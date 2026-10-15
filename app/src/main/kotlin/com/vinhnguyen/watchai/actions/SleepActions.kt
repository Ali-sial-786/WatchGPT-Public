package io.github.alisial786.watchgpt.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.alisial786.watchgpt.brain.BrainLogger
import io.github.alisial786.watchgpt.brain.LogEvent
import io.github.alisial786.watchgpt.brain.ToolSpec
import io.github.alisial786.watchgpt.brain.Toolbox
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.ZoneId

/**
 * How the user slept, from Health Connect: the sleep Samsung Health (or another sleep app) shares there, with its
 * stages ([SleepReport]). Read only when the user asks, at most two weeks back, and never kept. It's private
 * ([Safety]): the phone tells the user itself, and ChatGPT only hears that it did, unless the user turned on "Say it in
 * ChatGPT's voice" ([Safety.toCloud]). Allowed in What WatchGPT can do ([access], [permissions]);
 * [io.github.alisial786.watchgpt.HealthPrivacyActivity] is what Health Connect shows for it.
 */
class SleepActions(
    context: Context,
    private val logger: BrainLogger = BrainLogger.None,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : Toolbox {
    private val appContext = context.applicationContext

    enum class Access { ALLOWED, ASK, UPDATE, UNAVAILABLE }

    override fun tools(): List<ToolSpec> = SPECS

    /** Whether Health Connect is on this phone and lets WatchGPT read sleep. */
    suspend fun access(): Access = when (HealthConnectClient.getSdkStatus(appContext)) {
        HealthConnectClient.SDK_AVAILABLE ->
            try {
                if (READ in client().permissionController.getGrantedPermissions()) Access.ALLOWED else Access.ASK
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Access.ASK
            }

        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Access.UPDATE

        else -> Access.UNAVAILABLE
    }

    /**
     * What "What WatchGPT can do" asks Health Connect for: sleep, and reading it in the background where Health Connect
     * can do that. Conversations run in a foreground service, which Health Connect counts as open; the background covers
     * the rest, like the chat left while it answers.
     */
    fun permissions(): Set<String> {
        val background =
            runCatching {
                client().features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            }.getOrDefault(false)
        return if (background) setOf(READ, HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) else setOf(READ)
    }

    override suspend fun run(
        name: String,
        argumentsJson: String,
    ): String {
        if (name != READ_SLEEP) return done(name, "unknown", "error: there is no action called $name")
        val args = ActionArgs.parse(argumentsJson) ?: return done(name, "invalid", "error: the arguments were not valid JSON")
        // More than Health Connect can be asked for ("this month"): as much as it can, said so.
        val asked = (args["nights"] as? JsonPrimitive)?.intOrNull ?: 1
        val nights = asked.coerceIn(1, SleepReport.MAX_NIGHTS)
        when (access()) {
            Access.ALLOWED -> Unit
            Access.ASK -> return done(name, "denied", NOT_ALLOWED)
            Access.UPDATE -> return done(name, "unavailable", UPDATE)
            Access.UNAVAILABLE -> return done(name, "unavailable", NO_HEALTH_CONNECT)
        }
        val zone = zone()
        val today = LocalDate.now(zone)
        val (from, to) = SleepReport.window(nights, today, zone)
        val records =
            try {
                client().readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to), pageSize = PAGE)).records
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // Allowed a moment ago (access), so: read while Android counted WatchGPT as closed (no conversation's
                // foreground service running), without access in the background.
                return done(name, "denied", NOT_IN_BACKGROUND)
            } catch (e: Exception) {
                // RemoteException, IOException (a rate limit too), or Health Connect busy updating.
                return done(name, "failed", NO_ANSWER)
            }
        val sessions = records.map { it.toSession() }
        val report = SleepReport.describe(sessions, nights, today, zone, DateFormat.is24HourFormat(appContext)) ?: return done(name, "empty", nothing(nights))
        return done(name, "ok", if (asked > nights) "Only the last ${SleepReport.MAX_NIGHTS} days can be read. $report" else report)
    }

    private fun SleepSessionRecord.toSession() = SleepSession(
        start = startTime,
        end = endTime,
        stages = stages.map { SleepStage(it.startTime, it.endTime, KINDS[it.stage] ?: SleepStage.Kind.UNKNOWN) },
        source = appName(metadata.dataOrigin.packageName),
        startZone = startZoneOffset,
        endZone = endZoneOffset,
    )

    /** "Samsung Health": the name the phone shows for the app that wrote it. */
    private fun appName(packageName: String): String = runCatching {
        appContext.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: KNOWN_APPS[packageName] ?: "another app"

    private fun client() = HealthConnectClient.getOrCreate(appContext)

    private fun done(
        tool: String,
        outcome: String,
        result: String,
    ): String {
        logger.log(LogEvent.ToolUsed(tool, outcome))
        return result
    }

    companion object {
        const val READ_SLEEP = "read_sleep"

        /** android.permission.health.READ_SLEEP */
        val READ = HealthPermission.getReadPermission(SleepSessionRecord::class)

        /** Health Connect itself, for an update (Android 13 and older, where it's an app) or its settings. */
        fun updateIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding")).setPackage("com.android.vending")

        fun settingsIntent(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

        private const val PROVIDER = "com.google.android.apps.healthdata"
        private const val PAGE = 1_000

        private val KINDS =
            mapOf(
                SleepSessionRecord.STAGE_TYPE_AWAKE to SleepStage.Kind.AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to SleepStage.Kind.AWAKE,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to SleepStage.Kind.OUT_OF_BED,
                SleepSessionRecord.STAGE_TYPE_LIGHT to SleepStage.Kind.LIGHT,
                SleepSessionRecord.STAGE_TYPE_DEEP to SleepStage.Kind.DEEP,
                SleepSessionRecord.STAGE_TYPE_REM to SleepStage.Kind.REM,
                SleepSessionRecord.STAGE_TYPE_SLEEPING to SleepStage.Kind.ASLEEP,
            )

        private val KNOWN_APPS =
            mapOf(
                "com.sec.android.app.shealth" to "Samsung Health",
                "com.google.android.apps.fitness" to "Google Fit",
                "com.fitbit.FitbitMobile" to "Fitbit",
            )

        private const val NOT_ALLOWED =
            "error: WatchGPT isn't allowed to read sleep yet. Tell the user to allow it in the WatchGPT app on their phone: in the menu, " +
                "What WatchGPT can do, then Allow next to Sleep; Health Connect asks them there."
        private const val UPDATE =
            "error: Health Connect needs installing or updating from the Play Store before WatchGPT can read sleep. Tell the user to tap " +
                "Update next to Sleep in the WatchGPT app on their phone: in the menu, What WatchGPT can do."
        private const val NO_HEALTH_CONNECT =
            "error: this phone has no Health Connect, which is where WatchGPT reads sleep from, so it can't tell how the user slept."
        private const val NOT_IN_BACKGROUND =
            "error: Health Connect only lets WatchGPT read sleep while it's open on the phone, and it wasn't just now. Tell the user to " +
                "open WatchGPT on the phone and ask again. To ask with it closed, they can allow WatchGPT access in the background in " +
                "Health Connect (App permissions, then WatchGPT), where their phone's Health Connect offers it."
        private const val NO_ANSWER = "error: Health Connect didn't answer just now, so nothing was read. Say so; asking again in a moment usually works."

        private fun nothing(nights: Int): String {
            val span = if (nights == 1) "last night or the night before" else "the last $nights nights"
            return "error: Health Connect has no sleep for $span. Samsung Health only shares sleep there once that's on: in Samsung " +
                "Health, Settings, Health Connect, let it write Sleep. Last night can also take a while to arrive after waking; opening " +
                "Samsung Health sends it."
        }

        val SPECS =
            listOf(
                ToolSpec(
                    READ_SLEEP,
                    "How the user slept, from Health Connect, where Samsung Health and other sleep apps share it: when they fell asleep and " +
                        "woke up, time asleep and sleep stages (deep, light, REM, awake), by the day they woke up. Read on the phone, which " +
                        "tells the user itself, unless they chose to hear it from you: then you get it. Health Connect has no sleep score: " +
                        "Samsung Health keeps its score to itself. Asked for the score, still use this (it says there's none, and how they " +
                        "slept); with the phone unlocked, the score can also be read on Samsung Health's own screen.",
                    """{"type":"object","properties":{"nights":{"type":"integer","minimum":1,"maximum":${SleepReport.MAX_NIGHTS},"description":"How many days back, counting today, at most ${SleepReport.MAX_NIGHTS}. Default 1: last night."},""" +
                        """"instruction":{"type":"string","description":"In English: exactly what the phone should find in it and tell the user."},""" +
                        """"language":{"type":"string","description":"The language the user is speaking, as a BCP 47 tag: the phone answers and speaks in it."}},"additionalProperties":false}""",
                ),
            )
    }
}

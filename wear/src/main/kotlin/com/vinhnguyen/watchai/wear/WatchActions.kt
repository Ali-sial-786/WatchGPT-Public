package io.github.alisial786.watchgpt.wear

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.AlarmClock
import io.github.alisial786.watchgpt.watchlink.Control

/**
 * What the watch does itself when the phone asks during a call: timers and alarms in its own clock
 * apps, telling its battery, and whether it's unlocked. Android only lets an app open another app's screen while it is on
 * screen itself, which it is during a conversation.
 */
internal class WatchActions(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** A short sentence for the model: "ok: …" or "error: …". */
    fun run(
        request: Control,
        onScreen: Boolean,
    ): String = when (request.type) {
        "timer", "alarm" -> clock(request, onScreen)
        "battery" -> battery()
        "locked" -> locked()
        else -> "error: the watch can't do ${request.type}"
    }

    /**
     * For the phone's yes to a text or call: a watch with a screen lock is only unlocked while it's
     * on the wrist it was unlocked on. Without a lock the watch can't tell.
     */
    private fun locked(): String {
        val keyguard = appContext.getSystemService(KeyguardManager::class.java)
        return when {
            !keyguard.isDeviceSecure -> "no lock"
            keyguard.isDeviceLocked -> "locked"
            else -> "unlocked"
        }
    }

    private fun battery(): String {
        val battery = appContext.getSystemService(BatteryManager::class.java)
        val charging = if (battery.isCharging) ", charging" else ""
        return "watch: battery ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%$charging"
    }

    @SuppressLint("WearRecents") // another app's screen, started from the app context: it needs a task
    private fun clock(
        request: Control,
        onScreen: Boolean,
    ): String = try {
        io.github.alisial786.watchgpt.organizer.NativeClock.request(
            appContext,
            onScreen,
            seconds = if (request.type == "timer") request.seconds else null,
            hour = request.hour,
            minute = request.minute ?: 0,
            label = request.label,
            days = request.days,
        )
    } catch (_: IllegalArgumentException) {
        "error: invalid alarm or timer details"
    }
}

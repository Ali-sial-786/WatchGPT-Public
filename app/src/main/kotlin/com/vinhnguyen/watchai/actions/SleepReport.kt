package io.github.alisial786.watchgpt.actions

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One sleep as Health Connect has it, a night or a nap, with its stages if the app that wrote it recorded them. */
data class SleepSession(
    val start: Instant,
    val end: Instant,
    val stages: List<SleepStage>,
    /** The app that wrote it, by name ("Samsung Health"). */
    val source: String,
    /** Where the user was when they fell asleep and woke up; null when the app didn't say. */
    val startZone: ZoneOffset? = null,
    val endZone: ZoneOffset? = null,
)

data class SleepStage(
    val start: Instant,
    val end: Instant,
    val kind: Kind,
) {
    enum class Kind { AWAKE, OUT_OF_BED, LIGHT, DEEP, REM, ASLEEP, UNKNOWN }
}

/**
 * How the user slept, in plain English sentences: what the phone's own model answers from, or what the phone reads out
 * as it is when that model isn't there. By the day the user woke up, newest first. A day's night is its longest sleep
 * and the sleeps a short wake-up away from it (Samsung Health starts a new one after a long one), added up; other
 * sleeps that day are told after it, and a day with only a daytime nap has no night. Sleep scores aren't in Health
 * Connect, and the text says so: asked for one, the phone's model must not make one up.
 */
object SleepReport {
    const val MAX_NIGHTS = 14

    /**
     * What to ask Health Connect for: sleeps that end on one of the last [nights] days (at least two, see [describe]),
     * which started at most a day before.
     */
    fun window(
        nights: Int,
        today: LocalDate,
        zone: ZoneId,
    ): Pair<Instant, Instant> = today.minusDays(maxOf(nights, 2).toLong()).atStartOfDay(zone).toInstant() to today.plusDays(1).atStartOfDay(zone).toInstant()

    /** The last [nights] days up to [today], from [sessions]; null when none of them has any sleep. */
    fun describe(
        sessions: List<SleepSession>,
        nights: Int,
        today: LocalDate,
        zone: ZoneId,
        hours24: Boolean,
    ): String? {
        val all = distinct(sessions).groupBy { wokeOn(it, zone) }
        val asked = (0 until nights).map { today.minusDays(it.toLong()) }
        // Asked about last night before it reached Health Connect (Samsung Health shares it a while after waking): the night before too, told apart.
        val nightToday = all[today].orEmpty().any { !nap(it, zone) }
        val days = if (nights == 1 && !nightToday) asked + today.minusDays(1) else asked
        val byDay = all.filterKeys { it in days }
        if (byDay.isEmpty()) return null
        val time = DateTimeFormatter.ofPattern(if (hours24) "HH:mm" else "h:mm a", Locale.ENGLISH)
        val sources = byDay.values.flatten().map { it.source }.distinct()
        val lines = mutableListOf("Sleep in Health Connect, from ${sources.joinToString(" and ")}.")
        val asleep = mutableListOf<Duration>()
        for (day in days) {
            val slept = byDay[day].orEmpty().sortedBy { it.start }
            val label = "Woke up ${dayName(day, today)}"
            if (slept.isEmpty()) {
                lines += if (day == today) "$label: nothing recorded yet." else "$label: nothing recorded."
                continue
            }
            val main = slept.maxBy { totals(it).asleep }
            if (nap(main, zone)) {
                lines += "$label: no night recorded, only " + slept.joinToString(" and ") { "a nap, ${tell(it, zone, time)}" } + "."
                continue
            }
            val night = nightOf(slept, main)
            val total = night.fold(Duration.ZERO) { sum, it -> sum + totals(it).asleep }
            asleep += total
            val told = if (night.size == 1) tell(main, zone, time) else "${say(total)} asleep in all, in ${night.size} parts: " + night.joinToString("; ") { tell(it, zone, time) }
            lines += "$label: $told." + slept.filter { it !in night }.joinToString("") { " Also ${tell(it, zone, time)}." }
        }
        if (asleep.size > 1) lines += "On average ${say(asleep.fold(Duration.ZERO, Duration::plus).dividedBy(asleep.size.toLong()))} asleep over the ${asleep.size} nights with sleep."
        lines += "There is no sleep score: Health Connect has none, as apps like Samsung Health keep their scores to themselves."
        return lines.joinToString(" ")
    }

    /** Time asleep and awake in a session, from its stages; without stages, all of it counts as asleep. */
    data class Totals(
        val asleep: Duration,
        val byKind: Map<SleepStage.Kind, Duration>,
        val wakings: Int,
        val staged: Boolean,
    )

    fun totals(session: SleepSession): Totals {
        val whole = Duration.between(session.start, session.end)
        // Stages shouldn't overlap or leave the session, but an app can write them that way: each moment counts once.
        val byKind = LinkedHashMap<SleepStage.Kind, Duration>()
        var covered = session.start
        var wakings = 0
        var sleptYet = false
        var awakeSince = false
        for (stage in session.stages.sortedBy { it.start }) {
            val from = maxOf(stage.start, covered)
            val to = minOf(stage.end, session.end)
            if (!to.isAfter(from)) continue
            covered = to
            byKind[stage.kind] = (byKind[stage.kind] ?: Duration.ZERO) + Duration.between(from, to)
            when (stage.kind) {
                in ASLEEP -> {
                    if (sleptYet && awakeSince) wakings++
                    sleptYet = true
                    awakeSince = false
                }

                in AWAKE -> awakeSince = true

                else -> Unit
            }
        }
        val asleep = ASLEEP.fold(Duration.ZERO) { sum, kind -> sum + (byKind[kind] ?: Duration.ZERO) }
        if (asleep.isZero) return Totals(whole, emptyMap(), 0, staged = false)
        return Totals(asleep, byKind, wakings, staged = true)
    }

    /** "11:42 PM to 6:58 AM, 6 hours 51 minutes asleep of 7 hours 16 minutes (deep …, light …, REM …, awake …, woke up 3 times)". */
    private fun tell(
        session: SleepSession,
        zone: ZoneId,
        time: DateTimeFormatter,
    ): String {
        val from = session.start.atZone(session.startZone ?: zone).format(time)
        val to = session.end.atZone(session.endZone ?: zone).format(time)
        val whole = Duration.between(session.start, session.end)
        val t = totals(session)
        if (!t.staged) return "$from to $to, ${say(whole)} (no sleep stages recorded)"
        val parts =
            STAGE_NAMES.mapNotNull { (kind, name) -> t.byKind[kind]?.takeIf { !it.isZero }?.let { "$name ${say(it)}" } } +
                listOfNotNull(
                    AWAKE.fold(Duration.ZERO) { sum, kind -> sum + (t.byKind[kind] ?: Duration.ZERO) }.takeIf { !it.isZero }?.let { "awake ${say(it)}" },
                    when (t.wakings) {
                        0 -> null
                        1 -> "woke up once"
                        else -> "woke up ${t.wakings} times"
                    },
                )
        return "$from to $to, ${say(t.asleep)} asleep of ${say(whole)} (${parts.joinToString(", ")})"
    }

    /** [main] and the sleeps around it with less than [NIGHT_GAP] awake between them, in order. */
    private fun nightOf(
        slept: List<SleepSession>,
        main: SleepSession,
    ): List<SleepSession> {
        var first = slept.indexOf(main)
        var last = first
        while (first > 0 && Duration.between(slept[first - 1].end, slept[first].start) < NIGHT_GAP) first--
        while (last < slept.lastIndex && Duration.between(slept[last].end, slept[last + 1].start) < NIGHT_GAP) last++
        return slept.subList(first, last + 1)
    }

    /** Begun in the day and short: not a night. */
    private fun nap(
        session: SleepSession,
        zone: ZoneId,
    ): Boolean = session.start.atZone(session.startZone ?: zone).hour in NAP_HOURS && Duration.between(session.start, session.end) < NAP_LONGEST

    /** The same sleep written by two apps (Samsung Health and Google Fit, say) counts once: the one with stages, else the longer. */
    private fun distinct(sessions: List<SleepSession>): List<SleepSession> {
        val kept = mutableListOf<SleepSession>()
        val best = sessions.filter { it.end.isAfter(it.start) }.sortedWith(compareByDescending<SleepSession> { totals(it).staged }.thenByDescending { Duration.between(it.start, it.end) })
        for (s in best) if (kept.none { same(it, s) }) kept += s
        return kept
    }

    /** Overlapping by at least half of the shorter one. */
    private fun same(
        a: SleepSession,
        b: SleepSession,
    ): Boolean {
        val overlap = Duration.between(maxOf(a.start, b.start), minOf(a.end, b.end))
        val shorter = minOf(Duration.between(a.start, a.end), Duration.between(b.start, b.end))
        return !overlap.isNegative && overlap.multipliedBy(2) >= shorter
    }

    private fun wokeOn(
        session: SleepSession,
        zone: ZoneId,
    ): LocalDate = session.end.atZone(session.endZone ?: zone).toLocalDate()

    private fun dayName(
        day: LocalDate,
        today: LocalDate,
    ): String {
        val name = "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}"
        return when (day) {
            today -> "today ($name)"
            today.minusDays(1) -> "yesterday ($name)"
            else -> name
        }
    }

    /** "7 hours 16 minutes", "1 hour", "25 minutes": said aloud as it's written. */
    fun say(d: Duration): String {
        val minutes = (d.seconds + 30) / 60
        val h = minutes / 60
        val m = minutes % 60
        val hours = if (h == 1L) "1 hour" else "$h hours"
        val mins = if (m == 1L) "1 minute" else "$m minutes"
        return when {
            h == 0L -> mins
            m == 0L -> hours
            else -> "$hours $mins"
        }
    }

    private val NIGHT_GAP = Duration.ofHours(3)
    private val NAP_HOURS = 10 until 18
    private val NAP_LONGEST = Duration.ofHours(3)
    private val ASLEEP = setOf(SleepStage.Kind.LIGHT, SleepStage.Kind.DEEP, SleepStage.Kind.REM, SleepStage.Kind.ASLEEP)
    private val AWAKE = setOf(SleepStage.Kind.AWAKE, SleepStage.Kind.OUT_OF_BED)
    private val STAGE_NAMES = listOf(SleepStage.Kind.DEEP to "deep", SleepStage.Kind.LIGHT to "light", SleepStage.Kind.REM to "REM", SleepStage.Kind.ASLEEP to "sleep of no known stage")
}

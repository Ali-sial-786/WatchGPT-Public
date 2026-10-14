package io.github.alisial786.watchgpt.reminders

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** The deliberately bounded subset of RFC 5545 shared by both editors. Unsupported edits stay pending. */
object CalendarRules {
    fun encode(entry: Entry): String = when (entry.repeat) {
        Repeat.NONE -> ""

        Repeat.DAILY -> "FREQ=DAILY"

        Repeat.WEEKDAYS -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"

        Repeat.WEEKLY -> "FREQ=WEEKLY"

        Repeat.MONTHLY -> {
            val day = entry.monthlyDay ?: LocalDateTime.parse(entry.localTime).dayOfMonth
            if (day <= 28) "FREQ=MONTHLY;BYMONTHDAY=$day" else "FREQ=MONTHLY;BYMONTHDAY=${(28..day).joinToString(",")};BYSETPOS=-1"
        }
    }
    data class Decoded(val repeat: Repeat, val monthlyDay: Int? = null)

    /**
     * Reads a calendar rule written by WatchGPT or rewritten by a calendar's sync ("FREQ=WEEKLY" comes back from
     * Google as "FREQ=WEEKLY;BYDAY=TU;WKST=SU"). [start] is the event's first date. Throws [IllegalArgumentException]
     * for anything WatchGPT can't represent exactly.
     */
    fun decode(rule: String, start: LocalDate? = null): Decoded {
        if (rule.isBlank()) return Decoded(Repeat.NONE)
        val parts = rule.uppercase().split(';').associate {
            val p = it.split('=', limit = 2)
            require(p.size == 2) { UNSUPPORTED }
            p[0] to p[1]
        }.toMutableMap()
        require(parts.remove("INTERVAL").let { it == null || it == "1" }) { "Custom calendar intervals are not supported" }
        parts.remove("WKST")
        val frequency = parts.remove("FREQ")
        val days = parts.remove("BYDAY")
        val month = parts.remove("BYMONTHDAY")
        val position = parts.remove("BYSETPOS")
        require(parts.isEmpty()) { "Calendar recurrence end dates or exceptions are not supported" }
        return when {
            frequency == "DAILY" && days == null && month == null && position == null -> Decoded(Repeat.DAILY)

            frequency == "WEEKLY" && days == null && month == null && position == null -> Decoded(Repeat.WEEKLY)

            frequency == "WEEKLY" && days?.split(',')?.toSet() == WORKDAYS && month == null && position == null -> Decoded(Repeat.WEEKDAYS)

            frequency == "DAILY" && days?.split(',')?.toSet() == WORKDAYS && month == null && position == null -> Decoded(Repeat.WEEKDAYS)

            frequency == "WEEKLY" && start != null && days == code(start.dayOfWeek) && month == null && position == null -> Decoded(Repeat.WEEKLY)

            frequency == "MONTHLY" && days == null -> {
                val numbers = month?.split(',')?.map { it.toInt() }
                require(numbers == null || numbers.all { it in 1..31 }) { UNSUPPORTED }
                require((position == null && (numbers == null || numbers.size == 1)) || (position == "-1" && numbers != null && numbers == (28..numbers.last()).toList())) { UNSUPPORTED }
                Decoded(Repeat.MONTHLY, numbers?.last())
            }

            else -> throw IllegalArgumentException(UNSUPPORTED)
        }
    }

    private val WORKDAYS = setOf("MO", "TU", "WE", "TH", "FR")
    private const val UNSUPPORTED = "Calendar recurrence is not supported; local record was kept"

    private fun code(day: DayOfWeek) = day.name.take(2)
}

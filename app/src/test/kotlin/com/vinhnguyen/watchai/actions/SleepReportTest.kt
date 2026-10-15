package io.github.alisial786.watchgpt.actions

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class SleepReportTest {
    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 10, 15)

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun stage(
        from: String,
        to: String,
        kind: SleepStage.Kind,
    ) = SleepStage(at(from), at(to), kind)

    /** 23:42 to 06:58, as Samsung Health writes it: light, deep, light, awake, REM, light, awake, REM. */
    private val night =
        SleepSession(
            at("2026-10-14T23:42"),
            at("2026-10-15T06:58"),
            listOf(
                stage("2026-10-14T23:42", "2026-10-15T00:20", SleepStage.Kind.LIGHT),
                stage("2026-10-15T00:20", "2026-10-15T01:30", SleepStage.Kind.DEEP),
                stage("2026-10-15T01:30", "2026-10-15T03:00", SleepStage.Kind.LIGHT),
                stage("2026-10-15T03:00", "2026-10-15T03:15", SleepStage.Kind.AWAKE),
                stage("2026-10-15T03:15", "2026-10-15T04:16", SleepStage.Kind.REM),
                stage("2026-10-15T04:16", "2026-10-15T05:48", SleepStage.Kind.LIGHT),
                stage("2026-10-15T05:48", "2026-10-15T05:58", SleepStage.Kind.AWAKE),
                stage("2026-10-15T05:58", "2026-10-15T06:58", SleepStage.Kind.REM),
            ),
            "Samsung Health",
        )

    /** [night], [days] earlier, stages and all. */
    private fun nightBefore(days: Long): SleepSession {
        val by = Duration.ofDays(days)
        return night.copy(start = night.start.minus(by), end = night.end.minus(by), stages = night.stages.map { it.copy(start = it.start.minus(by), end = it.end.minus(by)) })
    }

    private fun describe(
        sessions: List<SleepSession>,
        nights: Int = 1,
        hours24: Boolean = false,
    ) = SleepReport.describe(sessions, nights, today, zone, hours24)

    @Test
    fun `a night with stages is told in full`() {
        assertThat(describe(listOf(night))).isEqualTo(
            "Sleep in Health Connect, from Samsung Health. Woke up today (Thursday 15 October): 11:42 PM to 6:58 AM, 6 hours 51 minutes " +
                "asleep of 7 hours 16 minutes (deep 1 hour 10 minutes, light 3 hours 40 minutes, REM 2 hours 1 minute, awake 25 minutes, " +
                "woke up 2 times). There is no sleep score: Health Connect has none, as apps like Samsung Health keep their scores to themselves.",
        )
        assertThat(describe(listOf(night), hours24 = true)).contains("23:42 to 06:58")
    }

    @Test
    fun `a night without stages is all asleep`() {
        val plain = night.copy(stages = emptyList(), source = "Google Fit")
        assertThat(describe(listOf(plain))).contains("from Google Fit. Woke up today (Thursday 15 October): 11:42 PM to 6:58 AM, 7 hours 16 minutes (no sleep stages recorded).")
        // Stages that only say "awake" don't tell how long they slept either.
        val awake = night.copy(stages = listOf(stage("2026-10-15T03:00", "2026-10-15T03:15", SleepStage.Kind.AWAKE)))
        assertThat(SleepReport.totals(awake).staged).isFalse()
    }

    @Test
    fun `last night not in yet tells the night before`() {
        val before = night.copy(start = night.start.minus(Duration.ofDays(1)), end = night.end.minus(Duration.ofDays(1)), stages = emptyList())
        val text = describe(listOf(before))!!
        assertThat(text).contains("Woke up today (Thursday 15 October): nothing recorded yet.")
        assertThat(text).contains("Woke up yesterday (Wednesday 14 October): 11:42 PM to 6:58 AM, 7 hours 16 minutes")
        // Nothing for either: nothing to tell.
        assertThat(describe(listOf(before.copy(start = before.start.minus(Duration.ofDays(3)), end = before.end.minus(Duration.ofDays(3)))))).isNull()
        assertThat(describe(emptyList())).isNull()
    }

    @Test
    fun `several nights, a nap, and the average`() {
        val yesterday = night.copy(start = night.start.minus(Duration.ofDays(1)), end = night.end.minus(Duration.ofDays(1)).minus(Duration.ofMinutes(16)), stages = emptyList())
        val nap = SleepSession(at("2026-10-15T13:10"), at("2026-10-15T13:50"), emptyList(), "Samsung Health")
        val text = describe(listOf(nap, night, yesterday), nights = 3)!!
        assertThat(text).contains("woke up 2 times). Also 1:10 PM to 1:50 PM, 40 minutes (no sleep stages recorded).")
        assertThat(text).contains("Woke up yesterday (Wednesday 14 October): 11:42 PM to 6:42 AM, 7 hours")
        assertThat(text).contains("Woke up Tuesday 13 October: nothing recorded.")
        // The nights' main sleep, not the nap: (6:51 + 7:00) / 2.
        assertThat(text).contains("On average 6 hours 56 minutes asleep over the 2 nights with sleep.")
    }

    @Test
    fun `a night recorded in two parts is added up, and counts whole in the average`() {
        val first = SleepSession(at("2026-10-14T23:00"), at("2026-10-15T03:00"), emptyList(), "Samsung Health")
        val second = SleepSession(at("2026-10-15T03:40"), at("2026-10-15T07:00"), emptyList(), "Samsung Health")
        val before = SleepSession(at("2026-10-13T23:00"), at("2026-10-14T07:00"), emptyList(), "Samsung Health")
        val text = describe(listOf(second, first, before), nights = 2)!!
        assertThat(text).contains(
            "Woke up today (Thursday 15 October): 7 hours 20 minutes asleep in all, in 2 parts: 11:00 PM to 3:00 AM, 4 hours (no sleep " +
                "stages recorded); 3:40 AM to 7:00 AM, 3 hours 20 minutes (no sleep stages recorded).",
        )
        assertThat(text).contains("On average 7 hours 40 minutes asleep over the 2 nights with sleep.")
    }

    @Test
    fun `a day with only a nap has no night`() {
        val nap = SleepSession(at("2026-10-15T13:10"), at("2026-10-15T13:50"), emptyList(), "Samsung Health")
        val before = nightBefore(1)
        // Asked about last night: the nap isn't it, so the night before comes too.
        val text = describe(listOf(nap, before))!!
        assertThat(text).contains("Woke up today (Thursday 15 October): no night recorded, only a nap, 1:10 PM to 1:50 PM, 40 minutes (no sleep stages recorded).")
        assertThat(text).contains("Woke up yesterday (Wednesday 14 October): 11:42 PM to 6:58 AM, 6 hours 51 minutes asleep")
        // And it isn't a night in the average.
        val week = (1L..6L).map { nightBefore(it) }
        assertThat(describe(week + nap, nights = 7)).contains("On average 6 hours 51 minutes asleep over the 6 nights with sleep.")
    }

    @Test
    fun `the same night from two apps counts once, the one with stages`() {
        val copy = night.copy(start = night.start.plus(Duration.ofMinutes(5)), stages = emptyList(), source = "Google Fit")
        val text = describe(listOf(copy, night))!!
        assertThat(text).contains("from Samsung Health. ")
        assertThat(text).doesNotContain("Also")
    }

    @Test
    fun `overlapping or stray stages count once, inside the night`() {
        val messy =
            night.copy(
                stages =
                listOf(
                    stage("2026-10-14T23:00", "2026-10-15T01:00", SleepStage.Kind.LIGHT),
                    stage("2026-10-15T00:30", "2026-10-15T02:00", SleepStage.Kind.DEEP),
                    stage("2026-10-15T06:00", "2026-10-15T08:00", SleepStage.Kind.REM),
                ),
            )
        val totals = SleepReport.totals(messy)
        // Light from 23:42 (the night's start) to 01:00, deep 01:00 to 02:00, REM 06:00 to 06:58 (the night's end).
        assertThat(totals.byKind[SleepStage.Kind.LIGHT]).isEqualTo(Duration.ofMinutes(78))
        assertThat(totals.byKind[SleepStage.Kind.DEEP]).isEqualTo(Duration.ofMinutes(60))
        assertThat(totals.byKind[SleepStage.Kind.REM]).isEqualTo(Duration.ofMinutes(58))
        assertThat(totals.asleep).isEqualTo(Duration.ofMinutes(196))
        assertThat(totals.wakings).isEqualTo(0)
    }

    @Test
    fun `woken by where the user woke up`() {
        // Asleep in New York, with the phone back in London: the night ends on the 15th there, and is told in New York time.
        val travel = night.copy(start = at("2026-10-15T04:00"), end = at("2026-10-15T11:00"), stages = emptyList(), startZone = ZoneOffset.ofHours(-4), endZone = ZoneOffset.ofHours(-4))
        assertThat(describe(listOf(travel))).contains("Woke up today (Thursday 15 October): 11:00 PM to 6:00 AM, 7 hours")
    }

    @Test
    fun `durations are said as they're written`() {
        assertThat(SleepReport.say(Duration.ofMinutes(61))).isEqualTo("1 hour 1 minute")
        assertThat(SleepReport.say(Duration.ofMinutes(120))).isEqualTo("2 hours")
        assertThat(SleepReport.say(Duration.ofSeconds(25 * 60 + 29))).isEqualTo("25 minutes")
        assertThat(SleepReport.say(Duration.ofSeconds(59 * 60 + 30))).isEqualTo("1 hour")
        assertThat(SleepReport.say(Duration.ZERO)).isEqualTo("0 minutes")
    }

    @Test
    fun `the window reaches back far enough for the night before`() {
        val (from, to) = SleepReport.window(1, today, zone)
        assertThat(from).isEqualTo(at("2026-10-13T00:00"))
        assertThat(to).isEqualTo(at("2026-10-16T00:00"))
        assertThat(SleepReport.window(7, today, zone).first).isEqualTo(at("2026-10-08T00:00"))
    }
}

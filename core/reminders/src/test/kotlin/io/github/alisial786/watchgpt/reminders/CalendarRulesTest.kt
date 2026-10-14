package io.github.alisial786.watchgpt.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class CalendarRulesTest {
    @Test fun supportedRulesRoundTripIncludingShortMonths() {
        Repeat.entries.forEach { repeat ->
            (1..31).forEach { day ->
                val entry = Entry(title = "Synthetic calendar", localTime = "2027-01-%02dT09:00".format(day), repeat = repeat)
                val decoded = CalendarRules.decode(CalendarRules.encode(entry))
                assertEquals(repeat, decoded.repeat)
                if (repeat == Repeat.MONTHLY) assertEquals(day, decoded.monthlyDay)
            }
        }
    }

    @Test fun providerNormalizationIsAccepted() {
        assertEquals(Repeat.WEEKDAYS, CalendarRules.decode("WKST=SU;BYDAY=FR,TH,WE,TU,MO;INTERVAL=1;FREQ=WEEKLY").repeat)
    }

    @Test fun unsupportedEndAndCustomIntervalsAreNotSilentlyLost() {
        listOf("FREQ=DAILY;COUNT=10", "FREQ=WEEKLY;INTERVAL=2", "FREQ=YEARLY", "FREQ=MONTHLY;BYMONTHDAY=-1").forEach { rule ->
            assertThrows(RuntimeException::class.java) { CalendarRules.decode(rule) }
        }
    }

    @Test fun googleWeeklyFormNamesTheStartDay() {
        val tuesday = LocalDate.parse("2026-10-20")
        assertEquals(Repeat.WEEKLY, CalendarRules.decode("FREQ=WEEKLY;WKST=SU;BYDAY=TU", tuesday).repeat)
        assertThrows(IllegalArgumentException::class.java) { CalendarRules.decode("FREQ=WEEKLY;BYDAY=WE", tuesday) }
        assertThrows(IllegalArgumentException::class.java) { CalendarRules.decode("FREQ=WEEKLY;BYDAY=TU,TH", tuesday) }
    }

    @Test fun dailyOnWorkdaysIsWeekdays() {
        assertEquals(Repeat.WEEKDAYS, CalendarRules.decode("FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR").repeat)
    }

    @Test fun everyRejectionIsTheSameKind() {
        listOf("FREQ=YEARLY", "nonsense", "FREQ=MONTHLY;BYMONTHDAY=x", "FREQ=DAILY;UNTIL=20270101").forEach { rule ->
            assertThrows(IllegalArgumentException::class.java) { CalendarRules.decode(rule) }
        }
    }

    @Test fun aCalendarMonthlyOnThe31stSkipsShortMonthsSoIsNotTakenAsMonthEnd() {
        assertThrows(IllegalArgumentException::class.java) { CalendarRules.decode("FREQ=MONTHLY;BYMONTHDAY=31") }
        assertThrows(IllegalArgumentException::class.java) { CalendarRules.decode("FREQ=MONTHLY", LocalDate.parse("2027-01-30")) }
        assertEquals(Repeat.MONTHLY, CalendarRules.decode("FREQ=MONTHLY", LocalDate.parse("2027-01-15")).repeat)
        assertEquals(31, CalendarRules.decode("FREQ=MONTHLY;BYMONTHDAY=28,29,30,31;BYSETPOS=-1").monthlyDay)
    }
}

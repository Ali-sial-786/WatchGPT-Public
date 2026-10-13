package io.github.alisial786.watchgpt.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
}

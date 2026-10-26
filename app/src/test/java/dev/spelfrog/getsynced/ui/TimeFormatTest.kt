package dev.spelfrog.getsynced.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class TimeFormatTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).toInstant(zone).toEpochMilli()

    private val now = at(2026, 10, 26, 19, 55)

    private fun rel(then: Long, nowMs: Long = now) = relativeTime(nowMs, then, zone)

    @Test fun justNow() {
        assertEquals("just now", rel(now))
        assertEquals("just now", rel(now - 59_000))
        assertEquals("just now", rel(now + 5_000)) // clock skew
    }

    @Test fun minutes() {
        assertEquals("1 min ago", rel(now - 60_000))
        assertEquals("25 min ago", rel(at(2026, 10, 26, 19, 30)))
        assertEquals("59 min ago", rel(now - 59 * 60_000 - 59_000))
    }

    @Test fun hoursSameDay() {
        assertEquals("1 h ago", rel(at(2026, 10, 26, 18, 55)))
        assertEquals("19 h ago", rel(at(2026, 10, 26, 0, 30)))
    }

    @Test fun hoursAcrossMidnightWhenRecent() {
        val earlyMorning = at(2026, 10, 27, 2, 0)
        assertEquals("4 h ago", rel(at(2026, 10, 26, 22, 0), earlyMorning))
    }

    @Test fun yesterday() {
        assertEquals("yesterday", rel(at(2026, 10, 25, 19, 30)))
        assertEquals("yesterday", rel(at(2026, 10, 25, 0, 5)))
    }

    @Test fun olderDates() {
        assertEquals("9 Oct", rel(at(2026, 10, 9, 18, 23)))
        assertEquals("24 Oct", rel(at(2026, 10, 24, 23, 59)))
        assertEquals("31 Dec 2025", rel(at(2025, 12, 31, 12, 0)))
    }

    @Test fun clock() {
        assertEquals("19:30", clockTime(at(2026, 10, 26, 19, 30), zone))
        assertEquals("07:05", clockTime(at(2026, 10, 26, 7, 5), zone))
        assertEquals("00:00", clockTime(at(2026, 10, 26, 0, 0), zone))
    }

    @Test fun relativeWithClockJoins() {
        assertEquals("25 min ago · 19:30", relativeWithClock(now, at(2026, 10, 26, 19, 30), zone))
        assertEquals("yesterday · 08:15", relativeWithClock(now, at(2026, 10, 25, 8, 15), zone))
    }

    @Test fun durationsUseDotDecimal() {
        assertEquals("0.0 s", formatDuration(0))
        assertEquals("1.4 s", formatDuration(1_400))
        assertEquals("59.9 s", formatDuration(59_900))
        assertEquals("2 min 05 s", formatDuration(125_000))
    }

    @Test fun syncCountsFromSummary() {
        assertEquals(SyncCounts(1, 2), parseSyncCounts("pushed 1, pulled 2, 0.9 s"))
        assertEquals(SyncCounts(0, 0), parseSyncCounts("pushed 0, pulled 0, 3.0 s"))
        assertNull(parseSyncCounts("Push rejected: REJECTED"))
    }
}

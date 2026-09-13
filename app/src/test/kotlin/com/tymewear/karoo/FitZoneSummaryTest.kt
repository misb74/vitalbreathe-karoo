package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitZoneSummaryTest {

    @Test
    fun `zone ledger preserves its total and ignores invalid additions`() {
        val times = ZoneTimes()
            .add(zone = 1, seconds = 7L)
            .add(zone = 4, seconds = 11L)
            .add(zone = 9, seconds = 20L)
            .add(zone = 2, seconds = 0L)

        assertEquals(7L, times.z1)
        assertEquals(11L, times.z4)
        assertEquals(18L, times.total)
        assertEquals(times.total, (1..5).sumOf(times::get))
    }

    @Test
    fun `summary rejects empty or internally inconsistent totals`() {
        assertNull(FitZoneSummary.from(ZoneTimes()))
        assertNull(FitZoneSummary.from(ZoneTimes(z1 = 5L, total = 6L)))
    }

    @Test
    fun `known distribution converts to exact minutes and percentages`() {
        val summary = FitZoneSummary.from(
            ZoneTimes(z1 = 30L, z2 = 60L, z3 = 90L, z4 = 0L, z5 = 120L, total = 300L),
        )!!

        assertEquals(0.5, summary.minutes(1), 0.0)
        assertEquals(2.0, summary.minutes(5), 0.0)
        assertEquals(10.0, summary.percentage(1), 0.0)
        assertEquals(40.0, summary.percentage(5), 0.0)
        assertEquals(100.0, (1..5).sumOf(summary::percentage), 0.0001)
    }

    @Test
    fun `checkpoint waits for thirty seconds and forced writes do not duplicate`() {
        val tracker = FitSummaryTracker()

        assertFalse(tracker.markIfDue(29L, force = false))
        assertTrue(tracker.markIfDue(30L, force = false))
        assertFalse(tracker.markIfDue(30L, force = true))
        assertFalse(tracker.markIfDue(59L, force = false))
        assertTrue(tracker.markIfDue(59L, force = true))
        assertFalse(tracker.markIfDue(59L, force = true))
    }
}

package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFreshnessPolicyTest {

    @Test
    fun `hold time follows breathing rate within practical limits`() {
        assertEquals(5_000L, RecordingFreshnessPolicy.maxAgeMs(120.0))
        assertEquals(6_600L, RecordingFreshnessPolicy.maxAgeMs(20.0))
        assertEquals(13_200L, RecordingFreshnessPolicy.maxAgeMs(10.0))
        assertEquals(20_000L, RecordingFreshnessPolicy.maxAgeMs(4.0))
    }

    @Test
    fun `exact age boundary is fresh and the next millisecond is stale`() {
        assertTrue(RecordingFreshnessPolicy.isFresh(1_000L, 7_600L, 20.0))
        assertFalse(RecordingFreshnessPolicy.isFresh(1_000L, 7_601L, 20.0))
    }

    @Test
    fun `future timestamps and invalid breathing rates are never fresh`() {
        assertFalse(RecordingFreshnessPolicy.isFresh(2_000L, 1_999L, 20.0))
        assertFalse(RecordingFreshnessPolicy.isFresh(1_000L, 1_000L, 0.0))
        assertFalse(RecordingFreshnessPolicy.isFresh(1_000L, 1_000L, Double.NaN))
    }

    @Test
    fun `stale interval and recovery tick are not credited to a zone`() {
        val tracker = FitElapsedTracker()
        assertTrue(
            tracker.onRecordingTick(
                elapsedMs = 6_000L,
                hasFreshSensorData = RecordingFreshnessPolicy.isFresh(0L, 6_000L, 20.0),
            ).shouldEmitRecord,
        )

        assertFalse(
            tracker.onRecordingTick(
                elapsedMs = 7_000L,
                hasFreshSensorData = RecordingFreshnessPolicy.isFresh(0L, 7_000L, 20.0),
            ).shouldEmitRecord,
        )
        assertEquals(0L, tracker.onRecordingTick(10_000L, true).zoneSeconds)
        assertEquals(1L, tracker.onRecordingTick(11_000L, true).zoneSeconds)
    }
}

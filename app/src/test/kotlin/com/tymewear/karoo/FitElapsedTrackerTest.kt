package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitElapsedTrackerTest {

    @Test
    fun `first valid tick records without inventing a second`() {
        val result = FitElapsedTracker().onRecordingTick(10_000L, hasFreshSensorData = true)

        assertTrue(result.shouldEmitRecord)
        assertEquals(0L, result.zoneSeconds)
    }

    @Test
    fun `elapsed deltas and partial milliseconds become whole seconds`() {
        val tracker = FitElapsedTracker()
        tracker.onRecordingTick(10_000L, true)

        assertEquals(0L, tracker.onRecordingTick(10_450L, true).zoneSeconds)
        assertEquals(1L, tracker.onRecordingTick(11_050L, true).zoneSeconds)
        assertEquals(2L, tracker.onRecordingTick(13_200L, true).zoneSeconds)
    }

    @Test
    fun `duplicate or backwards ticks do not record twice`() {
        val tracker = FitElapsedTracker()
        tracker.onRecordingTick(10_000L, true)

        assertFalse(tracker.onRecordingTick(10_000L, true).shouldEmitRecord)
        assertFalse(tracker.onRecordingTick(9_000L, true).shouldEmitRecord)
        assertEquals(1L, tracker.onRecordingTick(11_000L, true).zoneSeconds)
    }

    @Test
    fun `pause reset excludes paused time`() {
        val tracker = FitElapsedTracker()
        tracker.onRecordingTick(10_000L, true)
        assertEquals(1L, tracker.onRecordingTick(11_000L, true).zoneSeconds)

        tracker.pause()

        assertFalse(tracker.onRecordingTick(11_000L, true).shouldEmitRecord)
        assertEquals(1L, tracker.onRecordingTick(12_000L, true).zoneSeconds)
    }

    @Test
    fun `sensor dropout excludes the gap and recovery tick`() {
        val tracker = FitElapsedTracker()
        tracker.onRecordingTick(10_000L, true)
        assertEquals(1L, tracker.onRecordingTick(11_000L, true).zoneSeconds)

        assertFalse(tracker.onRecordingTick(12_000L, false).shouldEmitRecord)
        assertEquals(0L, tracker.onRecordingTick(45_000L, true).zoneSeconds)
        assertEquals(1L, tracker.onRecordingTick(46_000L, true).zoneSeconds)
    }

    @Test
    fun `suspended collector never assigns an unobserved gap to one zone`() {
        val tracker = FitElapsedTracker()
        tracker.onRecordingTick(1_000L, hasFreshSensorData = true)

        val resumed = tracker.onRecordingTick(21_000L, hasFreshSensorData = true)
        val nextSecond = tracker.onRecordingTick(22_000L, hasFreshSensorData = true)

        assertTrue(resumed.shouldEmitRecord)
        assertEquals(0L, resumed.zoneSeconds)
        assertEquals(1L, nextSecond.zoneSeconds)
    }

    @Test
    fun `new ride reset allows elapsed time to restart`() {
        val tracker = FitElapsedTracker()
        tracker.onRecordingTick(50_000L, true)
        tracker.reset()

        assertTrue(tracker.onRecordingTick(0L, true).shouldEmitRecord)
        assertEquals(1L, tracker.onRecordingTick(1_000L, true).zoneSeconds)
    }
}

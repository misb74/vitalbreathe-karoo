package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneDisplayTrackerTest {

    @Test
    fun `zero recorded seconds still selects recording mode`() {
        val tracker = ZoneDisplayTracker()
        tracker.next(
            recordingState = ZoneRecordingState(),
            recordedZoneTimes = ZoneTimes(),
            freshLiveZone = 2,
            nowElapsedMs = 1_000L,
        )

        val frame = tracker.next(
            recordingState = ZoneRecordingState(isRecording = true, sessionId = 1L),
            recordedZoneTimes = ZoneTimes(),
            freshLiveZone = 4,
            nowElapsedMs = 2_000L,
        )

        assertEquals(ZoneDisplayMode.RECORDING, frame.mode)
        assertEquals(ZoneTimes(), frame.zoneTimes)
    }

    @Test
    fun `recorded totals never imply recording while mode is live`() {
        val tracker = ZoneDisplayTracker()
        tracker.next(
            recordingState = ZoneRecordingState(isRecording = false, sessionId = 7L),
            recordedZoneTimes = ZoneTimes(z5 = 90L, total = 90L),
            freshLiveZone = 3,
            nowElapsedMs = 1_000L,
        )
        val frame = tracker.next(
            recordingState = ZoneRecordingState(isRecording = false, sessionId = 7L),
            recordedZoneTimes = ZoneTimes(z5 = 90L, total = 90L),
            freshLiveZone = 3,
            nowElapsedMs = 2_000L,
        )

        assertEquals(ZoneDisplayMode.LIVE, frame.mode)
        assertEquals(ZoneTimes(z3 = 1L, total = 1L), frame.zoneTimes)
    }

    @Test
    fun `new session clears stale live bars`() {
        val tracker = ZoneDisplayTracker()
        repeat(5) { index ->
            tracker.next(
                recordingState = ZoneRecordingState(),
                recordedZoneTimes = ZoneTimes(),
                freshLiveZone = 1,
                nowElapsedMs = (index + 1) * 1_000L,
            )
        }

        val frame = tracker.next(
            recordingState = ZoneRecordingState(isRecording = true, sessionId = 1L),
            recordedZoneTimes = ZoneTimes(z2 = 2L, total = 2L),
            freshLiveZone = 1,
            nowElapsedMs = 6_000L,
        )

        assertEquals(ZoneDisplayMode.RECORDING, frame.mode)
        assertEquals(ZoneTimes(z2 = 2L, total = 2L), frame.zoneTimes)

        val liveAfterRide = tracker.next(
            recordingState = ZoneRecordingState(isRecording = false, sessionId = 1L),
            recordedZoneTimes = frame.zoneTimes,
            freshLiveZone = 4,
            nowElapsedMs = 7_000L,
        )
        assertEquals(ZoneTimes(), liveAfterRide.zoneTimes)

        val nextLiveFrame = tracker.next(
            recordingState = ZoneRecordingState(isRecording = false, sessionId = 1L),
            recordedZoneTimes = frame.zoneTimes,
            freshLiveZone = 4,
            nowElapsedMs = 8_000L,
        )
        assertEquals(ZoneTimes(z4 = 1L, total = 1L), nextLiveFrame.zoneTimes)
    }

    @Test
    fun `retained session id resets live bars even when active transition was missed`() {
        val tracker = ZoneDisplayTracker()
        repeat(4) { index ->
            tracker.next(
                recordingState = ZoneRecordingState(),
                recordedZoneTimes = ZoneTimes(),
                freshLiveZone = 2,
                nowElapsedMs = (index + 1) * 1_000L,
            )
        }

        val frame = tracker.next(
            recordingState = ZoneRecordingState(isRecording = false, sessionId = 5L),
            recordedZoneTimes = ZoneTimes(z5 = 10L, total = 10L),
            freshLiveZone = 3,
            nowElapsedMs = 5_000L,
        )

        assertEquals(ZoneDisplayMode.LIVE, frame.mode)
        assertEquals(ZoneTimes(), frame.zoneTimes)
    }

    @Test
    fun `live totals use monotonic elapsed deltas and retain subsecond carry`() {
        val tracker = ZoneDisplayTracker()
        val state = ZoneRecordingState()

        tracker.next(state, ZoneTimes(), freshLiveZone = 2, nowElapsedMs = 1_000L)
        val first = tracker.next(
            state,
            ZoneTimes(),
            freshLiveZone = 2,
            nowElapsedMs = 2_450L,
        )
        val second = tracker.next(
            state,
            ZoneTimes(),
            freshLiveZone = 2,
            nowElapsedMs = 3_300L,
        )

        assertEquals(ZoneTimes(z2 = 1L, total = 1L), first.zoneTimes)
        assertEquals(ZoneTimes(z2 = 2L, total = 2L), second.zoneTimes)
    }

    @Test
    fun `stale sensor data breaks the live clock and excludes the gap`() {
        val tracker = ZoneDisplayTracker()
        val state = ZoneRecordingState()

        tracker.next(state, ZoneTimes(), freshLiveZone = 3, nowElapsedMs = 1_000L)
        tracker.next(state, ZoneTimes(), freshLiveZone = 3, nowElapsedMs = 2_000L)
        tracker.next(state, ZoneTimes(), freshLiveZone = null, nowElapsedMs = 5_000L)
        val reconnectBaseline = tracker.next(
            state,
            ZoneTimes(),
            freshLiveZone = 3,
            nowElapsedMs = 20_000L,
        )
        val afterReconnect = tracker.next(
            state,
            ZoneTimes(),
            freshLiveZone = 3,
            nowElapsedMs = 21_000L,
        )

        assertEquals(ZoneTimes(z3 = 1L, total = 1L), reconnectBaseline.zoneTimes)
        assertEquals(ZoneTimes(z3 = 2L, total = 2L), afterReconnect.zoneTimes)
    }

    @Test
    fun `suspended view never backfills an unobserved live gap`() {
        val tracker = ZoneDisplayTracker()
        val state = ZoneRecordingState()

        tracker.next(state, ZoneTimes(), freshLiveZone = 2, nowElapsedMs = 1_000L)
        val afterUnknownGap = tracker.next(
            state,
            ZoneTimes(),
            freshLiveZone = 3,
            nowElapsedMs = 21_000L,
        )
        val nextObservedSecond = tracker.next(
            state,
            ZoneTimes(),
            freshLiveZone = 3,
            nowElapsedMs = 22_000L,
        )

        assertEquals(ZoneTimes(), afterUnknownGap.zoneTimes)
        assertEquals(ZoneTimes(z3 = 1L, total = 1L), nextObservedSecond.zoneTimes)
    }
}

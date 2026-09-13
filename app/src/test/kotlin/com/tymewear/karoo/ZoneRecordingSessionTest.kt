package com.tymewear.karoo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZoneRecordingSessionTest {

    @Before
    fun resetBefore() {
        TymewearData.clearZoneRecordingSession()
        TymewearData.resetZoneTimes()
    }

    @After
    fun resetAfter() {
        TymewearData.clearZoneRecordingSession()
        TymewearData.resetZoneTimes()
    }

    @Test
    fun `begin publishes a new active session with empty recorded bars`() {
        TymewearData.incrementZoneTime(zone = 5, seconds = 12L)

        val sessionId = TymewearData.beginZoneRecordingSession()

        assertEquals(ZoneTimes(), TymewearData.zoneTimes.value)
        assertEquals(sessionId, TymewearData.zoneRecordingState.value.sessionId)
        assertTrue(TymewearData.zoneRecordingState.value.isRecording)
    }

    @Test
    fun `stale cancellation cannot stop a replacement session`() {
        val oldSessionId = TymewearData.beginZoneRecordingSession()
        val replacementSessionId = TymewearData.beginZoneRecordingSession()

        TymewearData.endZoneRecordingSession(oldSessionId)

        assertEquals(replacementSessionId, TymewearData.zoneRecordingState.value.sessionId)
        assertTrue(TymewearData.zoneRecordingState.value.isRecording)

        TymewearData.endZoneRecordingSession(replacementSessionId)
        assertFalse(TymewearData.zoneRecordingState.value.isRecording)
    }
}

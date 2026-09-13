package com.tymewear.karoo

import android.os.SystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TymewearDataSnapshotTest {

    @Before
    fun resetBefore() {
        TymewearData.setDisconnected()
        TymewearData.resetZoneTimes()
        TymewearData.updateHr(0.0)
    }

    @After
    fun resetAfter() {
        TymewearData.setDisconnected()
        TymewearData.resetZoneTimes()
        TymewearData.updateHr(0.0)
    }

    @Test
    fun `presentation is refreshed atomically between breaths`() {
        val initial = TymewearData.update(sampleBreath())

        assertEquals(24.0, initial.smoothBreathRate, 0.001)
        assertEquals(42.0, initial.smoothMinuteVolume, 0.001)
        assertFalse(initial.miConfigured)
        val recording = requireNotNull(TymewearData.recordingSnapshot.value)
        assertEquals(42.0, recording.smoothMinuteVolume, 0.001)
        assertFalse(recording.miConfigured)
        assertFalse(recording.mobilizationIndexAvailable)

        TymewearData.incrementZoneTime(zone = 3, seconds = 4)
        TymewearData.updateBattery(67)
        TymewearData.updateHr(151.0)

        val refreshed = requireNotNull(TymewearData.presentationSnapshot.value)
        assertEquals(initial.data, refreshed.data)
        assertEquals(4L, refreshed.zoneTimeTotal)
        assertEquals(67, refreshed.batteryPercent)
        assertEquals(151.0, refreshed.heartRate, 0.001)

        TymewearData.setDisconnected()
        assertNull(TymewearData.presentationSnapshot.value)
        assertEquals(151.0, TymewearData.heartRate.value, 0.001)
    }

    @Test
    fun `disconnect retains graph history and reconnect starts a fresh segment`() {
        val start = nextElapsedRealtime()
        TymewearData.updateAtElapsedRealtime(sampleBreath(minuteVolume = 20.0), start)
        TymewearData.updateAtElapsedRealtime(sampleBreath(minuteVolume = 30.0), start + 1_000L)
        val beforeDisconnect = TymewearData.ventilationHistory.value
        val previousSegment = beforeDisconnect.last().segmentId

        TymewearData.setDisconnected()

        assertFalse(TymewearData.isConnected.value)
        assertEquals(beforeDisconnect, TymewearData.ventilationHistory.value)
        TymewearData.updateAtElapsedRealtime(sampleBreath(minuteVolume = 55.0), start + 2_000L)
        val afterReconnect = TymewearData.ventilationHistory.value
        assertEquals(beforeDisconnect.size + 1, afterReconnect.size)
        assertNotEquals(previousSegment, afterReconnect.last().segmentId)
        assertEquals(55.0, TymewearData.ventilationAverages.value.thirtySeconds, 0.0)
    }

    @Test
    fun `breath arriving after freshness expires starts a fresh segment`() {
        val start = nextElapsedRealtime()
        TymewearData.updateAtElapsedRealtime(
            sampleBreath(breathRate = 20.0, minuteVolume = 20.0),
            start,
        )
        val firstSegment = TymewearData.ventilationHistory.value.last().segmentId

        // At 20 brpm, 6,600ms is still fresh and the next millisecond is a gap.
        TymewearData.updateAtElapsedRealtime(
            sampleBreath(breathRate = 20.0, minuteVolume = 50.0),
            start + 6_601L,
        )

        assertNotEquals(firstSegment, TymewearData.ventilationHistory.value.last().segmentId)
        assertEquals(50.0, TymewearData.ventilationAverages.value.thirtySeconds, 0.0)
    }

    private fun nextElapsedRealtime(): Long = maxOf(
        SystemClock.elapsedRealtime(),
        (TymewearData.ventilationHistory.value.lastOrNull()?.timestampMs ?: -1L) + 1L,
    )

    private fun sampleBreath(
        breathRate: Double = 24.0,
        minuteVolume: Double = 42.0,
    ) = Protocol.BreathingData(
        breathRate = breathRate,
        tidalVolume = 1.75,
        minuteVolume = minuteVolume,
        ieRatio = 0.8,
        veZone = 0,
        inhaleDurationCs = 30,
        exhaleDurationCs = 40,
        tvRaw = 175,
        fieldE = 9,
        timestamp40ms = 1_000L,
    )
}

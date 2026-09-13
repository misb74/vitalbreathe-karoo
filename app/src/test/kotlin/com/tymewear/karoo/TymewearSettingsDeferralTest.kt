package com.tymewear.karoo

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TymewearSettingsDeferralTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        TymewearData.clearZoneRecordingSession()
        application.getSharedPreferences(PREFS_NAME, 0).edit().clear().commit()
        TymewearData.loadThresholds(application)
        TymewearData.setDisconnected()
        TymewearData.updateHr(0.0)
    }

    @After
    fun tearDown() {
        TymewearData.clearZoneRecordingSession()
        application.getSharedPreferences(PREFS_NAME, 0).edit().clear().commit()
        TymewearData.loadThresholds(application)
        TymewearData.setDisconnected()
        TymewearData.updateHr(0.0)
    }

    @Test
    fun `zone and MI changes apply only after the active recording ends`() {
        saveSettings(
            thresholds = VentilationThresholds(10.0, 20.0, 30.0, 40.0, 50.0),
            restingBr = 10.0,
            maxBr = 40.0,
            maxHr = 190.0,
            restingHr = 50.0,
        )
        TymewearData.loadThresholds(application)
        TymewearData.update(sampleBreath())
        val sessionId = TymewearData.beginZoneRecordingSession()

        saveSettings(
            thresholds = VentilationThresholds(30.0, 40.0, 50.0, 60.0, 70.0),
            restingBr = 12.0,
            maxBr = 44.0,
            maxHr = 180.0,
            restingHr = 55.0,
        )
        TymewearData.loadThresholds(application)

        assertEquals(VentilationThresholds(10.0, 20.0, 30.0, 40.0, 50.0), TymewearData.thresholds)
        assertEquals(10.0, TymewearData.restingBr, 0.0)
        assertEquals(190.0, TymewearData.maxHr, 0.0)
        assertEquals(3, TymewearData.veZone.value)

        TymewearData.endZoneRecordingSession(sessionId)

        assertEquals(VentilationThresholds(30.0, 40.0, 50.0, 60.0, 70.0), TymewearData.thresholds)
        assertEquals(12.0, TymewearData.restingBr, 0.0)
        assertEquals(180.0, TymewearData.maxHr, 0.0)
        assertEquals(1, TymewearData.veZone.value)
    }

    @Test
    fun `collector replacement keeps deferred settings frozen until its session ends`() {
        val original = VentilationThresholds(10.0, 20.0, 30.0, 40.0, 50.0)
        val replacement = VentilationThresholds(15.0, 25.0, 35.0, 45.0, 55.0)
        saveSettings(original)
        TymewearData.loadThresholds(application)
        val oldSessionId = TymewearData.beginZoneRecordingSession()
        TymewearData.incrementZoneTime(zone = 2, seconds = 37L)

        saveSettings(replacement)
        TymewearData.loadThresholds(application)
        val replacementSessionId = TymewearData.beginZoneRecordingSession()

        assertTrue(TymewearData.zoneRecordingState.value.isRecording)
        assertEquals(original, TymewearData.thresholds)
        assertEquals(ZoneTimes(z2 = 37L, total = 37L), TymewearData.zoneTimes.value)

        TymewearData.endZoneRecordingSession(oldSessionId)
        assertTrue(TymewearData.zoneRecordingState.value.isRecording)
        assertEquals(original, TymewearData.thresholds)
        assertEquals(ZoneTimes(z2 = 37L, total = 37L), TymewearData.zoneTimes.value)

        TymewearData.endZoneRecordingSession(replacementSessionId)
        assertEquals(replacement, TymewearData.thresholds)
    }

    private fun saveSettings(
        thresholds: VentilationThresholds,
        restingBr: Double = 0.0,
        maxBr: Double = 0.0,
        maxHr: Double = 0.0,
        restingHr: Double = 0.0,
    ) {
        application.getSharedPreferences(PREFS_NAME, 0).edit()
            .putFloat("endurance_threshold", thresholds.endurance.toFloat())
            .putFloat("vt1_threshold", thresholds.vt1.toFloat())
            .putFloat("vt2_threshold", thresholds.vt2.toFloat())
            .putFloat("topz4_threshold", thresholds.topZ4.toFloat())
            .putFloat("vo2max_threshold", thresholds.vo2max.toFloat())
            .putFloat("resting_br", restingBr.toFloat())
            .putFloat("max_br", maxBr.toFloat())
            .putFloat("max_hr", maxHr.toFloat())
            .putFloat("resting_hr", restingHr.toFloat())
            .putBoolean(
                "mi_configured",
                restingBr > 0.0 && maxBr > restingBr &&
                    restingHr > 0.0 && maxHr > restingHr,
            )
            .commit()
    }

    private fun sampleBreath() = Protocol.BreathingData(
        breathRate = 20.0,
        tidalVolume = 1.25,
        minuteVolume = 25.0,
        ieRatio = 0.5,
        veZone = 3,
        inhaleDurationCs = 100,
        exhaleDurationCs = 200,
        tvRaw = 125,
        fieldE = 12,
        timestamp40ms = 1_000L,
    )

    private companion object {
        const val PREFS_NAME = "tymewear_prefs"
    }
}

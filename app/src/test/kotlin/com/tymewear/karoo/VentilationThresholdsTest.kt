package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VentilationThresholdsTest {

    private val thresholds = VentilationThresholds(
        endurance = 50.0,
        vt1 = 70.0,
        vt2 = 90.0,
        topZ4 = 110.0,
        vo2max = 130.0,
    )

    @Test
    fun `all five Tymewear values must be configured in order`() {
        assertTrue(thresholds.isConfigured)
        assertFalse(thresholds.copy(endurance = 0.0).isConfigured)
        assertFalse(thresholds.copy(vt2 = 65.0).isConfigured)
        assertFalse(thresholds.copy(vo2max = 100.0).isConfigured)
        assertFalse(thresholds.copy(vt1 = thresholds.endurance).isConfigured)
        assertFalse(thresholds.copy(vt2 = Double.NaN).isConfigured)
        assertFalse(thresholds.copy(vo2max = Double.POSITIVE_INFINITY).isConfigured)
    }

    @Test
    fun `all zero values deliberately disable zones`() {
        val disabled = VentilationThresholds(0.0, 0.0, 0.0, 0.0, 0.0)

        assertTrue(disabled.isDisabled)
        assertFalse(disabled.isConfigured)
        assertEquals(0, disabled.zone(80.0))
    }

    @Test
    fun `boundaries follow current Tymewear five-zone model`() {
        assertEquals(0, thresholds.zone(0.0))
        assertEquals(1, thresholds.zone(49.9))
        assertEquals(2, thresholds.zone(50.0))
        assertEquals(3, thresholds.zone(70.0))
        assertEquals(4, thresholds.zone(90.0))
        assertEquals(5, thresholds.zone(110.0))
        assertEquals(5, thresholds.zone(150.0))
    }

    @Test
    fun `invalid settings disable zone colors`() {
        assertEquals(0, thresholds.copy(endurance = 0.0).zone(80.0))
        assertEquals(0, thresholds.zone(Double.NaN))
        assertEquals(0, thresholds.zone(Double.POSITIVE_INFINITY))
    }
}

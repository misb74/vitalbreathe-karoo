package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Test

class MobilizationCalculatorTest {

    @Test
    fun `matching halfway reserves produce one hundred percent index`() {
        val metrics = MobilizationCalculator.calculate(
            breathRate = 30.0,
            heartRate = 120.0,
            restingBreathRate = 10.0,
            maximumBreathRate = 50.0,
            restingHeartRate = 60.0,
            maximumHeartRate = 180.0,
        )

        assertEquals(50.0, metrics.percentBrr, 0.001)
        assertEquals(50.0, metrics.percentHrr, 0.001)
        assertEquals(100.0, metrics.index, 0.001)
    }

    @Test
    fun `breathing below rest cannot create a negative index`() {
        val metrics = MobilizationCalculator.calculate(
            breathRate = 8.0,
            heartRate = 150.0,
            restingBreathRate = 10.0,
            maximumBreathRate = 50.0,
            restingHeartRate = 60.0,
            maximumHeartRate = 180.0,
        )

        assertEquals(0.0, metrics.percentBrr, 0.0)
        assertEquals(0.0, metrics.index, 0.0)
    }

    @Test
    fun `heart rate reserve below ten percent is idle`() {
        val metrics = MobilizationCalculator.calculate(
            breathRate = 30.0,
            heartRate = 65.0,
            restingBreathRate = 10.0,
            maximumBreathRate = 50.0,
            restingHeartRate = 60.0,
            maximumHeartRate = 180.0,
        )

        assertEquals(0.0, metrics.index, 0.0)
    }

    @Test
    fun `breathing reserve remains available without heart rate`() {
        val metrics = MobilizationCalculator.calculate(
            breathRate = 30.0,
            heartRate = 0.0,
            restingBreathRate = 10.0,
            maximumBreathRate = 50.0,
            restingHeartRate = 60.0,
            maximumHeartRate = 180.0,
        )

        assertEquals(50.0, metrics.percentBrr, 0.001)
        assertEquals(0.0, metrics.percentHrr, 0.0)
        assertEquals(0.0, metrics.index, 0.0)
    }

    @Test
    fun `non finite or invalid inputs produce safe zeros`() {
        val nonFiniteBreathing = MobilizationCalculator.calculate(
            Double.NaN, 120.0, 10.0, 50.0, 60.0, 180.0,
        )
        val invalidRanges = MobilizationCalculator.calculate(
            30.0, 120.0, 50.0, 50.0, 180.0, 60.0,
        )

        assertEquals(MobilizationMetrics(0.0, 0.0, 0.0), nonFiniteBreathing)
        assertEquals(MobilizationMetrics(0.0, 0.0, 0.0), invalidRanges)
    }
}

package com.tymewear.karoo

data class MobilizationMetrics(
    val percentHrr: Double,
    val percentBrr: Double,
    val index: Double,
)

/** Pure, defensive implementation of the experimental BRR/HRR comparison. */
object MobilizationCalculator {
    private val ZERO = MobilizationMetrics(0.0, 0.0, 0.0)

    fun calculate(
        breathRate: Double,
        heartRate: Double,
        restingBreathRate: Double,
        maximumBreathRate: Double,
        restingHeartRate: Double,
        maximumHeartRate: Double,
    ): MobilizationMetrics {
        val breathingValues = listOf(
            breathRate,
            restingBreathRate,
            maximumBreathRate,
        )
        if (breathingValues.any { !it.isFinite() } ||
            breathRate <= 0.0 || maximumBreathRate <= restingBreathRate
        ) {
            return ZERO
        }

        val brr = ((breathRate - restingBreathRate) /
            (maximumBreathRate - restingBreathRate) * 100.0).coerceAtLeast(0.0)
        val validHeartRate = heartRate.isFinite() &&
            restingHeartRate.isFinite() && maximumHeartRate.isFinite() &&
            heartRate > 0.0 && maximumHeartRate > restingHeartRate
        val hrr = if (validHeartRate) {
            ((heartRate - restingHeartRate) /
                (maximumHeartRate - restingHeartRate) * 100.0).coerceAtLeast(0.0)
        } else {
            0.0
        }
        val index = if (hrr >= 10.0) (brr / hrr) * 100.0 else 0.0

        return if (hrr.isFinite() && brr.isFinite() && index.isFinite()) {
            MobilizationMetrics(hrr, brr, index)
        } else {
            ZERO
        }
    }
}

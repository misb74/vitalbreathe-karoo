package com.tymewear.karoo

/** The five personal VE values published by a Tymewear threshold test. */
data class VentilationThresholds(
    val endurance: Double,
    val vt1: Double,
    val vt2: Double,
    val topZ4: Double,
    val vo2max: Double,
) {
    private val values: List<Double>
        get() = listOf(endurance, vt1, vt2, topZ4, vo2max)

    /** All-zero values are the deliberate "zones disabled" state. */
    val isDisabled: Boolean
        get() = values.all { it == 0.0 }

    val isConfigured: Boolean
        get() = values.all { it.isFinite() && it > 0.0 } &&
            endurance < vt1 &&
            vt1 < vt2 &&
            vt2 < topZ4 &&
            topZ4 < vo2max

    /**
     * Tymewear's five-zone model uses Endurance, VT1, VT2 and Top Z4 as
     * transitions. VO2max is the ceiling/graph reference; values above it stay Z5.
     */
    fun zone(minuteVolume: Double): Int {
        if (!isConfigured || !minuteVolume.isFinite() || minuteVolume <= 0.0) return 0
        return when {
            minuteVolume < endurance -> 1
            minuteVolume < vt1 -> 2
            minuteVolume < vt2 -> 3
            minuteVolume < topZ4 -> 4
            else -> 5
        }
    }
}

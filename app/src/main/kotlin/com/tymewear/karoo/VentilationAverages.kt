package com.tymewear.karoo

/** One coherent set of time-based VE values produced from the shared breath history. */
data class VentilationAverages(
    val live: Double = 0.0,
    val fiveSeconds: Double = 0.0,
    val fifteenSeconds: Double = 0.0,
    val thirtySeconds: Double = 0.0,
    val sixtySeconds: Double = 0.0,
) {
    fun valueFor(windowMs: Long): Double = when (windowMs) {
        0L -> live
        5_000L -> fiveSeconds
        15_000L -> fifteenSeconds
        30_000L -> thirtySeconds
        60_000L -> sixtySeconds
        else -> thirtySeconds
    }
}

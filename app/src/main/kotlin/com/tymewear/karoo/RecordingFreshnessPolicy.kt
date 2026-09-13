package com.tymewear.karoo

import kotlin.math.roundToLong

/**
 * Decides how long one completed breath may be repeated into the one-second FIT
 * stream. The allowance follows the measured breathing period and tolerates one
 * missed notification, but it never inherits the full connection watchdog blindly.
 */
object RecordingFreshnessPolicy {
    private const val MIN_HOLD_MS = 5_000L
    private const val MAX_HOLD_MS = 20_000L
    private const val MISSED_NOTIFICATION_ALLOWANCE = 2.2

    fun maxAgeMs(breathRate: Double): Long {
        if (!breathRate.isFinite() || breathRate <= 0.0) return 0L
        val breathPeriodMs = 60_000.0 / breathRate
        return (breathPeriodMs * MISSED_NOTIFICATION_ALLOWANCE)
            .roundToLong()
            .coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
    }

    fun isFresh(
        sampleElapsedMs: Long,
        nowElapsedMs: Long,
        breathRate: Double,
    ): Boolean {
        val maxAge = maxAgeMs(breathRate)
        if (maxAge <= 0L) return false
        return nowElapsedMs - sampleElapsedMs in 0L..maxAge
    }
}

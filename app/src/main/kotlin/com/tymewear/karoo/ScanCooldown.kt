package com.tymewear.karoo

/** Shared monotonic cooldown after Android rejects scans as too frequent. */
internal class ScanCooldown(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private var blockedUntilElapsedMs = 0L

    init {
        require(cooldownMs > 0L)
    }

    @Synchronized
    fun recordFailure(errorCode: Int, nowElapsedMs: Long) {
        if (errorCode != SCAN_FAILED_SCANNING_TOO_FREQUENT) return
        blockedUntilElapsedMs = maxOf(blockedUntilElapsedMs, nowElapsedMs + cooldownMs)
    }

    @Synchronized
    fun remainingDelayMs(nowElapsedMs: Long): Long =
        (blockedUntilElapsedMs - nowElapsedMs).coerceAtLeast(0L)

    @Synchronized
    fun delayBeforeStart(nowElapsedMs: Long, normalDelayMs: Long = 0L): Long =
        maxOf(normalDelayMs, remainingDelayMs(nowElapsedMs))

    companion object {
        const val SCAN_FAILED_SCANNING_TOO_FREQUENT = 6
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}

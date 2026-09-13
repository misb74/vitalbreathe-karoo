package com.tymewear.karoo

/**
 * Keeps FIT record effects behind the metadata effect that registers this
 * extension's developer fields with Karoo.
 */
internal class FitMetadataSettleGate(
    private val snapshotWrittenAtMs: Long,
    private val settleDurationMs: Long = DEFAULT_SETTLE_DURATION_MS,
) {
    init {
        require(settleDurationMs >= 0L) { "settleDurationMs must not be negative" }
    }

    fun isReady(nowMs: Long): Boolean {
        if (nowMs < snapshotWrittenAtMs) return false
        return nowMs - snapshotWrittenAtMs >= settleDurationMs
    }

    companion object {
        const val DEFAULT_SETTLE_DURATION_MS = 2_000L
    }
}

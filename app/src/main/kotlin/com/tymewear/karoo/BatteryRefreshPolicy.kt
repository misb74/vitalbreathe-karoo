package com.tymewear.karoo

internal enum class BatteryRefreshAction {
    QUICK_RETRY,
    REGULAR_POLL,
}

internal data class BatteryFailureSchedule(
    val action: BatteryRefreshAction,
    val delayMs: Long,
    val nextQuickRetriesRemaining: Int,
)

/** Timing policy kept separate from Android callbacks so failure handling is deterministic. */
internal object BatteryRefreshPolicy {
    const val READ_TIMEOUT_MS = 10_000L
    const val QUICK_RETRY_DELAY_MS = 5_000L
    const val POLL_INTERVAL_MS = 10L * 60L * 1_000L
    const val INITIAL_QUICK_RETRIES = 1

    /** Never project a cached level while the physical GATT session is unavailable. */
    fun replayableLevel(ready: Boolean, percent: Int?): Int? =
        percent?.takeIf { ready }

    fun afterFailure(quickRetriesRemaining: Int): BatteryFailureSchedule =
        if (quickRetriesRemaining > 0) {
            BatteryFailureSchedule(
                action = BatteryRefreshAction.QUICK_RETRY,
                delayMs = QUICK_RETRY_DELAY_MS,
                nextQuickRetriesRemaining = quickRetriesRemaining - 1,
            )
        } else {
            BatteryFailureSchedule(
                action = BatteryRefreshAction.REGULAR_POLL,
                delayMs = POLL_INTERVAL_MS,
                nextQuickRetriesRemaining = 0,
            )
        }
}

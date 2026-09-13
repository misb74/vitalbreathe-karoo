package com.tymewear.karoo

enum class ZoneDisplayMode(val label: String) {
    LIVE("LIVE"),
    RECORDING("REC"),
}

data class ZoneDisplayFrame(
    val mode: ZoneDisplayMode,
    val zoneTimes: ZoneTimes,
)

/**
 * Selects the VE Zones source from explicit recording state.
 *
 * The session ID is retained after a session ends. This lets the tracker clear
 * old live bars even if a very short recording starts and ends between view polls.
 */
class ZoneDisplayTracker {
    private var localZoneTimes = ZoneTimes()
    private var observedSessionId = 0L
    private var lastLiveElapsedMs: Long? = null
    private var liveCarryMs = 0L

    fun next(
        recordingState: ZoneRecordingState,
        recordedZoneTimes: ZoneTimes,
        freshLiveZone: Int?,
        nowElapsedMs: Long,
    ): ZoneDisplayFrame {
        if (recordingState.sessionId != observedSessionId) {
            localZoneTimes = ZoneTimes()
            observedSessionId = recordingState.sessionId
            resetLiveClock()
        }

        if (recordingState.isRecording) {
            resetLiveClock()
            return ZoneDisplayFrame(
                mode = ZoneDisplayMode.RECORDING,
                zoneTimes = recordedZoneTimes,
            )
        }

        if (freshLiveZone == null || freshLiveZone !in 1..5) {
            // Break the clock at stale/no-data intervals so reconnect gaps can
            // never be credited when a valid breath eventually returns.
            resetLiveClock()
            return liveFrame()
        }

        val previousElapsedMs = lastLiveElapsedMs
        lastLiveElapsedMs = nowElapsedMs
        if (previousElapsedMs == null || nowElapsedMs <= previousElapsedMs) {
            if (previousElapsedMs != null) liveCarryMs = 0L
            return liveFrame()
        }

        val elapsedMs = nowElapsedMs - previousElapsedMs
        if (elapsedMs > MAX_CONTIGUOUS_LIVE_GAP_MS) {
            // A suspended view cannot know whether the sensor stayed fresh or
            // which zones were crossed while it was not sampling. Start a new
            // baseline instead of assigning the whole unknown gap to one zone.
            liveCarryMs = 0L
            return liveFrame()
        }

        val totalElapsedMs = liveCarryMs + elapsedMs
        val wholeSeconds = totalElapsedMs / 1_000L
        liveCarryMs = totalElapsedMs % 1_000L
        if (wholeSeconds > 0L) {
            localZoneTimes = localZoneTimes.add(freshLiveZone, seconds = wholeSeconds)
        }
        return liveFrame()
    }

    private fun resetLiveClock() {
        lastLiveElapsedMs = null
        liveCarryMs = 0L
    }

    private fun liveFrame(): ZoneDisplayFrame {
        return ZoneDisplayFrame(
            mode = ZoneDisplayMode.LIVE,
            zoneTimes = localZoneTimes,
        )
    }

    private companion object {
        const val MAX_CONTIGUOUS_LIVE_GAP_MS = 2_500L
    }
}

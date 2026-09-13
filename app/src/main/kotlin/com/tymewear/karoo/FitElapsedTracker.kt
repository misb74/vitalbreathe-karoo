package com.tymewear.karoo

data class FitTickResult(
    val shouldEmitRecord: Boolean,
    val zoneSeconds: Long,
)

/**
 * Converts Karoo's elapsed-time millisecond ticks into whole seconds of valid
 * recording time. Missing sensor data and ride pauses deliberately break the
 * interval so neither gap is credited to a ventilation zone.
 */
class FitElapsedTracker {
    private var lastRecordElapsedMs: Long? = null
    private var previousZoneElapsedMs: Long? = null
    private var carryMs = 0L

    fun onRecordingTick(elapsedMs: Long, hasFreshSensorData: Boolean): FitTickResult {
        if (!hasFreshSensorData) {
            breakZoneInterval()
            return FitTickResult(shouldEmitRecord = false, zoneSeconds = 0L)
        }

        val lastRecord = lastRecordElapsedMs
        if (lastRecord != null && elapsedMs <= lastRecord) {
            // Karoo commonly re-emits the unchanged elapsed tick on resume.
            // Use it as the new zone baseline, but do not duplicate the record.
            if (elapsedMs == lastRecord && previousZoneElapsedMs == null) {
                previousZoneElapsedMs = elapsedMs
            }
            return FitTickResult(shouldEmitRecord = false, zoneSeconds = 0L)
        }
        lastRecordElapsedMs = elapsedMs

        val previous = previousZoneElapsedMs
        previousZoneElapsedMs = elapsedMs
        if (previous == null) {
            return FitTickResult(shouldEmitRecord = true, zoneSeconds = 0L)
        }

        val deltaMs = elapsedMs - previous
        if (deltaMs > MAX_CONTIGUOUS_TICK_GAP_MS) {
            // A suspended/conflated collector cannot prove that the sensor stayed
            // fresh throughout this interval. Keep the resumed record, but do not
            // assign the unknown gap to its current ventilation zone.
            carryMs = 0L
            return FitTickResult(shouldEmitRecord = true, zoneSeconds = 0L)
        }

        carryMs += deltaMs
        val seconds = carryMs / 1_000L
        carryMs %= 1_000L
        return FitTickResult(shouldEmitRecord = true, zoneSeconds = seconds)
    }

    /** Break zone integration while retaining record deduplication across pause. */
    fun pause() = breakZoneInterval()

    /** Fully reset for a new ride whose elapsed clock may restart at zero. */
    fun reset() {
        lastRecordElapsedMs = null
        breakZoneInterval()
    }

    private fun breakZoneInterval() {
        previousZoneElapsedMs = null
        carryMs = 0L
    }

    private companion object {
        const val MAX_CONTIGUOUS_TICK_GAP_MS = 2_500L
    }
}

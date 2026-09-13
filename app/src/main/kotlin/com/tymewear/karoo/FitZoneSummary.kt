package com.tymewear.karoo

data class FitZoneSummary(
    val totalSeconds: Long,
    val zoneSeconds: List<Long>,
) {
    fun minutes(zone: Int): Double = zoneSeconds.getOrElse(zone - 1) { 0L } / 60.0

    fun percentage(zone: Int): Double = if (totalSeconds > 0L) {
        zoneSeconds.getOrElse(zone - 1) { 0L } * 100.0 / totalSeconds
    } else {
        0.0
    }

    companion object {
        fun from(times: ZoneTimes): FitZoneSummary? {
            if (times.total <= 0L) return null
            val zones = (1..5).map(times::get)
            // Never serialize an internally inconsistent session summary.
            if (zones.sum() != times.total) return null
            return FitZoneSummary(times.total, zones)
        }
    }
}

/** Tracks 30-second checkpoints and prevents duplicate forced summaries. */
class FitSummaryTracker(private val checkpointSeconds: Long = 30L) {
    private var lastWrittenTotal = -1L

    init {
        require(checkpointSeconds > 0L)
    }

    fun markIfDue(totalSeconds: Long, force: Boolean): Boolean {
        if (totalSeconds <= 0L || totalSeconds == lastWrittenTotal) return false
        if (!force && totalSeconds - lastWrittenTotal.coerceAtLeast(0L) < checkpointSeconds) {
            return false
        }
        lastWrittenTotal = totalSeconds
        return true
    }
}

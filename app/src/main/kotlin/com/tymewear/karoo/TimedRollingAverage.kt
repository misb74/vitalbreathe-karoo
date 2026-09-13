package com.tymewear.karoo

import java.util.ArrayDeque

/** A numeric sample paired with a monotonic timestamp. */
data class TimedSample(
    val timestampMs: Long,
    val value: Double,
    val segmentId: Long = 0L,
)

/**
 * Keeps samples for a real time window. VitalPro reports once per breath, so a
 * sample-count average is not a seconds-based average when breathing rate changes.
 */
class TimedRollingAverage(
    private val retentionMs: Long,
    private val maxCarryMs: Long = 20_000L,
) {
    private val samples = ArrayDeque<TimedSample>()
    private var currentSegmentId = 0L

    init {
        require(retentionMs >= 0L)
        require(maxCarryMs > 0L)
    }

    @Synchronized
    fun add(value: Double, timestampMs: Long) {
        if (!value.isFinite() || timestampMs < 0L) return

        // elapsedRealtime() should not move backwards. Clear defensively if a
        // caller supplies an older timestamp so the series remains ordered.
        val previous = samples.peekLast()
        if (previous?.timestampMs?.let { timestampMs < it } == true) {
            samples.clear()
            advanceSegment()
        } else if (
            previous != null &&
            previous.segmentId == currentSegmentId &&
            timestampMs - previous.timestampMs > maxCarryMs
        ) {
            // A sample arriving after the carry limit is new evidence, not a
            // licence to fill the unobserved interval with its value.
            advanceSegment()
        } else if (
            previous?.let {
                it.timestampMs == timestampMs && it.segmentId == currentSegmentId
            } == true
        ) {
            // Two real breaths cannot finish in the same millisecond. If Android
            // delivers callbacks in one burst, replacing the zero-duration point
            // matches the weighting math and avoids a vertical graph artefact.
            samples.removeLast()
        }
        samples.addLast(TimedSample(timestampMs, value, currentSegmentId))
        prune(timestampMs)
    }

    /**
     * Keep retained history, but ensure the next sample starts a new independent
     * smoothing and graph segment. Repeated marks before another sample are
     * intentionally idempotent.
     */
    @Synchronized
    fun markDiscontinuity() {
        if (samples.peekLast()?.segmentId == currentSegmentId) {
            advanceSegment()
        }
    }

    @Synchronized
    fun average(windowMs: Long, nowMs: Long): Double {
        if (samples.isEmpty()) return 0.0
        prune(nowMs)
        if (samples.isEmpty()) return 0.0
        val currentSamples = samples.toList().takeLastWhile { it.segmentId == currentSegmentId }
        if (currentSamples.isEmpty()) return 0.0
        if (windowMs <= 0L) return currentSamples.last().value
        return timeWeightedAverage(currentSamples, windowMs, nowMs, maxCarryMs)
    }

    @Synchronized
    fun snapshot(nowMs: Long): List<TimedSample> {
        prune(nowMs)
        return samples.toList()
    }

    @Synchronized
    fun clear() {
        samples.clear()
        currentSegmentId = 0L
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - retentionMs
        // Keep one sample at/before the cutoff as an anchor for the interval that
        // crosses into the retained window.
        while (samples.size > 1) {
            val first = samples.removeFirst()
            val next = samples.peekFirst() ?: break
            if (next.timestampMs > cutoff) {
                // Only a point from the same continuous segment can anchor an
                // interval crossing the cutoff. An older disconnected segment
                // must not survive merely to imply a connection to the next one.
                if (first.timestampMs > cutoff || first.segmentId == next.segmentId) {
                    samples.addFirst(first)
                }
                break
            }
        }
        if (samples.size == 1 && samples.peekFirst()?.timestampMs?.let { it < cutoff } == true) {
            samples.clear()
        }
    }

    private fun advanceSegment() {
        currentSegmentId = if (currentSegmentId == Long.MAX_VALUE) 0L else currentSegmentId + 1L
    }

    companion object {
        /** Returns one time-window average for each input sample. */
        fun rollingAverages(data: List<TimedSample>, windowMs: Long): List<TimedSample> {
            if (windowMs <= 0L || data.size < 2) return data
            var segmentStart = 0
            return data.mapIndexed { index, sample ->
                if (index > 0 && sample.segmentId != data[index - 1].segmentId) {
                    segmentStart = index
                }
                TimedSample(
                    sample.timestampMs,
                    timeWeightedAverage(
                        data = data.subList(segmentStart, index + 1),
                        windowMs = windowMs,
                        nowMs = sample.timestampMs,
                        maxCarryMs = Long.MAX_VALUE,
                    ),
                    sample.segmentId,
                )
            }
        }

        /**
         * Integrates each completed breath value over its measured interval.
         * This avoids giving faster breathing periods extra weight merely because
         * they produce more notifications. The latest value is carried forward
         * briefly, then considered stale.
         */
        private fun timeWeightedAverage(
            data: List<TimedSample>,
            windowMs: Long,
            nowMs: Long,
            maxCarryMs: Long,
        ): Double {
            if (data.isEmpty()) return 0.0
            val latest = data.last()
            if (nowMs - latest.timestampMs > maxCarryMs) return 0.0
            if (data.size == 1 || windowMs <= 0L) return latest.value

            val windowStart = nowMs - windowMs
            val observedStart = maxOf(windowStart, data.first().timestampMs)
            if (nowMs <= observedStart) return latest.value

            var weightedTotal = 0.0
            var measuredMs = 0L
            var previousTimestamp = data.first().timestampMs

            for (index in 1 until data.size) {
                val sample = data[index]
                val segmentStart = maxOf(observedStart, previousTimestamp)
                val segmentEnd = minOf(nowMs, sample.timestampMs)
                if (segmentEnd > segmentStart) {
                    val duration = segmentEnd - segmentStart
                    // A breath summary describes the interval that just completed.
                    weightedTotal += sample.value * duration
                    measuredMs += duration
                }
                previousTimestamp = sample.timestampMs
            }

            val tailStart = maxOf(observedStart, latest.timestampMs)
            if (nowMs > tailStart) {
                val duration = nowMs - tailStart
                weightedTotal += latest.value * duration
                measuredMs += duration
            }

            return if (measuredMs > 0L) weightedTotal / measuredMs else latest.value
        }
    }
}

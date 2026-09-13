package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Test

class TimedRollingAverageTest {

    @Test
    fun `average is weighted by elapsed time rather than breath count`() {
        val average = TimedRollingAverage(retentionMs = 60_000L)
        average.add(10.0, 0L)
        average.add(20.0, 10_000L)
        average.add(40.0, 30_000L)

        // The completed 10s interval carries 20 and the completed 20s interval
        // carries 40. An equal-per-breath mean would incorrectly return 23.33.
        assertEquals(33.333, average.average(30_000L, 30_000L), 0.001)
    }

    @Test
    fun `window keeps the sample anchoring its lower boundary`() {
        val average = TimedRollingAverage(retentionMs = 30_000L)
        average.add(10.0, 0L)
        average.add(20.0, 20_000L)
        average.add(40.0, 40_000L)

        assertEquals(33.333, average.average(30_000L, 40_000L), 0.001)
    }

    @Test
    fun `instant mode returns latest value`() {
        val average = TimedRollingAverage(retentionMs = 60_000L)
        average.add(10.0, 0L)
        average.add(25.0, 5_000L)

        assertEquals(25.0, average.average(0L, 5_000L), 0.0)
    }

    @Test
    fun `stale value is not carried forever`() {
        val average = TimedRollingAverage(retentionMs = 60_000L, maxCarryMs = 20_000L)
        average.add(30.0, 0L)

        assertEquals(0.0, average.average(30_000L, 20_001L), 0.0)
    }

    @Test
    fun `historical rolling output retains timestamps`() {
        val data = listOf(
            TimedSample(0L, 10.0),
            TimedSample(10_000L, 20.0),
            TimedSample(30_000L, 40.0),
        )

        val result = TimedRollingAverage.rollingAverages(data, 30_000L)

        assertEquals(data.map { it.timestampMs }, result.map { it.timestampMs })
        assertEquals(33.333, result.last().value, 0.001)
    }

    @Test
    fun `explicit discontinuity preserves history but restarts smoothing`() {
        val average = TimedRollingAverage(retentionMs = 5 * 60_000L)
        average.add(10.0, 0L)
        average.add(20.0, 5_000L)
        val firstSegment = average.snapshot(5_000L).last().segmentId

        average.markDiscontinuity()
        average.markDiscontinuity()
        average.add(50.0, 10_000L)

        val history = average.snapshot(10_000L)
        assertEquals(3, history.size)
        assertEquals(firstSegment, history[1].segmentId)
        assertEquals(firstSegment + 1L, history[2].segmentId)
        assertEquals(50.0, average.average(30_000L, 10_000L), 0.0)
        assertEquals(
            50.0,
            TimedRollingAverage.rollingAverages(history, 30_000L).last().value,
            0.0,
        )
    }

    @Test
    fun `sample beyond carry limit starts a new segment without losing recent history`() {
        val average = TimedRollingAverage(
            retentionMs = 5 * 60_000L,
            maxCarryMs = 20_000L,
        )
        average.add(10.0, 0L)
        average.add(20.0, 10_000L)
        average.add(60.0, 30_001L)

        val history = average.snapshot(30_001L)
        assertEquals(3, history.size)
        assertEquals(history[0].segmentId, history[1].segmentId)
        assertEquals(history[1].segmentId + 1L, history[2].segmentId)
        assertEquals(60.0, average.average(60_000L, 30_001L), 0.0)
    }

    @Test
    fun `equal host timestamps replace a zero duration point`() {
        val average = TimedRollingAverage(retentionMs = 60_000L)
        average.add(10.0, 1_000L)
        average.add(25.0, 1_000L)

        assertEquals(listOf(TimedSample(1_000L, 25.0)), average.snapshot(1_000L))
    }

    @Test
    fun `expired disconnected segment is not retained as a window anchor`() {
        val average = TimedRollingAverage(retentionMs = 30_000L)
        average.add(10.0, 0L)
        average.markDiscontinuity()
        average.add(40.0, 40_000L)

        assertEquals(listOf(TimedSample(40_000L, 40.0, 1L)), average.snapshot(40_000L))
    }
}

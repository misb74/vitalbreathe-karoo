package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryRefreshPolicyTest {

    @Test
    fun `the initial failure gets one quick retry before normal polling`() {
        val retry = BatteryRefreshPolicy.afterFailure(
            BatteryRefreshPolicy.INITIAL_QUICK_RETRIES,
        )

        assertEquals(BatteryRefreshAction.QUICK_RETRY, retry.action)
        assertEquals(5_000L, retry.delayMs)
        assertEquals(0, retry.nextQuickRetriesRemaining)

        val poll = BatteryRefreshPolicy.afterFailure(retry.nextQuickRetriesRemaining)

        assertEquals(BatteryRefreshAction.REGULAR_POLL, poll.action)
        assertEquals(10L * 60L * 1_000L, poll.delayMs)
        assertEquals(0, poll.nextQuickRetriesRemaining)
    }

    @Test
    fun `cached battery is replayed only for a ready physical session`() {
        assertNull(BatteryRefreshPolicy.replayableLevel(ready = false, percent = 72))
        assertNull(BatteryRefreshPolicy.replayableLevel(ready = true, percent = null))
        assertEquals(72, BatteryRefreshPolicy.replayableLevel(ready = true, percent = 72))
    }
}

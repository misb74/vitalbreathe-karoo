package com.tymewear.karoo

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class KarooRetryPolicyTest {

    @Test
    fun `retry delay backs off quickly and caps at thirty seconds`() {
        assertEquals(1_000L, karooRetryDelayMs(0))
        assertEquals(2_000L, karooRetryDelayMs(1))
        assertEquals(4_000L, karooRetryDelayMs(2))
        assertEquals(16_000L, karooRetryDelayMs(4))
        assertEquals(30_000L, karooRetryDelayMs(5))
        assertEquals(30_000L, karooRetryDelayMs(50))
    }

    @Test
    fun `failed stream invalidates stale value until retry reports again`() = runBlocking {
        var subscriptions = 0
        val values = flow {
            subscriptions += 1
            if (subscriptions == 1) {
                emit("recording")
                error("Karoo listener removed")
            }
            emit("paused")
        }
            .retryKarooStreamWithAvailability(
                label = "test",
                delayBeforeRetry = {},
            )
            .take(3)
            .toList()

        assertEquals(
            listOf(
                KarooStreamAvailability.Available("recording"),
                KarooStreamAvailability.Unavailable,
                KarooStreamAvailability.Available("paused"),
            ),
            values,
        )
    }
}

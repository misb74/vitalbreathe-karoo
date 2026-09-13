package com.tymewear.karoo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleForegroundServicePolicyTest {

    @Test
    fun `replacement session refreshes service start during pending old stop`() {
        assertTrue(
            foregroundSessionStartRequired(
                sessionWasAdded = true,
                foregroundRunning = true,
            ),
        )
    }

    @Test
    fun `failed promotion can be retried for an existing session`() {
        assertTrue(
            foregroundSessionStartRequired(
                sessionWasAdded = false,
                foregroundRunning = false,
            ),
        )
    }

    @Test
    fun `duplicate start is suppressed only for the same already-protected session`() {
        assertFalse(
            foregroundSessionStartRequired(
                sessionWasAdded = false,
                foregroundRunning = true,
            ),
        )
    }

    @Test
    fun `breath retries back off monotonically and stop growing at the bound`() {
        val backoff = ForegroundRetryBackoff(
            initialCooldownMs = 1_000L,
            maximumCooldownMs = 4_000L,
        )

        assertTrue(backoff.tryAcquire(0L))
        assertFalse(backoff.tryAcquire(999L))
        assertTrue(backoff.tryAcquire(1_000L))
        assertFalse(backoff.tryAcquire(2_999L))
        assertTrue(backoff.tryAcquire(3_000L))
        assertFalse(backoff.tryAcquire(6_999L))
        assertTrue(backoff.tryAcquire(7_000L))
        assertFalse(backoff.tryAcquire(10_999L))
        assertTrue(backoff.tryAcquire(11_000L))
    }

    @Test
    fun `successful promotion or new session can reset breath retry cooldown`() {
        val backoff = ForegroundRetryBackoff()

        assertTrue(backoff.tryAcquire(5_000L))
        assertFalse(backoff.tryAcquire(5_001L))

        backoff.reset()

        assertTrue(backoff.tryAcquire(5_001L))
    }
}

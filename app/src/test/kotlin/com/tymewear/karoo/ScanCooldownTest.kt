package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanCooldownTest {

    @Test
    fun `ordinary scan failure preserves the normal retry delay`() {
        val cooldown = ScanCooldown(cooldownMs = 30_000L)
        cooldown.recordFailure(errorCode = 3, nowElapsedMs = 1_000L)

        assertEquals(2_000L, cooldown.delayBeforeStart(1_000L, normalDelayMs = 2_000L))
    }

    @Test
    fun `too-frequent failure blocks scans for the full cooldown`() {
        val cooldown = ScanCooldown(cooldownMs = 30_000L)
        cooldown.recordFailure(
            errorCode = ScanCooldown.SCAN_FAILED_SCANNING_TOO_FREQUENT,
            nowElapsedMs = 1_000L,
        )

        assertEquals(30_000L, cooldown.remainingDelayMs(1_000L))
        assertEquals(1L, cooldown.remainingDelayMs(30_999L))
        assertEquals(0L, cooldown.remainingDelayMs(31_000L))
    }

    @Test
    fun `another throttle failure never shortens an existing block`() {
        val cooldown = ScanCooldown(cooldownMs = 30_000L)
        cooldown.recordFailure(ScanCooldown.SCAN_FAILED_SCANNING_TOO_FREQUENT, 10_000L)
        cooldown.recordFailure(ScanCooldown.SCAN_FAILED_SCANNING_TOO_FREQUENT, 5_000L)

        assertEquals(30_000L, cooldown.remainingDelayMs(10_000L))
    }
}

package com.tymewear.karoo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleReconnectPolicyTest {

    @Test
    fun `configured sensor ID rescans and does not fall back to a stale address`() {
        val policy = bleReconnectPolicy("BEEF")

        assertTrue(policy.scanAllAdvertisements)
        assertTrue(policy.rescanBeforeReconnect)
        assertFalse(policy.fallbackToKnownDeviceAfterScanMiss)
        assertTrue(
            policy.matchesCandidate(
                storedAddress = "AA:AA:AA:AA:AA:AA",
                candidateAddress = "BB:BB:BB:BB:BB:BB",
                candidateName = "TYME-BEEF",
            ),
        )
        assertFalse(
            policy.matchesCandidate(
                storedAddress = "AA:AA:AA:AA:AA:AA",
                candidateAddress = "AA:AA:AA:AA:AA:AA",
                candidateName = "TYME-CAFE",
            ),
        )
        assertFalse(
            policy.matchesCandidate(
                storedAddress = "AA:AA:AA:AA:AA:AA",
                candidateAddress = "BB:BB:BB:BB:BB:BB",
                candidateName = "TymeHR BEEF",
            ),
        )
        assertTrue(
            policy.matchesCandidate(
                storedAddress = "AA:AA:AA:AA:AA:AA",
                candidateAddress = "AA:AA:AA:AA:AA:AA",
                candidateName = "VitalPro R",
            ),
        )
        assertFalse(
            policy.matchesCandidate(
                storedAddress = "AA:AA:AA:AA:AA:AA",
                candidateAddress = "BB:BB:BB:BB:BB:BB",
                candidateName = "VitalPro R",
            ),
        )
    }

    @Test
    fun `missing sensor ID preserves direct known-device fallback`() {
        val policy = bleReconnectPolicy(null)

        assertFalse(policy.scanAllAdvertisements)
        assertFalse(policy.rescanBeforeReconnect)
        assertTrue(policy.fallbackToKnownDeviceAfterScanMiss)
        assertTrue(
            policy.matchesCandidate(
                storedAddress = "AA:AA:AA:AA:AA:AA",
                candidateAddress = "aa:aa:aa:aa:aa:aa",
                candidateName = null,
            ),
        )
        assertFalse(
            policy.matchesCandidate(
                storedAddress = "AA:AA:AA:AA:AA:AA",
                candidateAddress = "BB:BB:BB:BB:BB:BB",
                candidateName = "TYME-BEEF",
            ),
        )
    }
}

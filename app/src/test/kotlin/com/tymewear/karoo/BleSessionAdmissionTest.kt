package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSessionAdmissionTest {

    @Test
    fun `same address reuses its shared session case insensitively`() {
        val decision = decideBleSessionAdmission(
            requestedAddress = "aa:bb:cc:dd:ee:ff",
            sessions = listOf(BleSessionSnapshot("AA:BB:CC:DD:EE:FF", true, 1)),
        )

        assertTrue(decision is BleSessionAdmission.Reuse)
    }

    @Test
    fun `different address is rejected while an active owner exists`() {
        val decision = decideBleSessionAdmission(
            requestedAddress = "BB:BB:BB:BB:BB:BB",
            sessions = listOf(BleSessionSnapshot("AA:AA:AA:AA:AA:AA", true, 1)),
        )

        assertEquals(
            BleSessionAdmission.RejectActive("AA:AA:AA:AA:AA:AA"),
            decision,
        )
    }

    @Test
    fun `pending reservation also prevents a second strap`() {
        val decision = decideBleSessionAdmission(
            requestedAddress = "BB:BB:BB:BB:BB:BB",
            sessions = listOf(BleSessionSnapshot("AA:AA:AA:AA:AA:AA", true, 1)),
        )

        assertTrue(decision is BleSessionAdmission.RejectActive)
    }

    @Test
    fun `different address replaces an ownerless grace session`() {
        val decision = decideBleSessionAdmission(
            requestedAddress = "BB:BB:BB:BB:BB:BB",
            sessions = listOf(BleSessionSnapshot("AA:AA:AA:AA:AA:AA", true, 0)),
        )

        assertEquals(
            BleSessionAdmission.ReplaceIdle("AA:AA:AA:AA:AA:AA"),
            decision,
        )
    }

    @Test
    fun `stopped sessions do not block a new connection`() {
        val decision = decideBleSessionAdmission(
            requestedAddress = "BB:BB:BB:BB:BB:BB",
            sessions = listOf(BleSessionSnapshot("AA:AA:AA:AA:AA:AA", false, 0)),
        )

        assertEquals(BleSessionAdmission.Create, decision)
    }
}

package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryReadGateTest {

    @Test
    fun `resolved failure permits a non-overlapping retry`() {
        val gate = BatteryReadGate()

        assertTrue(gate.begin())
        assertEquals(BatteryReadCompletion.CURRENT, gate.completed())
        assertTrue(gate.begin())
    }

    @Test
    fun `rejected request never occupies the GATT queue`() {
        val gate = BatteryReadGate()

        assertTrue(gate.begin())
        assertTrue(gate.rejected())
        assertTrue(gate.begin())
    }

    @Test
    fun `timeout blocks reads until its callback resolves or a fresh GATT resets the gate`() {
        val gate = BatteryReadGate()

        assertTrue(gate.begin())
        assertTrue(gate.timedOut())

        // No newer read can start until the only outstanding callback resolves.
        assertFalse(gate.begin())
        assertFalse(gate.rejected())

        assertEquals(BatteryReadCompletion.LATE_AFTER_TIMEOUT, gate.completed())
        assertTrue(gate.begin())
        assertEquals(BatteryReadCompletion.CURRENT, gate.completed())

        assertTrue(gate.begin())
        assertTrue(gate.timedOut())
        gate.reset()
        assertTrue(gate.begin())
        assertEquals(BatteryReadCompletion.CURRENT, gate.completed())
    }

    @Test
    fun `duplicate callbacks cannot resolve another operation`() {
        val gate = BatteryReadGate()

        assertTrue(gate.begin())
        assertEquals(BatteryReadCompletion.CURRENT, gate.completed())
        assertEquals(BatteryReadCompletion.UNEXPECTED, gate.completed())
    }
}

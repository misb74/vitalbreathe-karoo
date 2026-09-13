package com.tymewear.karoo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FitMetadataSettleGateTest {

    @Test
    fun `opens only after the full metadata settle interval`() {
        val gate = FitMetadataSettleGate(snapshotWrittenAtMs = 10_000L)

        assertFalse(gate.isReady(9_999L))
        assertFalse(gate.isReady(10_000L))
        assertFalse(gate.isReady(11_999L))
        assertTrue(gate.isReady(12_000L))
        assertTrue(gate.isReady(20_000L))
    }

    @Test
    fun `zero duration is immediately ready for isolated runtime tests`() {
        val gate = FitMetadataSettleGate(
            snapshotWrittenAtMs = 10_000L,
            settleDurationMs = 0L,
        )

        assertTrue(gate.isReady(10_000L))
    }

    @Test
    fun `rejects a negative settle interval`() {
        assertThrows(IllegalArgumentException::class.java) {
            FitMetadataSettleGate(snapshotWrittenAtMs = 0L, settleDurationMs = -1L)
        }
    }
}

package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleDiagnosticsTest {

    @Test
    fun `valid breath clears visible reconnect attempt`() {
        BleDiagnostics.failure(
            address = "AA:BB:CC:DD:EE:FF",
            generation = 7L,
            attempt = 3,
            reason = "connection lost",
        )

        BleDiagnostics.breath(
            address = "AA:BB:CC:DD:EE:FF",
            generation = 8L,
            elapsedMs = 12_345L,
        )

        val state = BleDiagnostics.state.value
        assertEquals("Receiving", state.phase)
        assertEquals(0, state.reconnectAttempt)
        assertEquals(8L, state.generation)
        assertEquals(12_345L, state.lastBreathElapsedMs)
        assertNull(state.lastError)
    }
}

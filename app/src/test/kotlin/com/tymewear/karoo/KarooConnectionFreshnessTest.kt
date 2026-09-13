package com.tymewear.karoo

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KarooConnectionFreshnessTest {

    @Test
    fun `connection generation changes only after a real reconnect`() {
        val disconnected = KarooConnectionState()
        val firstConnection = disconnected.transition(connected = true)

        assertEquals(1L, firstConnection.generation)
        assertEquals(firstConnection, firstConnection.transition(connected = true))

        val firstDisconnect = firstConnection.transition(connected = false)
        assertEquals(1L, firstDisconnect.generation)
        assertEquals(firstDisconnect, firstDisconnect.transition(connected = false))

        val secondConnection = firstDisconnect.transition(connected = true)
        assertEquals(2L, secondConnection.generation)
        assertTrue(secondConnection.accepts(2L))
        assertFalse(secondConnection.accepts(1L))
        assertFalse(firstDisconnect.accepts(1L))
    }

    @Test
    fun `disconnect cancels old inputs and reconnect waits for both fresh callback orders`() = runBlocking {
        val connection = MutableStateFlow(KarooConnectionState())
        val generations = Channel<GenerationSources>(Channel.UNLIMITED)
        val outputs = Channel<FreshKarooValues<Int, String>>(Channel.UNLIMITED)
        val collection = launch {
            connection
                .combineFreshWhileConnected { generation ->
                    val sources = GenerationSources(generation)
                    generations.trySend(sources).getOrThrow()
                    sources.elapsedFlow() to sources.rideStateFlow()
                }
                .collect(outputs::send)
        }

        try {
            assertEquals(FreshKarooValues.Unavailable(0L), outputs.receiveWithinTest())

            connection.value = connection.value.transition(connected = true)
            val first = generations.receiveWithinTest()
            assertEquals(1L, first.generation)
            assertEquals(FreshKarooValues.Unavailable(1L), outputs.receiveWithinTest())

            // ELAPSED_TIME first is not enough to produce a FIT input.
            first.elapsed.trySend(1).getOrThrow()
            assertTrue(outputs.tryReceive().isFailure)
            first.rideState.trySend("recording").getOrThrow()
            assertEquals(
                FreshKarooValues.Available(
                    connectionGeneration = 1L,
                    first = 1,
                    second = "recording",
                ),
                outputs.receiveWithinTest(),
            )

            connection.value = connection.value.transition(connected = false)
            assertEquals(FreshKarooValues.Unavailable(1L), outputs.receiveWithinTest())

            // Callbacks from the canceled generation cannot escape after disconnect.
            first.elapsed.trySend(99).getOrThrow()
            first.rideState.trySend("stale-recording").getOrThrow()
            assertTrue(outputs.tryReceive().isFailure)

            connection.value = connection.value.transition(connected = true)
            val second = generations.receiveWithinTest()
            assertEquals(2L, second.generation)
            assertEquals(FreshKarooValues.Unavailable(2L), outputs.receiveWithinTest())

            // On this generation RideState arrives first. It must still wait for
            // fresh ELAPSED_TIME rather than combining with generation one's value.
            second.rideState.trySend("recording").getOrThrow()
            assertTrue(outputs.tryReceive().isFailure)
            second.elapsed.trySend(2).getOrThrow()

            assertEquals(
                FreshKarooValues.Available(
                    connectionGeneration = 2L,
                    first = 2,
                    second = "recording",
                ),
                outputs.receiveWithinTest(),
            )
        } finally {
            collection.cancelAndJoin()
        }
    }

    @Test
    fun `conflated reconnect generation still invalidates elapsed integration`() = runBlocking {
        val connection = MutableStateFlow(
            KarooConnectionState(connected = true, generation = 1L),
        )
        val generations = Channel<GenerationSources>(Channel.UNLIMITED)
        val outputs = Channel<FreshKarooValues<Int, String>>(Channel.UNLIMITED)
        val collection = launch {
            connection
                .combineFreshWhileConnected { generation ->
                    val sources = GenerationSources(generation)
                    generations.trySend(sources).getOrThrow()
                    sources.elapsedFlow() to sources.rideStateFlow()
                }
                .collect(outputs::send)
        }

        try {
            val first = generations.receiveWithinTest()
            assertEquals(FreshKarooValues.Unavailable(1L), outputs.receiveWithinTest())
            first.elapsed.trySend(1).getOrThrow()
            first.rideState.trySend("recording").getOrThrow()
            assertEquals(
                FreshKarooValues.Available(1L, 1, "recording"),
                outputs.receiveWithinTest(),
            )

            // Reproduce StateFlow conflation: generation 2 arrives while still
            // marked connected, so no disconnected state reaches the collector.
            connection.value = KarooConnectionState(connected = true, generation = 2L)
            val second = generations.receiveWithinTest()
            assertEquals(FreshKarooValues.Unavailable(2L), outputs.receiveWithinTest())

            second.rideState.trySend("recording").getOrThrow()
            assertTrue(outputs.tryReceive().isFailure)
            second.elapsed.trySend(10).getOrThrow()
            assertEquals(
                FreshKarooValues.Available(2L, 10, "recording"),
                outputs.receiveWithinTest(),
            )
        } finally {
            collection.cancelAndJoin()
        }
    }

    private data class GenerationSources(
        val generation: Long,
        val elapsed: Channel<Int> = Channel(Channel.UNLIMITED),
        val rideState: Channel<String> = Channel(Channel.UNLIMITED),
    ) {
        fun elapsedFlow() = elapsed.receiveAsFlow()

        fun rideStateFlow() = rideState.receiveAsFlow()
    }

    private suspend fun <T> Channel<T>.receiveWithinTest(): T =
        withTimeout(1_000L) { receive() }
}

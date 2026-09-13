package com.tymewear.karoo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import timber.log.Timber
import kotlin.math.roundToLong

internal fun karooRetryDelayMs(attempt: Long): Long {
    val exponent = attempt.coerceIn(0L, 5L).toInt()
    return (1_000L shl exponent).coerceAtMost(30_000L)
}

/** Re-register a Karoo consumer after the service removes a failed listener. */
fun <T> Flow<T>.retryKarooStream(
    label: String,
    onFailure: () -> Unit = {},
): Flow<T> = retryWhen { cause, attempt ->
    if (cause is CancellationException) return@retryWhen false
    onFailure()
    val delayMs = karooRetryDelayMs(attempt)
    Timber.w(cause, "$label stream ended; registering again in ${delayMs}ms")
    delay(delayMs)
    true
}

internal sealed interface KarooStreamAvailability<out T> {
    data class Available<T>(val value: T) : KarooStreamAvailability<T>
    data object Unavailable : KarooStreamAvailability<Nothing>
}

internal data class KarooConnectionState(
    val connected: Boolean = false,
    val generation: Long = 0L,
) {
    fun accepts(candidateGeneration: Long): Boolean =
        connected && generation == candidateGeneration

    fun transition(connected: Boolean): KarooConnectionState = when {
        connected && !this.connected -> KarooConnectionState(
            connected = true,
            generation = generation + 1L,
        )
        !connected && this.connected -> copy(connected = false)
        else -> this
    }
}

internal sealed interface FreshKarooValues<out A, out B> {
    val connectionGeneration: Long

    data class Available<A, B>(
        override val connectionGeneration: Long,
        val first: A,
        val second: B,
    ) : FreshKarooValues<A, B>

    data class Unavailable(
        override val connectionGeneration: Long,
    ) : FreshKarooValues<Nothing, Nothing>
}

/**
 * Subscribe to both Karoo inputs once per connected service generation. A new
 * generation cannot emit until both replacement consumers have fresh values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <A, B> Flow<KarooConnectionState>.combineFreshWhileConnected(
    streamsForConnection: (generation: Long) -> Pair<Flow<A>, Flow<B>>,
): Flow<FreshKarooValues<A, B>> =
    distinctUntilChanged()
        .flatMapLatest { connection ->
            if (!connection.connected) {
                flowOf(FreshKarooValues.Unavailable(connection.generation))
            } else {
                val (first, second) = streamsForConnection(connection.generation)
                flow<FreshKarooValues<A, B>> {
                    // StateFlow may conflate an extremely fast disconnect and
                    // reconnect. Invalidate elapsed-time integration at every
                    // new connection generation even if the false state was not
                    // observed by the downstream collector.
                    emit(FreshKarooValues.Unavailable(connection.generation))
                    emitAll(
                        first.combine(second) { firstValue, secondValue ->
                            FreshKarooValues.Available(
                                connectionGeneration = connection.generation,
                                first = firstValue,
                                second = secondValue,
                            )
                        },
                    )
                }
            }
        }

/**
 * Retry a Karoo stream while explicitly invalidating its last value during the
 * retry delay. This prevents combine() from continuing with stale system state.
 */
internal fun <T> Flow<T>.retryKarooStreamWithAvailability(
    label: String,
    delayBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): Flow<KarooStreamAvailability<T>> =
    map<T, KarooStreamAvailability<T>> { KarooStreamAvailability.Available(it) }
        .retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            emit(KarooStreamAvailability.Unavailable)
            val delayMs = karooRetryDelayMs(attempt)
            Timber.w(cause, "$label stream ended; registering again in ${delayMs}ms")
            delayBeforeRetry(delayMs)
            true
        }

/**
 * Wraps KarooSystemService.addConsumer for streaming data types as a Flow.
 * OnStreamState extends KarooEvent and has a .state: StreamState property.
 */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val consumerId = addConsumer<OnStreamState>(
        OnStreamState.StartStreaming(dataTypeId),
        onError = { error ->
            Timber.e("streamDataFlow error for $dataTypeId: $error")
            close(IllegalStateException("$dataTypeId stream error: $error"))
        },
        onComplete = {
            close(IllegalStateException("$dataTypeId stream completed unexpectedly"))
        },
        onEvent = { event -> trySend(event.state) },
    )
    awaitClose {
        removeConsumer(consumerId)
    }
}

/**
 * One elapsed-time callback paired with the exact immutable sensor snapshot
 * that was fresh when Karoo delivered it.
 */
internal data class FitElapsedSample(
    val elapsedMs: Long,
    val recordingSnapshot: RecordingSnapshot?,
)

/**
 * Capture FIT inputs at the Karoo callback boundary and retain only the newest
 * callback while the recorder is busy. This prevents queued old elapsed ticks
 * from being combined later with a newer global sensor snapshot.
 */
internal fun KarooSystemService.fitElapsedSnapshotFlow(): Flow<FitElapsedSample> = callbackFlow {
    val consumerId = addConsumer<OnStreamState>(
        OnStreamState.StartStreaming(DataType.Type.ELAPSED_TIME),
        onError = { error ->
            Timber.e("FIT elapsed snapshot stream error: $error")
            close(IllegalStateException("FIT elapsed snapshot stream error: $error"))
        },
        onComplete = {
            close(IllegalStateException("FIT elapsed snapshot stream completed unexpectedly"))
        },
        onEvent = { event ->
            val elapsed = (event.state as? StreamState.Streaming)
                ?.dataPoint
                ?.singleValue
                ?: return@addConsumer
            trySend(
                FitElapsedSample(
                    elapsedMs = elapsed.roundToLong(),
                    recordingSnapshot = TymewearData.freshRecordingSnapshot(),
                ),
            )
        },
    )
    awaitClose {
        removeConsumer(consumerId)
    }
}.buffer(Channel.CONFLATED)

/**
 * Wraps KarooSystemService.addConsumer as a Flow for any KarooEvent type.
 * Used for RideState to get state transition events.
 */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val consumerId = addConsumer<T>(
        onError = { error ->
            Timber.e("consumerFlow error: $error")
            close(IllegalStateException("${T::class.simpleName} stream error: $error"))
        },
        onComplete = {
            close(IllegalStateException("${T::class.simpleName} stream completed unexpectedly"))
        },
        onEvent = { event -> trySend(event) },
    )
    awaitClose {
        removeConsumer(consumerId)
    }
}

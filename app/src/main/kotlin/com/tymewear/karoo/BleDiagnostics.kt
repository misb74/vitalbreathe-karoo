package com.tymewear.karoo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BleDiagnosticState(
    val phase: String = "Idle",
    val address: String? = null,
    val generation: Long = 0L,
    val reconnectAttempt: Int = 0,
    val lastBreathElapsedMs: Long? = null,
    val lastError: String? = null,
)

/** Small in-app diagnostic snapshot for field testing without ADB. */
object BleDiagnostics {
    private val _state = MutableStateFlow(BleDiagnosticState())
    val state: StateFlow<BleDiagnosticState> = _state.asStateFlow()

    fun phase(
        phase: String,
        address: String,
        generation: Long,
        reconnectAttempt: Int,
    ) {
        _state.update {
            it.copy(
                phase = phase,
                address = address,
                generation = generation,
                reconnectAttempt = reconnectAttempt,
                lastError = null,
            )
        }
    }

    fun breath(address: String, generation: Long, elapsedMs: Long) {
        _state.update {
            it.copy(
                phase = "Receiving",
                address = address,
                generation = generation,
                reconnectAttempt = 0,
                lastBreathElapsedMs = elapsedMs,
                lastError = null,
            )
        }
    }

    fun failure(address: String, generation: Long, attempt: Int, reason: String) {
        _state.update {
            it.copy(
                phase = "Reconnecting",
                address = address,
                generation = generation,
                reconnectAttempt = attempt,
                lastError = reason,
            )
        }
    }

    fun stopped(address: String, reason: String) {
        _state.update {
            if (it.address == null || it.address == address) {
                it.copy(phase = "Stopped", lastError = reason)
            } else {
                it
            }
        }
    }
}

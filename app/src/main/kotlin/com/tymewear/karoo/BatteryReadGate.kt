package com.tymewear.karoo

/**
 * Tracks the one optional battery ATT operation allowed on a GATT generation.
 *
 * Android does not attach a caller-owned request ID to characteristic-read callbacks.
 * Once a read times out, starting another read on the same GATT would therefore let a
 * late callback from the first operation complete the second one. Quarantine that
 * operation until its eventual callback safely resolves it; if no callback arrives,
 * the next physical reconnect resets the gate.
 */
internal enum class BatteryReadCompletion {
    CURRENT,
    LATE_AFTER_TIMEOUT,
    UNEXPECTED,
}

internal class BatteryReadGate {
    private enum class State {
        IDLE,
        IN_FLIGHT,
        ABANDONED,
    }

    private var state = State.IDLE

    fun begin(): Boolean {
        if (state != State.IDLE) return false
        state = State.IN_FLIGHT
        return true
    }

    /** A rejected request never entered Android's GATT operation queue. */
    fun rejected(): Boolean {
        if (state != State.IN_FLIGHT) return false
        state = State.IDLE
        return true
    }

    /**
     * A late callback safely releases an abandoned operation because [begin] has
     * prevented any newer read from entering the same GATT queue in the meantime.
     */
    fun completed(): BatteryReadCompletion = when (state) {
        State.IN_FLIGHT -> {
            state = State.IDLE
            BatteryReadCompletion.CURRENT
        }
        State.ABANDONED -> {
            state = State.IDLE
            BatteryReadCompletion.LATE_AFTER_TIMEOUT
        }
        State.IDLE -> BatteryReadCompletion.UNEXPECTED
    }

    /** Do not overlap a retry with an operation whose eventual callback is unidentifiable. */
    fun timedOut(): Boolean {
        if (state != State.IN_FLIGHT) return false
        state = State.ABANDONED
        return true
    }

    fun reset() {
        state = State.IDLE
    }
}

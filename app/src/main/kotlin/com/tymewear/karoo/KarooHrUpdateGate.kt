package com.tymewear.karoo

/**
 * Serializes Karoo heart-rate callbacks with extension shutdown. Once closed,
 * no callback can restore an old heart rate after the final clear.
 */
internal class KarooHrUpdateGate(
    private val applyHeartRate: (Double) -> Unit,
) {
    private val lock = Any()
    private var acceptingUpdates = true

    fun updateIfActive(heartRate: Double): Boolean = synchronized(lock) {
        if (!acceptingUpdates) return@synchronized false
        applyHeartRate(heartRate)
        true
    }

    fun closeAndClear() = synchronized(lock) {
        acceptingUpdates = false
        applyHeartRate(0.0)
    }
}

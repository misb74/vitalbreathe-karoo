package com.tymewear.karoo

/**
 * Reconnect behavior selected from an already-normalized optional sensor ID.
 * A configured ID is stable across BLE address rotation, while an address is not.
 */
data class BleReconnectPolicy(
    val normalizedSensorId: String?,
    val scanAllAdvertisements: Boolean,
    val rescanBeforeReconnect: Boolean,
    val fallbackToKnownDeviceAfterScanMiss: Boolean,
) {
    /** Match one advertisement without depending on Android BLE classes. */
    fun matchesCandidate(
        storedAddress: String,
        candidateAddress: String,
        candidateName: String?,
    ): Boolean = if (normalizedSensorId == null) {
        candidateAddress.equals(storedAddress, ignoreCase = true)
    } else {
        val advertisedId = Protocol.advertisedSensorId(candidateName)
        Protocol.isVitalProDevice(candidateName) && when {
            advertisedId != null -> advertisedId == normalizedSensorId
            else -> candidateAddress.equals(storedAddress, ignoreCase = true)
        }
    }
}

fun bleReconnectPolicy(normalizedSensorId: String?): BleReconnectPolicy =
    if (normalizedSensorId == null) {
        BleReconnectPolicy(
            normalizedSensorId = null,
            scanAllAdvertisements = false,
            rescanBeforeReconnect = false,
            fallbackToKnownDeviceAfterScanMiss = true,
        )
    } else {
        BleReconnectPolicy(
            normalizedSensorId = normalizedSensorId,
            scanAllAdvertisements = true,
            rescanBeforeReconnect = true,
            fallbackToKnownDeviceAfterScanMiss = false,
        )
    }

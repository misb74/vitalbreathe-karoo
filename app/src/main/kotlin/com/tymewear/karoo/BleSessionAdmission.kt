package com.tymewear.karoo

import java.util.Locale

internal data class BleSessionSnapshot(
    val address: String,
    val accepting: Boolean,
    val ownerCount: Int,
)

internal sealed interface BleSessionAdmission {
    data object Create : BleSessionAdmission
    data class Reuse(val address: String) : BleSessionAdmission
    data class ReplaceIdle(val oldAddress: String) : BleSessionAdmission
    data class RejectActive(val activeAddress: String) : BleSessionAdmission
}

internal fun normalizeBleAddress(address: String): String = address.trim().uppercase(Locale.ROOT)

/** Pure one-strap admission policy used while the manager's session lock is held. */
internal fun decideBleSessionAdmission(
    requestedAddress: String,
    sessions: List<BleSessionSnapshot>,
): BleSessionAdmission {
    val requested = normalizeBleAddress(requestedAddress)
    sessions.firstOrNull {
        it.accepting && normalizeBleAddress(it.address) == requested
    }?.let { return BleSessionAdmission.Reuse(it.address) }

    sessions.firstOrNull { it.accepting && it.ownerCount > 0 }
        ?.let { return BleSessionAdmission.RejectActive(it.address) }

    sessions.firstOrNull { it.accepting }
        ?.let { return BleSessionAdmission.ReplaceIdle(it.address) }

    return BleSessionAdmission.Create
}

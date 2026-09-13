package com.tymewear.karoo

/**
 * Thread-safe ownership for subscribers that are first reserved by a Binder/coroutine
 * thread and then activated on the BLE session's main-thread handler.
 *
 * A reservation counts as an owner immediately. This prevents an already-due grace
 * callback from stopping the session while the activation runnable is still queued.
 */
internal class SubscriberOwnership<T> {

    class GraceTicket internal constructor(internal val epoch: Long)

    data class ReleaseResult<T>(
        val removed: T?,
        val wasPayloadOwner: Boolean,
        val promoted: T?,
        val graceTicket: GraceTicket?,
    )

    private var accepting = true
    private var epoch = 0L
    private val pending = linkedMapOf<Long, T>()
    private val active = linkedMapOf<Long, T>()

    @Synchronized
    fun isAccepting(): Boolean = accepting

    @Synchronized
    fun ownerCount(): Int = pending.size + active.size

    /** Reserve synchronously before the caller releases the manager session lock. */
    @Synchronized
    fun reserve(id: Long, subscriber: T): Boolean {
        if (!accepting || pending.containsKey(id) || active.containsKey(id)) return false
        epoch += 1L
        pending[id] = subscriber
        return true
    }

    /** Activate on the BLE session handler. Returns null if cancelled or stopped first. */
    @Synchronized
    fun activate(id: Long): T? {
        if (!accepting) return null
        val subscriber = pending.remove(id) ?: return null
        active[id] = subscriber
        return subscriber
    }

    /**
     * Release is safe before or after activation. The returned grace ticket is valid
     * only while no later reservation/release changes the ownership epoch.
     */
    @Synchronized
    fun release(id: Long): ReleaseResult<T> {
        val wasPayloadOwner = active.keys.lastOrNull() == id
        val removed = pending.remove(id) ?: active.remove(id)
        if (removed == null) {
            return ReleaseResult(null, false, null, null)
        }

        epoch += 1L
        return ReleaseResult(
            removed = removed,
            wasPayloadOwner = wasPayloadOwner,
            promoted = if (wasPayloadOwner) active.values.lastOrNull() else null,
            graceTicket = if (pending.isEmpty() && active.isEmpty() && accepting) {
                GraceTicket(epoch)
            } else {
                null
            },
        )
    }

    @Synchronized
    fun shouldExpire(ticket: GraceTicket): Boolean =
        accepting && ticket.epoch == epoch && pending.isEmpty() && active.isEmpty()

    @Synchronized
    fun latestActive(): T? = active.values.lastOrNull()

    @Synchronized
    fun activeSnapshot(): List<T> = active.values.toList()

    /** Stop admission immediately and return both queued and active subscribers once. */
    @Synchronized
    fun stopAndDrain(): List<T>? {
        if (!accepting) return null
        accepting = false
        epoch += 1L
        return buildList(pending.size + active.size) {
            addAll(pending.values)
            addAll(active.values)
        }.also {
            pending.clear()
            active.clear()
        }
    }
}

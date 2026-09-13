package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriberOwnershipTest {

    @Test
    fun `pending reservation invalidates an already-issued grace ticket`() {
        val ownership = SubscriberOwnership<String>()
        assertTrue(ownership.reserve(1L, "old"))
        assertEquals("old", ownership.activate(1L))
        val oldGrace = requireNotNull(ownership.release(1L).graceTicket)
        assertTrue(ownership.shouldExpire(oldGrace))

        assertTrue(ownership.reserve(2L, "replacement"))

        assertFalse(ownership.shouldExpire(oldGrace))
        assertEquals(1, ownership.ownerCount())
        assertEquals("replacement", ownership.activate(2L))
    }

    @Test
    fun `cancelling before activation removes the reservation`() {
        val ownership = SubscriberOwnership<String>()
        assertTrue(ownership.reserve(1L, "pending"))

        val release = ownership.release(1L)

        assertEquals("pending", release.removed)
        assertFalse(release.wasPayloadOwner)
        assertTrue(ownership.shouldExpire(requireNotNull(release.graceTicket)))
        assertNull(ownership.activate(1L))
    }

    @Test
    fun `shutdown drains pending and active subscribers exactly once`() {
        val ownership = SubscriberOwnership<String>()
        assertTrue(ownership.reserve(1L, "active"))
        assertEquals("active", ownership.activate(1L))
        assertTrue(ownership.reserve(2L, "pending"))

        assertEquals(listOf("pending", "active"), ownership.stopAndDrain())
        assertNull(ownership.stopAndDrain())
        assertEquals(0, ownership.ownerCount())
        assertFalse(ownership.reserve(3L, "late"))
        assertNull(ownership.activate(2L))
    }

    @Test
    fun `releasing newest active subscriber promotes the preceding owner`() {
        val ownership = SubscriberOwnership<String>()
        assertTrue(ownership.reserve(1L, "first"))
        assertEquals("first", ownership.activate(1L))
        assertTrue(ownership.reserve(2L, "second"))
        assertEquals("second", ownership.activate(2L))

        val release = ownership.release(2L)

        assertTrue(release.wasPayloadOwner)
        assertEquals("first", release.promoted)
        assertEquals("first", ownership.latestActive())
        assertNull(release.graceTicket)
    }
}

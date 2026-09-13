package com.tymewear.karoo

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KarooHrUpdateGateTest {

    @Test
    fun `shutdown clears an in-flight update and rejects later callbacks`() {
        val appliedValues = CopyOnWriteArrayList<Double>()
        val updateEntered = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val shutdownFinished = CountDownLatch(1)
        val gate = KarooHrUpdateGate { heartRate ->
            if (heartRate == 151.0) {
                updateEntered.countDown()
                assertTrue(releaseUpdate.await(2, TimeUnit.SECONDS))
            }
            appliedValues += heartRate
        }

        val updateThread = Thread { gate.updateIfActive(151.0) }
        updateThread.start()
        assertTrue(updateEntered.await(2, TimeUnit.SECONDS))

        val shutdownThread = Thread {
            gate.closeAndClear()
            shutdownFinished.countDown()
        }
        shutdownThread.start()
        assertFalse(shutdownFinished.await(100, TimeUnit.MILLISECONDS))

        releaseUpdate.countDown()
        updateThread.join(2_000L)
        shutdownThread.join(2_000L)

        assertFalse(updateThread.isAlive)
        assertFalse(shutdownThread.isAlive)
        assertEquals(listOf(151.0, 0.0), appliedValues)
        assertFalse(gate.updateIfActive(166.0))
        assertEquals(listOf(151.0, 0.0), appliedValues)
    }
}

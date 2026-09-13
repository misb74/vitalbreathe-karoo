package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Test

class VitalBreatheLogTreeTest {
    @Test
    fun `operational logging uses one app specific tag`() {
        assertEquals("VitalBreathe", VITAL_BREATHE_LOG_TAG)
    }

    @Test
    fun `release log redaction removes strap addresses and sensor IDs`() {
        assertEquals(
            "BLE[redacted-device] found TYME-[redacted] (sensorId=[redacted])",
            redactOperationalLog(
                "BLE[AA:BB:CC:12:34:56] found TYME-BEEF (sensorId=BEEF)",
            ),
        )
        assertEquals(
            "Sensor ID: [redacted]",
            redactOperationalLog("Sensor ID: CAFE"),
        )
    }

    @Test
    fun `release log redaction leaves useful state and timing intact`() {
        val message = "gen=2 READY after 1657ms; first valid breath after 7223ms"
        assertEquals(message, redactOperationalLog(message))
    }
}

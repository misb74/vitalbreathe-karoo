package com.tymewear.karoo

import io.hammerhead.karooext.models.BatteryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class VitalProBatteryStatusTest {

    @Test
    fun `uses Hammerhead battery boundaries`() {
        val expected = mapOf(
            -1 to BatteryStatus.INVALID,
            0 to BatteryStatus.INVALID,
            1 to BatteryStatus.CRITICAL,
            15 to BatteryStatus.CRITICAL,
            16 to BatteryStatus.LOW,
            45 to BatteryStatus.LOW,
            46 to BatteryStatus.OK,
            80 to BatteryStatus.OK,
            81 to BatteryStatus.GOOD,
            95 to BatteryStatus.GOOD,
            96 to BatteryStatus.NEW,
            100 to BatteryStatus.NEW,
        )

        expected.forEach { (percent, status) ->
            assertEquals("battery at $percent%", status, vitalProBatteryStatus(percent))
        }
    }
}

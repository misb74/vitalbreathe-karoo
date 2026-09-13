package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Test

class VentilationAveragesTest {

    @Test
    fun `field selection returns the matching displayed VE window`() {
        val averages = VentilationAverages(
            live = 1.0,
            fiveSeconds = 5.0,
            fifteenSeconds = 15.0,
            thirtySeconds = 30.0,
            sixtySeconds = 60.0,
        )

        assertEquals(1.0, averages.valueFor(0L), 0.0)
        assertEquals(5.0, averages.valueFor(5_000L), 0.0)
        assertEquals(15.0, averages.valueFor(15_000L), 0.0)
        assertEquals(30.0, averages.valueFor(30_000L), 0.0)
        assertEquals(60.0, averages.valueFor(60_000L), 0.0)
        assertEquals(30.0, averages.valueFor(12_345L), 0.0)
    }
}

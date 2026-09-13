package com.tymewear.karoo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtocolTest {

    @Test
    fun `pairing keeps generic VitalPro names but rejects a visible wrong sensor ID`() {
        assertEquals("BEEF", Protocol.advertisedSensorId("TYME-beef"))
        assertNull(Protocol.advertisedSensorId("VitalPro R"))
        assertTrue(Protocol.isPairingCandidate("TYME-BEEF", "BEEF"))
        assertFalse(Protocol.isPairingCandidate("TYME-CAFE", "BEEF"))
        assertTrue(Protocol.isPairingCandidate("VitalPro R", "BEEF"))
        assertFalse(Protocol.isPairingCandidate("TymeHR BEEF", "BEEF"))
    }

    private lateinit var parser: Protocol.BreathParser

    @Before
    fun setUp() {
        parser = Protocol.BreathParser()
    }

    @Test
    fun `first breath warms timestamp and is not published`() {
        assertNull(parser.parse(packet(timestamp = 1_000L)))
        val parsed = parser.parse(packet(timestamp = 1_075L))

        requireNotNull(parsed)
        assertEquals(20.0, parsed.breathRate, 0.001)
        assertEquals(0.8, parsed.tidalVolume, 0.001)
        assertEquals(16.0, parsed.minuteVolume, 0.001)
    }

    @Test
    fun `missed notification does not halve breathing rate`() {
        assertNull(parser.parse(packet(timestamp = 1_000L)))
        assertEquals(20.0, parser.parse(packet(timestamp = 1_075L))!!.breathRate, 0.001)
        assertEquals(20.0, parser.parse(packet(timestamp = 1_225L))!!.breathRate, 0.001)
    }

    @Test
    fun `repeated long intervals become a genuine slower breathing rate`() {
        assertNull(parser.parse(packet(timestamp = 1_000L)))
        assertEquals(20.0, parser.parse(packet(timestamp = 1_075L))!!.breathRate, 0.001)

        // The first long interval is conservatively treated as one missed packet.
        assertEquals(20.0, parser.parse(packet(timestamp = 1_225L))!!.breathRate, 0.001)
        // A second long interval establishes the new 10 brpm baseline.
        assertEquals(10.0, parser.parse(packet(timestamp = 1_375L))!!.breathRate, 0.001)
    }

    @Test
    fun `uint32 timestamp wrap is handled`() {
        assertNull(parser.parse(packet(timestamp = 0xFFFF_FFF0L)))
        val parsed = parser.parse(packet(timestamp = 59L))

        assertEquals(20.0, parsed!!.breathRate, 0.001)
    }

    @Test
    fun `inconsistent duplicate words are rejected`() {
        assertNull(parser.parse(packet(timestamp = 1_000L, repeatedTvRaw = 81)))
    }

    @Test
    fun `out of range tidal volume is rejected`() {
        assertNull(parser.parse(packet(timestamp = 1_000L, tvRaw = 600, repeatedTvRaw = 600)))
        assertNull(parser.parse(packet(timestamp = 1_075L, tvRaw = 600, repeatedTvRaw = 600)))
    }

    @Test
    fun `device-name matching excludes heart-rate pod`() {
        assertTrue(Protocol.isVitalProDevice("TYME-BEEF"))
        assertTrue(Protocol.isVitalProDevice("VitalPro R"))
        assertTrue(Protocol.isVitalProDevice("vitalpro-hw8-small"))
        assertFalse(Protocol.isVitalProDevice("TymeHR 1234567"))
        assertFalse(Protocol.isVitalProDevice("Power Meter"))
        assertTrue(Protocol.matchesSensorId("TYME-BEEF", "beef"))
        assertEquals("BEEF", Protocol.normalizeSensorId(" beef "))
        assertNull(Protocol.normalizeSensorId("2G7B"))
    }

    @Test
    fun `VitalBreathe FIT fields use Hammerhead sequential definition numbers`() {
        val fields = listOf(
            Protocol.FIT_FIELD_BREATH_RATE,
            Protocol.FIT_FIELD_TIDAL_VOLUME,
            Protocol.FIT_FIELD_MINUTE_VOLUME,
            Protocol.FIT_FIELD_IE_RATIO,
            Protocol.FIT_FIELD_MOBILIZATION_INDEX,
            Protocol.FIT_FIELD_PERCENT_BRR,
            Protocol.FIT_FIELD_VE_ZONE,
            Protocol.FIT_FIELD_VE_ZONE1_TIME,
            Protocol.FIT_FIELD_VE_ZONE1_PCT,
            Protocol.FIT_FIELD_VE_ZONE2_TIME,
            Protocol.FIT_FIELD_VE_ZONE2_PCT,
            Protocol.FIT_FIELD_VE_ZONE3_TIME,
            Protocol.FIT_FIELD_VE_ZONE3_PCT,
            Protocol.FIT_FIELD_VE_ZONE4_TIME,
            Protocol.FIT_FIELD_VE_ZONE4_PCT,
            Protocol.FIT_FIELD_VE_ZONE5_TIME,
            Protocol.FIT_FIELD_VE_ZONE5_PCT,
            Protocol.FIT_FIELD_VE_30S,
            Protocol.FIT_FIELD_VE_ENDURANCE,
            Protocol.FIT_FIELD_VE_VT1,
            Protocol.FIT_FIELD_VE_VT2,
            Protocol.FIT_FIELD_VE_TOP_Z4,
            Protocol.FIT_FIELD_VE_VO2MAX,
        )

        assertEquals((0..22).toList(), fields.map { it.fieldDefinitionNumber.toInt() })
        assertEquals(fields.size, fields.map { it.fieldName }.toSet().size)
    }

    @Test
    fun `connection parsers are isolated and reset requires a new warmup`() {
        val first = Protocol.BreathParser()
        val second = Protocol.BreathParser()

        assertNull(first.parse(packet(timestamp = 1_000L)))
        assertNull(second.parse(packet(timestamp = 5_000L)))
        assertEquals(20.0, first.parse(packet(timestamp = 1_075L))!!.breathRate, 0.001)
        assertEquals(20.0, second.parse(packet(timestamp = 5_075L))!!.breathRate, 0.001)

        first.reset()
        assertNull(first.parse(packet(timestamp = 1_150L)))
        assertEquals(20.0, first.parse(packet(timestamp = 1_225L))!!.breathRate, 0.001)
    }

    @Test
    fun `parser recovers predictably after rejected packets`() {
        assertNull(parser.parse(packet(timestamp = 1_000L)))

        assertNull(parser.parse(packet(timestamp = 1_075L, repeatedTvRaw = 81)))
        assertEquals(20.0, parser.parse(packet(timestamp = 1_075L))!!.breathRate, 0.001)

        assertNull(parser.parse(packet(timestamp = 1_150L, tvRaw = 600, repeatedTvRaw = 600)))
        assertEquals(20.0, parser.parse(packet(timestamp = 1_225L))!!.breathRate, 0.001)

        val imu = packet(timestamp = 1_300L).also { it[0] = Protocol.PKT_IMU.toByte() }
        assertNull(parser.parse(imu))
        assertNull(parser.parse(byteArrayOf(Protocol.PKT_BREATH.toByte())))
        assertEquals(20.0, parser.parse(packet(timestamp = 1_300L))!!.breathRate, 0.001)
    }

    @Test
    fun `unrepairable timestamp gap is rejected once and then recovers`() {
        assertNull(parser.parse(packet(timestamp = 1_000L)))
        assertEquals(20.0, parser.parse(packet(timestamp = 1_075L))!!.breathRate, 0.001)

        assertNull(parser.parse(packet(timestamp = 1_979L)))
        assertEquals(20.0, parser.parse(packet(timestamp = 2_054L))!!.breathRate, 0.001)
    }

    private fun packet(
        timestamp: Long,
        inhale: Int = 100,
        exhale: Int = 200,
        tvRaw: Int = 80,
        repeatedTvRaw: Int = tvRaw,
        fieldE: Int = 12,
        repeatedFieldE: Int = fieldE,
    ): ByteArray = ByteBuffer.allocate(17)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(Protocol.PKT_BREATH.toByte())
        .putInt(timestamp.toInt())
        .putShort(inhale.toShort())
        .putShort(exhale.toShort())
        .putShort(tvRaw.toShort())
        .putShort(repeatedTvRaw.toShort())
        .putShort(fieldE.toShort())
        .putShort(repeatedFieldE.toShort())
        .array()
}

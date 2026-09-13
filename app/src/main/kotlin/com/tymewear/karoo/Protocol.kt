package com.tymewear.karoo

import io.hammerhead.karooext.models.DeveloperField
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * VitalPro BLE protocol constants and data parsing.
 *
 * The minimum interoperability surface is inherited from K-Breathe and
 * validated against notifications from owned VitalPro hardware. Commands and
 * unrelated services remain intentionally unsupported.
 */
object Protocol {

    // -------------------------------------------------------------------------
    // BLE service and characteristic UUIDs used for live interoperability
    // -------------------------------------------------------------------------

    // VitalPro breathing strap custom service
    val VITALPRO_SERVICE_UUID: UUID =
        UUID.fromString("40B50000-30B5-11E5-A151-FEFF819CDC90")

    // Breathing data notification characteristic
    // Context: primary data stream from strap
    val BREATHING_DATA_CHAR_UUID: UUID =
        UUID.fromString("40B50001-30B5-11E5-A151-FEFF819CDC90")

    // Primary observed data channel. Sends three packet types:
    //   0x01 = per-breath summary, 0x02 = IMU data, 0x06 = ADC peaks.
    val COMMAND_CHAR_UUID: UUID =
        UUID.fromString("40B50004-30B5-11E5-A151-FEFF819CDC90")

    // Optional four-character sensor identity shown on the device.
    val SENSOR_ID_CHAR_UUID: UUID =
        UUID.fromString("40B50007-30B5-11E5-A151-FEFF819CDC90")

    // Secondary service (purpose unclear — possibly firmware update related)
    val SECONDARY_SERVICE_UUID: UUID =
        UUID.fromString("4610c40a-c4ff-410d-b5db-abdd19f704a7")

    // Standard Client Characteristic Configuration Descriptor
    val CCCD_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Standard Battery Service
    val BATTERY_SERVICE_UUID: UUID =
        UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_CHAR_UUID: UUID =
        UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // -------------------------------------------------------------------------
    // Device identification
    // -------------------------------------------------------------------------

    // Device names observed: "VitalPro R", "VitalPro BR", "VitalPro S",
    // "vitalpro-hw8-small", "vitalpro-hw8-regular", "TYME-XXXX"
    private val DEVICE_NAME_PATTERNS = listOf("vitalpro", "tymewear")
    private const val HR_SENSOR_PREFIX = "tymehr"
    private val TYME_STRAP_REGEX = Regex("^tyme-([0-9a-f]{4})$")
    private val SENSOR_ID_REGEX = Regex("^[0-9A-F]{4}$")

    /**
     * Check if a BLE device name matches the VitalPro breathing sensor.
     * Matches breathing straps (VitalPro, Tymewear, TYME-XXXX) but
     * excludes HR sensors (TymeHR XXXXXXX).
     */
    fun isVitalProDevice(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val lower = deviceName.lowercase().trim()
        if (lower.startsWith(HR_SENSOR_PREFIX)) return false
        if (DEVICE_NAME_PATTERNS.any { lower.contains(it) }) return true
        if (TYME_STRAP_REGEX.matches(lower)) return true
        // Debug builds log near-misses to catch firmware name changes early.
        // Release scans may see the same nearby advertiser many times.
        if (BuildConfig.DEBUG && (lower.contains("tyme") || lower.contains("vital"))) {
            Timber.w("Potential VitalPro device not matched by name patterns: '$deviceName'")
        }
        return false
    }

    /**
     * Check if a device name matches a specific sensor ID (4-digit code).
     */
    fun matchesSensorId(deviceName: String?, sensorId: String): Boolean {
        val normalized = normalizeSensorId(sensorId) ?: return false
        return deviceName?.contains(normalized, ignoreCase = true) == true
    }

    /** Four-character identity exposed directly by a `TYME-XXXX` advertisement. */
    fun advertisedSensorId(deviceName: String?): String? = deviceName
        ?.trim()
        ?.lowercase()
        ?.let(TYME_STRAP_REGEX::matchEntire)
        ?.groupValues
        ?.getOrNull(1)
        ?.uppercase()

    /**
     * Pairing may show a generic VitalPro name because some supported firmware
     * does not include the four-character code in its advertisement. A visible
     * but different TYME code is still rejected.
     */
    fun isPairingCandidate(deviceName: String?, configuredSensorId: String?): Boolean {
        if (!isVitalProDevice(deviceName)) return false
        val configured = normalizeSensorId(configuredSensorId) ?: return true
        val advertised = advertisedSensorId(deviceName)
        return advertised == null || advertised == configured
    }

    fun normalizeSensorId(sensorId: String?): String? = sensorId
        ?.trim()
        ?.uppercase()
        ?.takeIf(SENSOR_ID_REGEX::matches)

    // -------------------------------------------------------------------------
    // Data parsing — validated against live BLE capture from TYME-XXXX strap
    // -------------------------------------------------------------------------

    // ALL breathing data arrives on characteristic 40B50004 (not 40B50001).
    // Three packet types identified by the first byte:
    //   0x01 (17 bytes): Per-breath summary — arrives every 2-4 seconds
    //   0x02 (13 bytes): IMU/accelerometer data — arrives every ~1 second
    //   0x06 (17 bytes): Raw ADC peak data — paired with type 0x01
    //
    // Type 0x06 contains two ADC readings (peaks) whose difference equals
    // the tidal volume field (C) in the paired type 0x01 packet. Confirmed
    // by L - I = C for every captured sample.

    /** Packet type identifiers */
    const val PKT_BREATH = 0x01
    const val PKT_IMU = 0x02
    const val PKT_ADC_PEAKS = 0x06

    /**
     * Parsed breathing data from a type 0x01 BLE notification.
     */
    data class BreathingData(
        val breathRate: Double,      // breaths per minute (from inter-packet timestamp delta)
        val tidalVolume: Double,     // tidal volume (tv_raw × calibration factor)
        val minuteVolume: Double,    // minute ventilation VE = BR × TV
        val ieRatio: Double,         // inhale/exhale ratio (dimensionless)
        val veZone: Int,             // ventilation zone (0=none, 1-5)
        val inhaleDurationCs: Int,   // inhale duration in raw packet units
        val exhaleDurationCs: Int,   // exhale duration in raw packet units
        val tvRaw: Int,              // raw tidal volume (ADC delta)
        val fieldE: Int,             // unknown field E (for debugging/calibration)
        val timestamp40ms: Long,     // packet timestamp in 40ms ticks
    )

    /**
     * TV calibration factor: converts raw ADC delta to approximate volume units.
     * Validated by cross-referencing Karoo FIT output against Tymewear FIT files
     * (9886 records across two rides): VE = BR × tv_raw × ~0.01, consistently.
     * tv_raw values are confirmed identical between apps (ratio 1.002).
     */
    private const val TV_CALIBRATION = 0.01

    /** Seconds per timestamp tick (packet timestamp field). */
    private const val TICK_SECONDS = 0.040

    /** Minimum plausible BR — below this the timestamp delta likely spans a dropped packet. */
    private const val MIN_BREATH_RATE = 4.0

    private val defaultParser = BreathParser()

    /** Stateful parser owned by one physical VitalPro connection. */
    class BreathParser {
        private var previousTimestamp40ms: Long = -1L
        private var previousIntervalTicks: Long = -1L

        fun reset() {
            previousTimestamp40ms = -1L
            previousIntervalTicks = -1L
        }

        fun parse(bytes: ByteArray): BreathingData? = parseBreath(
            bytes = bytes,
            previousTimestamp = previousTimestamp40ms,
            previousInterval = previousIntervalTicks,
            updateState = { timestamp, interval ->
                previousTimestamp40ms = timestamp
                if (interval > 0L) previousIntervalTicks = interval
            },
        )
    }

    /** Compatibility parser for callers that do not own a connection parser. */
    fun resetState() = defaultParser.reset()

    /**
     * Parse a notification from characteristic 40B50004 into breathing data.
     * Only type 0x01 packets produce BreathingData; other types return null.
     *
     * BR is computed from the timestamp delta between consecutive 0x01 packets,
     * which captures the full breath period including inter-breath pauses.
     * This matches the Tymewear app's BR values (validated via FIT comparison).
     * The first packet establishes a timestamp baseline and is not published.
     *
     * Type 0x01 packet layout (17 bytes, uint16 LE):
     *   [0]      type = 0x01
     *   [1..4]   timestamp (uint32 LE, 40ms ticks)
     *   [5..6]   A = inhale duration (raw units)
     *   [7..8]   B = exhale duration (raw units)
     *   [9..10]  C = tidal volume (raw ADC delta)
     *   [11..12] D = C repeated
     *   [13..14] E = unknown metric
     *   [15..16] F = E repeated
     */
    fun parseNotification(bytes: ByteArray): BreathingData? = defaultParser.parse(bytes)

    private fun parseBreath(
        bytes: ByteArray,
        previousTimestamp: Long,
        previousInterval: Long,
        updateState: (timestamp: Long, interval: Long) -> Unit,
    ): BreathingData? {
        if (bytes.size < 2) return null
        val type = bytes[0].toInt() and 0xFF

        if (type != PKT_BREATH || bytes.size < 17) return null

        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val timestamp = buf.getInt(1).toLong() and 0xFFFFFFFFL  // uint32
            val a = buf.getShort(5).toInt() and 0xFFFF  // inhale duration
            val b = buf.getShort(7).toInt() and 0xFFFF  // exhale duration
            val c = buf.getShort(9).toInt() and 0xFFFF  // tv_raw
            val d = buf.getShort(11).toInt() and 0xFFFF // tv_raw repeated
            val e = buf.getShort(13).toInt() and 0xFFFF // unknown
            val f = buf.getShort(15).toInt() and 0xFFFF // unknown repeated

            if (a == 0 && b == 0 || c == 0) return null
            if (c != d || e != f) {
                if (BuildConfig.DEBUG) {
                    Timber.w("Rejected inconsistent breath packet: C=$c D=$d E=$e F=$f")
                }
                return null
            }

            val ieRatio = if (b > 0) a.toDouble() / b.toDouble() else 0.0

            // Compute BR from inter-packet timestamp delta (full breath period).
            if (previousTimestamp < 0L) {
                // Inhale+exhale omits the inter-breath pause and was measured to
                // overstate BR by about 1.8x. Warm up with one timestamp instead.
                updateState(timestamp, -1L)
                return null
            }

            val rawDeltaTicks = (timestamp - previousTimestamp) and 0xFFFF_FFFFL
            if (rawDeltaTicks == 0L) return null

            // If one or more notifications were missed, divide the gap by the
            // nearest prior-breath multiple instead of silently halving BR.
            var intervalTicks = rawDeltaTicks
            if (previousInterval > 0L && rawDeltaTicks > previousInterval * 1.6) {
                val intervalCount = (rawDeltaTicks.toDouble() / previousInterval)
                    .roundToInt()
                    .coerceIn(1, 8)
                if (intervalCount > 1) {
                    val adjusted = rawDeltaTicks / intervalCount
                    if (adjusted in (previousInterval / 2)..(previousInterval * 3 / 2)) {
                        if (BuildConfig.DEBUG) {
                            Timber.d(
                                "Adjusted %d-tick gap across %d likely breath intervals to %d ticks",
                                rawDeltaTicks,
                                intervalCount,
                                adjusted,
                            )
                        }
                        intervalTicks = adjusted
                    }
                }
            }

            val breathRate = 60.0 / (intervalTicks * TICK_SECONDS)
            if (breathRate < MIN_BREATH_RATE || breathRate > Constants.MAX_BREATHING_RATE) {
                if (BuildConfig.DEBUG) {
                    Timber.w("Rejected implausible BR %.1f from %d ticks", breathRate, intervalTicks)
                }
                updateState(timestamp, -1L)
                return null
            }
            // Keep the raw gap as the next baseline. This repairs one isolated
            // dropped notification without permanently masking a real slowdown.
            updateState(timestamp, rawDeltaTicks)

            val tvLiters = c * TV_CALIBRATION
            val minuteVolume = breathRate * tvLiters

            // Range validation
            if (tvLiters > Constants.MAX_TIDAL_VOLUME_L ||
                minuteVolume > Constants.MAX_MINUTE_VENTILATION
            ) {
                if (BuildConfig.DEBUG) {
                    Timber.w(
                        "Out of range: BR=%.1f, TV=%.3f, VE=%.1f L/min",
                        breathRate, tvLiters, minuteVolume,
                    )
                }
                return null
            }

            BreathingData(
                breathRate = breathRate,
                tidalVolume = tvLiters,
                minuteVolume = minuteVolume,
                ieRatio = ieRatio,
                veZone = 0,  // zone computed later with user thresholds
                inhaleDurationCs = a,
                exhaleDurationCs = b,
                tvRaw = c,
                fieldE = e,
                timestamp40ms = timestamp,
            )
        } catch (ex: Exception) {
            if (BuildConfig.DEBUG) Timber.w(ex, "Failed to parse breath packet")
            null
        }
    }

    /**
     * Return the packet type byte, or -1 if empty.
     */
    fun packetType(bytes: ByteArray): Int {
        return if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else -1
    }

    // -------------------------------------------------------------------------
    // FIT developer fields retained for external-platform interoperability.
    // -------------------------------------------------------------------------

    private const val FIT_FLOAT32: Short = 136

    val FIT_FIELD_BREATH_RATE = DeveloperField(
        fieldDefinitionNumber = 0,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_breath_rate",
        units = "brpm",
    )

    val FIT_FIELD_TIDAL_VOLUME = DeveloperField(
        fieldDefinitionNumber = 1,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_tidal_volume",
        units = "vol/br",
    )

    val FIT_FIELD_MINUTE_VOLUME = DeveloperField(
        fieldDefinitionNumber = 2,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_minute_volume",
        units = "vol/min",
    )

    val FIT_FIELD_IE_RATIO = DeveloperField(
        fieldDefinitionNumber = 3,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_inhale_exhale_ratio",
        units = "sec/sec",
    )

    val FIT_FIELD_MOBILIZATION_INDEX = DeveloperField(
        // Fork-only experimental field. VitalBreathe has its own FIT developer
        // identity, so its field numbers follow Hammerhead's required sequence.
        fieldDefinitionNumber = 4,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_mobilization_index",
        units = "%",
    )

    val FIT_FIELD_PERCENT_BRR = DeveloperField(
        // Fork-only experimental field. The public Tymewear integration only
        // guarantees the three core breathing field names above.
        fieldDefinitionNumber = 5,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_percent_brr",
        units = "%",
    )

    val FIT_FIELD_VE_ZONE = DeveloperField(
        fieldDefinitionNumber = 6,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone",
        units = "",
    )

    // Session summary fields
    val FIT_FIELD_VE_ZONE1_TIME = DeveloperField(
        fieldDefinitionNumber = 7,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone1_time",
        units = "min",
    )

    val FIT_FIELD_VE_ZONE1_PCT = DeveloperField(
        fieldDefinitionNumber = 8,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone1_percentage",
        units = "%",
    )

    val FIT_FIELD_VE_ZONE2_TIME = DeveloperField(
        fieldDefinitionNumber = 9,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone2_time",
        units = "min",
    )

    val FIT_FIELD_VE_ZONE2_PCT = DeveloperField(
        fieldDefinitionNumber = 10,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone2_percentage",
        units = "%",
    )

    val FIT_FIELD_VE_ZONE3_TIME = DeveloperField(
        fieldDefinitionNumber = 11,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone3_time",
        units = "min",
    )

    val FIT_FIELD_VE_ZONE3_PCT = DeveloperField(
        fieldDefinitionNumber = 12,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone3_percentage",
        units = "%",
    )

    val FIT_FIELD_VE_ZONE4_TIME = DeveloperField(
        fieldDefinitionNumber = 13,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone4_time",
        units = "min",
    )

    val FIT_FIELD_VE_ZONE4_PCT = DeveloperField(
        fieldDefinitionNumber = 14,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone4_percentage",
        units = "%",
    )

    val FIT_FIELD_VE_ZONE5_TIME = DeveloperField(
        fieldDefinitionNumber = 15,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone5_time",
        units = "min",
    )

    val FIT_FIELD_VE_ZONE5_PCT = DeveloperField(
        fieldDefinitionNumber = 16,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "tyme_ve_zone5_percentage",
        units = "%",
    )

    // Fork-owned fields are append-only so existing VitalBreathe FIT files keep
    // the same developer-field schema across app upgrades.
    val FIT_FIELD_VE_30S = DeveloperField(
        fieldDefinitionNumber = 17,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "vitalbreathe_ve_30s",
        units = "vol/min",
    )

    val FIT_FIELD_VE_ENDURANCE = DeveloperField(
        fieldDefinitionNumber = 18,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "vitalbreathe_ve_endurance",
        units = "vol/min",
    )

    val FIT_FIELD_VE_VT1 = DeveloperField(
        fieldDefinitionNumber = 19,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "vitalbreathe_ve_vt1",
        units = "vol/min",
    )

    val FIT_FIELD_VE_VT2 = DeveloperField(
        fieldDefinitionNumber = 20,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "vitalbreathe_ve_vt2",
        units = "vol/min",
    )

    val FIT_FIELD_VE_TOP_Z4 = DeveloperField(
        fieldDefinitionNumber = 21,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "vitalbreathe_ve_top_z4",
        units = "vol/min",
    )

    val FIT_FIELD_VE_VO2MAX = DeveloperField(
        fieldDefinitionNumber = 22,
        fitBaseTypeId = FIT_FLOAT32,
        fieldName = "vitalbreathe_ve_vo2max",
        units = "vol/min",
    )

    // -------------------------------------------------------------------------
    // Ventilation zone thresholds (Tymewear 5-zone model, Feb 2026 update)
    // -------------------------------------------------------------------------
    //
    // Endurance, VT1, VT2, and Top Z4 are the four transitions.
    // VO2max is the physiological/display ceiling for Z5, not a sixth boundary.

    /**
     * Determine VE zone from minute ventilation value and user thresholds.
     * Returns 0 if no data, 1-5 for active zones.
     */
    fun veZone(
        minuteVolume: Double,
        endurance: Double,
        vt1: Double,
        vt2: Double,
        topZ4: Double,
        vo2max: Double,
    ): Int = VentilationThresholds(endurance, vt1, vt2, topZ4, vo2max).zone(minuteVolume)
}

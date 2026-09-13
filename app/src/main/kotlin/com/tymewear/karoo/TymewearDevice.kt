package com.tymewear.karoo

import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.ManufacturerInfo
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.OnManufacturerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Represents a connected VitalPro breathing sensor.
 * Handles BLE connection lifecycle and data parsing into Karoo DeviceEvents.
 */
class TymewearDevice(
    private val extension: String,
    private val uid: String,
    private val displayName: String,
    private val bleManager: BleManager,
) {
    val source: Device by lazy {
        Device(
            extension = extension,
            uid = uid,
            dataTypes = listOf(
                DataType.dataTypeId(extension, "dashboard"),
                DataType.dataTypeId(extension, "ve"),
                DataType.dataTypeId(extension, "ve_graph"),
                DataType.dataTypeId(extension, "br"),
                DataType.dataTypeId(extension, "tv"),
                DataType.dataTypeId(extension, "mi"),
                DataType.dataTypeId(extension, "mi_bat"),
                DataType.dataTypeId(extension, "vp_battery"),
                DataType.dataTypeId(extension, "ve_zones"),
            ),
            displayName = displayName,
        )
    }

    private var scope: CoroutineScope? = null

    private fun emitDataPoints(
        emitter: Emitter<DeviceEvent>,
        snapshot: BreathPresentationSnapshot,
    ) {
        // Device events are the one canonical stream for each paired sensor type.
        // Graphical views read the same shared values; they do not publish a second stream.
        val values = buildMap {
            put("dashboard", snapshot.ventilationAverages.thirtySeconds)
            put("ve", snapshot.ventilationAverages.valueFor(SmoothingState.mode.value.windowMs))
            put(
                "ve_graph",
                snapshot.ventilationAverages.valueFor(GraphSmoothingState.mode.value.windowMs),
            )
            put("br", snapshot.smoothBreathRate)
            put("tv", snapshot.smoothTidalVolume) // litres per breath
            put("ve_zones", snapshot.zoneTimeTotal.toDouble())
            if (snapshot.miConfigured && snapshot.percentHrr >= 10.0) {
                put("mi", snapshot.mobilizationIndex)
                put("mi_bat", snapshot.mobilizationReserve)
            }
        }
        values.forEach { (typeId, value) ->
            emitDataPoint(emitter, typeId, value)
        }
    }

    private fun emitDataPoint(emitter: Emitter<DeviceEvent>, typeId: String, value: Double) {
        emitter.onNext(
            OnDataPoint(
                DataPoint(
                    dataTypeId = DataType.dataTypeId(extension, typeId),
                    values = mapOf(DataType.Field.SINGLE to value),
                    sourceId = uid,
                ),
            ),
        )
    }

    private fun emitBattery(emitter: Emitter<DeviceEvent>, percent: Int) {
        emitter.onNext(OnBatteryStatus(vitalProBatteryStatus(percent)))
        emitDataPoint(emitter, "vp_battery", percent.toDouble())
    }

    /**
     * Connect to the BLE device and start emitting DeviceEvents.
     */
    fun connect(emitter: Emitter<DeviceEvent>) {
        val connectScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        scope = connectScope

        connectScope.launch {
            var connected = false
            var pendingBattery: Int? = null
            emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))

            bleManager.connect(uid).collect { event ->
                when (event) {
                    is BleManager.ConnectionEvent.Connected -> {
                        connected = true
                        emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                        emitter.onNext(
                            OnManufacturerInfo(
                                ManufacturerInfo(
                                    manufacturer = "Tymewear",
                                    modelNumber = "VitalPro",
                                ),
                            ),
                        )
                        pendingBattery?.let { percent ->
                            emitBattery(emitter, percent)
                            pendingBattery = null
                        }
                    }

                    is BleManager.ConnectionEvent.Disconnected -> {
                        connected = false
                        pendingBattery = null
                        emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                    }

                    is BleManager.ConnectionEvent.Rejected -> {
                        connected = false
                        pendingBattery = null
                        Timber.w("VitalPro connection rejected: ${event.reason}")
                        emitter.onNext(OnConnectionStatus(ConnectionStatus.DISCONNECTED))
                    }

                    is BleManager.ConnectionEvent.BatteryLevel -> {
                        TymewearData.updateBattery(event.percent)
                        if (connected) {
                            emitBattery(emitter, event.percent)
                            pendingBattery = null
                        } else {
                            // GATT often returns battery before the first valid
                            // breath. Karoo may ignore sensor data while SEARCHING.
                            pendingBattery = event.percent
                        }
                    }

                    is BleManager.ConnectionEvent.Subscribed -> {
                        Timber.d("Breathing data notifications active")
                    }

                    is BleManager.ConnectionEvent.Breathing -> {
                        val data = event.data
                        if (BuildConfig.DEBUG) {
                            Timber.d(
                                "Breath: BR=%.1f brpm, TV_raw=%d (%.3f L), VE=%.1f, " +
                                    "IE=%.2f, A=%d, B=%d, E=%d, ts=%d",
                                data.breathRate, data.tvRaw, data.tidalVolume,
                                data.minuteVolume, data.ieRatio,
                                data.inhaleDurationCs, data.exhaleDurationCs,
                                data.fieldE, data.timestamp40ms,
                            )
                        }
                        emitDataPoints(emitter, event.presentation)
                    }

                    is BleManager.ConnectionEvent.Presentation -> {
                        emitDataPoints(emitter, event.presentation)
                    }

                    is BleManager.ConnectionEvent.Data -> {
                        when (event.characteristicUuid) {
                            Protocol.COMMAND_CHAR_UUID -> {
                                // Primary data stream — all breathing data arrives here
                                val pktType = Protocol.packetType(event.bytes)
                                when (pktType) {
                                    Protocol.PKT_BREATH -> {
                                        if (BuildConfig.DEBUG) {
                                            Timber.w("Unexpected raw breath packet")
                                        }
                                    }
                                    Protocol.PKT_IMU -> {
                                        // IMU data at ~1Hz — ignore for now
                                    }
                                    Protocol.PKT_ADC_PEAKS -> {
                                        // Raw ADC peak data paired with breath packet — ignore for now
                                    }
                                    else -> {
                                        if (BuildConfig.DEBUG) {
                                            Timber.d(
                                                "Unknown pkt type 0x%02x: %d bytes, hex=%s",
                                                pktType, event.bytes.size,
                                                event.bytes.joinToString("") { "%02x".format(it) },
                                            )
                                        }
                                    }
                                }
                            }
                            Protocol.BREATHING_DATA_CHAR_UUID -> {
                                if (BuildConfig.DEBUG) {
                                    Timber.d(
                                        "Char 0001 data: ${event.bytes.size} bytes, " +
                                            "hex=${event.bytes.joinToString("") { "%02x".format(it) }}",
                                    )
                                }
                            }
                            Protocol.SENSOR_ID_CHAR_UUID -> {
                                val sensorId = String(event.bytes, Charsets.UTF_8).trim()
                                Timber.d("Sensor ID: $sensorId")
                            }
                            else -> {
                                if (BuildConfig.DEBUG) {
                                    Timber.d(
                                        "Unknown char ${event.characteristicUuid}: " +
                                            "${event.bytes.joinToString("") { "%02x".format(it) }}",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        emitter.setCancellable {
            Timber.d("Cancelling device connection for $uid")
            connectScope.cancel()
        }
    }
}

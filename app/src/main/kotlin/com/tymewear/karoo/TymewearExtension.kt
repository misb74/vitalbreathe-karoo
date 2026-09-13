package com.tymewear.karoo

import android.os.SystemClock
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class TymewearExtension : KarooExtension("vitalbreathe", packagedBuildIdentity().versionName) {

    lateinit var karooSystem: KarooSystemService
    private lateinit var bleManager: BleManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
    private var karooCollectorsJob: Job? = null
    private val karooConnectionState = MutableStateFlow(KarooConnectionState())
    private val karooHrUpdates = KarooHrUpdateGate(TymewearData::updateHr)
    private val fitLifecycleLock = Any()
    private var fitGeneration = 0L
    private var activeFitSession: ActiveFitSession? = null

    internal var fitMetadataNowMs: () -> Long = SystemClock::elapsedRealtime
    internal var fitMetadataSettleDurationMs = FitMetadataSettleGate.DEFAULT_SETTLE_DURATION_MS

    private data class ActiveFitSession(
        val generation: Long,
        val scope: CoroutineScope,
        val emitter: Emitter<FitEffect>,
        val summaryTracker: FitSummaryTracker,
    )

    override val types by lazy {
        listOf(
            VitalDashboardDataType(extension),
            VentilationDataType(extension),
            VeGraphDataType(extension),
            BreathingRateDataType(extension),
            TidalVolumeDataType(extension),
            MobilizationIndexDataType(extension),
            MiBatteryDataType(extension),
            VitalProBatteryDataType(extension),
            TimeInZonesDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Keep bounded operational diagnostics available in release builds.
        // Stable strap identifiers are redacted by VitalBreatheLogTree, while
        // high-frequency physiological values remain debug-only.
        if (Timber.treeCount == 0) {
            Timber.plant(VitalBreatheLogTree())
        }
        Timber.d("TymewearExtension created")

        karooSystem = KarooSystemService(applicationContext)
        bleManager = BleManager(applicationContext)

        // Load zone thresholds before BLE data arrives
        TymewearData.loadThresholds(applicationContext)

        karooSystem.connect { connected ->
            karooConnectionState.update { it.transition(connected) }
            if (connected) {
                Timber.d("Connected to Karoo system")
                karooSystem.dispatch(RequestBluetooth(extension))
                karooCollectorsJob?.cancel()

                // Karoo can reconnect its system service. Keep exactly one copy of
                // the HR and RideState collectors across those reconnects.
                karooCollectorsJob = scope.launch {
                    launch {
                        karooSystem.streamDataFlow(DataType.Type.HEART_RATE)
                            .retryKarooStream("heart rate") {
                                karooHrUpdates.updateIfActive(0.0)
                            }
                            .collect { state ->
                                when (state) {
                                    is StreamState.Streaming -> {
                                        val hr = state.dataPoint.singleValue ?: return@collect
                                        if (BuildConfig.DEBUG) Timber.d("HR stream: %.0f bpm", hr)
                                        karooHrUpdates.updateIfActive(hr)
                                    }
                                    else -> {
                                        karooHrUpdates.updateIfActive(0.0)
                                        Timber.d("HR stream state: $state")
                                    }
                                }
                            }
                    }

                    // Preserve the upstream v0.4.3 ride-start fix. Public reports
                    // indicate this stopped the original second-ride freeze.
                    launch {
                        karooSystem.consumerFlow<RideState>()
                            .retryKarooStream("ride state")
                            .distinctUntilChangedBy { it::class }
                            .collect { state ->
                                Timber.d("RideState transition: $state")
                                if (state is RideState.Recording) {
                                    Timber.d("Recording started — re-dispatching RequestBluetooth")
                                    karooSystem.dispatch(RequestBluetooth(extension))
                                    BleForegroundService.retryIfActive(applicationContext)
                                }
                            }
                    }
                }
            } else {
                karooCollectorsJob?.cancel()
                karooCollectorsJob = null
                karooHrUpdates.updateIfActive(0.0)
            }
        }
    }

    override fun startScan(emitter: Emitter<Device>) {
        Timber.d("Starting VitalPro scan")
        karooSystem.dispatch(RequestBluetooth(extension))

        // Read configured sensor ID from preferences
        val prefs = applicationContext.getSharedPreferences("tymewear_prefs", MODE_PRIVATE)
        val sensorId = Protocol.normalizeSensorId(prefs.getString("sensor_id", null))

        // Keep pairing as a child of the extension lifecycle. BleManager also owns
        // the Android scan itself, so either cancellation path stops the radio scan.
        val scanJob = scope.launch {
            try {
                bleManager.scan(sensorId).collect { scannedDevice ->
                    val device = TymewearDevice(
                        extension = extension,
                        uid = scannedDevice.address,
                        displayName = scannedDevice.name,
                        bleManager = bleManager,
                    )
                    Timber.d("Emitting discovered device: ${scannedDevice.name}")
                    emitter.onNext(device.source)
                }
                emitter.onComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "VitalPro pairing scan ended with an error")
                emitter.onError(e)
            }
        }

        emitter.setCancellable {
            Timber.d("Scan cancelled")
            scanJob.cancel()
        }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        Timber.d("Connecting to device: $uid")
        karooSystem.dispatch(RequestBluetooth(extension))

        val device = TymewearDevice(
            extension = extension,
            uid = uid,
            displayName = "VitalPro",
            bleManager = bleManager,
        )
        device.connect(emitter)
    }

    override fun startFit(emitter: Emitter<FitEffect>) {
        Timber.d("startFit called")
        BleForegroundService.retryIfActive(applicationContext)

        // Use ELAPSED_TIME stream (ticks ~1Hz) combined with RideState
        // so we emit a FIT record every second while recording.
        // RideState alone only fires on state transitions.
        val fitScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val summaryTracker = FitSummaryTracker()
        val elapsedTracker = FitElapsedTracker()
        var zoneSessionId = 0L
        val (generation, fitThresholds) = synchronized(fitLifecycleLock) {
            // Defensively retire a stale FIT collector before resetting shared
            // session totals. Karoo normally cancels it before starting another.
            activeFitSession?.let { previous ->
                finalizeFitSessionBestEffort(previous, "FIT session replacement")
            }
            fitGeneration += 1L
            activeFitSession = ActiveFitSession(
                generation = fitGeneration,
                scope = fitScope,
                emitter = emitter,
                summaryTracker = summaryTracker,
            )
            zoneSessionId = TymewearData.beginZoneRecordingSession()
            fitGeneration to TymewearData.thresholds
        }
        // Record the exact personal boundaries held for this ride. Settings
        // saved during recording are deferred until this FIT session ends.
        val metadataSettleGate = try {
            writeSessionThresholdSnapshot(emitter, fitThresholds)
            FitMetadataSettleGate(
                snapshotWrittenAtMs = fitMetadataNowMs(),
                settleDurationMs = fitMetadataSettleDurationMs,
            )
        } catch (failure: Throwable) {
            // The Karoo emitter can fail synchronously before it has accepted a
            // cancellation callback. Retire everything installed above so the
            // abandoned start cannot keep settings frozen for a ride that never began.
            synchronized(fitLifecycleLock) {
                val active = activeFitSession
                if (generation == fitGeneration &&
                    active?.generation == generation &&
                    active.scope === fitScope
                ) {
                    fitGeneration += 1L
                    activeFitSession = null
                }
                fitScope.cancel()
                TymewearData.endZoneRecordingSession(zoneSessionId)
            }
            throw failure
        }
        fitScope.launch {
            try {
                karooConnectionState
                    .combineFreshWhileConnected { _ ->
                        val elapsedTime = karooSystem.fitElapsedSnapshotFlow()
                            .retryKarooStream("FIT elapsed time") {
                                synchronized(fitLifecycleLock) { elapsedTracker.pause() }
                            }
                        val rideState = karooSystem.consumerFlow<RideState>()
                            .retryKarooStreamWithAvailability("FIT ride state")
                        elapsedTime to rideState
                    }
                    .collect { values ->
                        synchronized(fitLifecycleLock) {
                            val active = activeFitSession
                            if (generation != fitGeneration ||
                                active?.generation != generation ||
                                active.scope !== fitScope
                            ) {
                                return@synchronized
                            }
                            val available = when (values) {
                                is FreshKarooValues.Available -> values
                                is FreshKarooValues.Unavailable -> {
                                    elapsedTracker.pause()
                                    return@synchronized
                                }
                            }
                            val connection = karooConnectionState.value
                            if (!connection.accepts(available.connectionGeneration)) {
                                // Reject an already-queued value from an invalidated
                                // service generation after a rapid reconnect.
                                elapsedTracker.pause()
                                return@synchronized
                            }
                            val elapsedSample = available.first
                            val elapsedMs = elapsedSample.elapsedMs
                            val rideStateAvailability = available.second
                            val rideState = when (rideStateAvailability) {
                                is KarooStreamAvailability.Available -> rideStateAvailability.value
                                KarooStreamAvailability.Unavailable -> {
                                    // Karoo removed the listener. Treat ride state as
                                    // unknown until the replacement listener reports.
                                    elapsedTracker.pause()
                                    return@synchronized
                                }
                            }
                            when (rideState) {
                                is RideState.Recording -> {
                                    // Karoo expands first-use developer fields into
                                    // metadata messages asynchronously. Keep a live
                                    // sensor from racing its first record past the
                                    // startup threshold descriptions.
                                    if (!metadataSettleGate.isReady(fitMetadataNowMs())) {
                                        elapsedTracker.pause()
                                        return@synchronized
                                    }
                                    val snapshot = elapsedSample.recordingSnapshot
                                    val tick = elapsedTracker.onRecordingTick(
                                        elapsedMs = elapsedMs,
                                        hasFreshSensorData = snapshot != null,
                                    )
                                    if (!tick.shouldEmitRecord || snapshot == null) {
                                        if (BuildConfig.DEBUG && snapshot == null) {
                                            Timber.d("Skipping FIT breath sample while VitalPro is unavailable")
                                        }
                                        return@synchronized
                                    }

                                    if (tick.zoneSeconds > 0L) {
                                        TymewearData.incrementZoneTime(snapshot.veZone, tick.zoneSeconds)
                                        writeSessionSummary(emitter, summaryTracker, force = false)
                                    }

                                    if (BuildConfig.DEBUG) {
                                        Timber.d(
                                            "FIT record: BR=%.1f TV=%.3f VE=%.1f zone=%d MI=%.1f",
                                            snapshot.breathRate,
                                            snapshot.tidalVolume,
                                            snapshot.minuteVolume,
                                            snapshot.veZone,
                                            snapshot.mobilizationIndex,
                                        )
                                    }

                                    // Write per-second record fields. Experimental
                                    // MI values are omitted until personal settings
                                    // and the required heart-rate signal are usable.
                                    val recordFields = buildList {
                                        add(FieldValue(Protocol.FIT_FIELD_BREATH_RATE, snapshot.breathRate))
                                        add(FieldValue(Protocol.FIT_FIELD_TIDAL_VOLUME, snapshot.tidalVolume))
                                        add(FieldValue(Protocol.FIT_FIELD_MINUTE_VOLUME, snapshot.minuteVolume))
                                        add(FieldValue(Protocol.FIT_FIELD_VE_30S, snapshot.smoothMinuteVolume))
                                        add(FieldValue(Protocol.FIT_FIELD_IE_RATIO, snapshot.ieRatio))
                                        add(FieldValue(Protocol.FIT_FIELD_VE_ZONE, snapshot.veZone.toDouble()))
                                        if (snapshot.miConfigured) {
                                            add(FieldValue(Protocol.FIT_FIELD_PERCENT_BRR, snapshot.percentBrr))
                                        }
                                        if (snapshot.mobilizationIndexAvailable) {
                                            add(
                                                FieldValue(
                                                    Protocol.FIT_FIELD_MOBILIZATION_INDEX,
                                                    snapshot.mobilizationIndex,
                                                ),
                                            )
                                        }
                                    }
                                    emitter.onNext(
                                        WriteToRecordMesg(recordFields),
                                    )
                                }

                                is RideState.Paused -> {
                                    elapsedTracker.pause()
                                    writeSessionSummary(emitter, summaryTracker, force = true)
                                }

                                is RideState.Idle -> {
                                    elapsedTracker.reset()
                                    writeSessionSummary(emitter, summaryTracker, force = true)
                                }
                            }
                        }
                    }
            } finally {
                retireFitCollectorAfterExit(
                    generation = generation,
                    fitScope = fitScope,
                    zoneSessionId = zoneSessionId,
                )
            }
        }

        emitter.setCancellable {
            synchronized(fitLifecycleLock) {
                try {
                    val active = activeFitSession
                    if (generation == fitGeneration &&
                        active?.generation == generation &&
                        active.scope === fitScope
                    ) {
                        // Invalidate and cancel first. The same lock ensures any
                        // in-flight tick is complete before the final checkpoint.
                        fitGeneration += 1L
                        activeFitSession = null
                        fitScope.cancel()
                        writeSessionSummary(emitter, summaryTracker, force = true)
                    } else {
                        fitScope.cancel()
                    }
                } finally {
                    TymewearData.endZoneRecordingSession(zoneSessionId)
                }
            }
        }
    }

    private fun writeSessionThresholdSnapshot(
        emitter: Emitter<FitEffect>,
        thresholds: VentilationThresholds,
    ) {
        emitter.onNext(
            WriteToSessionMesg(
                listOf(
                    FieldValue(Protocol.FIT_FIELD_VE_ENDURANCE, thresholds.endurance),
                    FieldValue(Protocol.FIT_FIELD_VE_VT1, thresholds.vt1),
                    FieldValue(Protocol.FIT_FIELD_VE_VT2, thresholds.vt2),
                    FieldValue(Protocol.FIT_FIELD_VE_TOP_Z4, thresholds.topZ4),
                    FieldValue(Protocol.FIT_FIELD_VE_VO2MAX, thresholds.vo2max),
                ),
            ),
        )
    }

    /**
     * A Binder write can fail after Karoo has accepted the FIT emitter. Retire
     * only the collector that still owns the active generation; an older
     * collector may finish after its replacement has already taken ownership.
     */
    private fun retireFitCollectorAfterExit(
        generation: Long,
        fitScope: CoroutineScope,
        zoneSessionId: Long,
    ) {
        synchronized(fitLifecycleLock) {
            try {
                val active = activeFitSession
                if (generation == fitGeneration &&
                    active?.generation == generation &&
                    active.scope === fitScope
                ) {
                    fitGeneration += 1L
                    activeFitSession = null
                    finalizeFitSessionBestEffort(active, "FIT collector termination")
                } else {
                    fitScope.cancel()
                }
            } finally {
                // Session IDs make this harmless after a newer collector starts.
                TymewearData.endZoneRecordingSession(zoneSessionId)
            }
        }
    }

    private fun writeSessionSummary(
        emitter: Emitter<FitEffect>,
        tracker: FitSummaryTracker,
        force: Boolean,
    ) {
        val summary = FitZoneSummary.from(TymewearData.zoneTimes.value) ?: return
        // Commit a checkpoint every 30 valid seconds. Pause, stop, and
        // cancellation force the final partial interval to be written.
        if (!tracker.markIfDue(summary.totalSeconds, force)) return

        emitter.onNext(
            WriteToSessionMesg(
                listOf(
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE1_TIME, summary.minutes(1)),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE1_PCT, summary.percentage(1)),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE2_TIME, summary.minutes(2)),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE2_PCT, summary.percentage(2)),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE3_TIME, summary.minutes(3)),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE3_PCT, summary.percentage(3)),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE4_TIME, summary.minutes(4)),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE4_PCT, summary.percentage(4)),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE5_TIME, summary.minutes(5)),
                    FieldValue(Protocol.FIT_FIELD_VE_ZONE5_PCT, summary.percentage(5)),
                ),
            ),
        )
    }

    /**
     * A stale Karoo emitter may already reference a dead Binder. Its final
     * checkpoint is useful when possible, but must never prevent retirement of
     * the collector or progress into the next lifecycle state.
     */
    private fun finalizeFitSessionBestEffort(session: ActiveFitSession, reason: String) {
        session.scope.cancel()
        try {
            writeSessionSummary(
                session.emitter,
                session.summaryTracker,
                force = true,
            )
        } catch (e: Exception) {
            Timber.w(e, "Unable to write final FIT session summary during $reason")
        }
    }

    override fun onDestroy() {
        Timber.d("TymewearExtension destroyed")
        synchronized(fitLifecycleLock) {
            activeFitSession?.let { active ->
                finalizeFitSessionBestEffort(active, "extension shutdown")
            }
            fitGeneration += 1L
            activeFitSession = null
        }
        TymewearData.clearZoneRecordingSession()
        scope.cancel()
        // BLE reconnects keep the Karoo HR stream value, but a destroyed
        // extension must not leak it into the next service instance.
        karooHrUpdates.closeAndClear()
        TymewearData.setDisconnected()
        bleManager.shutdown()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        BleForegroundService.stop(applicationContext)
        super.onDestroy()
    }
}

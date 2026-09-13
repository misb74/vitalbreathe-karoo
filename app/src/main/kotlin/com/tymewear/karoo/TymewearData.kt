package com.tymewear.karoo

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BreathSample(
    val elapsedRealtimeMs: Long,
    val data: Protocol.BreathingData,
)

data class RecordingSnapshot(
    val breathElapsedRealtimeMs: Long,
    val breathRate: Double,
    val tidalVolume: Double,
    val minuteVolume: Double,
    val smoothMinuteVolume: Double,
    val ieRatio: Double,
    val veZone: Int,
    val mobilizationIndex: Double,
    val percentBrr: Double,
    val miConfigured: Boolean,
    val mobilizationIndexAvailable: Boolean,
)

/** One internally consistent set of values for a Karoo breath event. */
data class BreathPresentationSnapshot(
    val data: Protocol.BreathingData,
    val ventilationAverages: VentilationAverages,
    val smoothBreathRate: Double,
    val smoothTidalVolume: Double,
    val smoothMinuteVolume: Double,
    val veZone: Int,
    val heartRate: Double,
    val percentHrr: Double,
    val percentBrr: Double,
    val mobilizationIndex: Double,
    val mobilizationReserve: Double,
    val miConfigured: Boolean,
    val batteryPercent: Int,
    val zoneTimeTotal: Long,
)

/** Explicit source selection for the VE Zones field. */
data class ZoneRecordingState(
    val isRecording: Boolean = false,
    val sessionId: Long = 0L,
)

/**
 * Shared state holder for the latest breathing data from the VitalPro sensor.
 * Used by data types and FIT recording to access current values.
 */
object TymewearData {

    private data class PersonalSettings(
        val thresholds: VentilationThresholds,
        val restingBr: Double,
        val maxBr: Double,
        val maxHr: Double,
        val restingHr: Double,
    )

    /** Serializes BLE, HR, preference, and disconnect state transitions. */
    private val stateLock = Any()
    private var currentBreathSample: BreathSample? = null

    private val _latestBreath = MutableStateFlow<BreathSample?>(null)
    val latestBreath: StateFlow<BreathSample?> = _latestBreath.asStateFlow()

    private val _recordingSnapshot = MutableStateFlow<RecordingSnapshot?>(null)
    val recordingSnapshot: StateFlow<RecordingSnapshot?> = _recordingSnapshot.asStateFlow()

    private val _presentationSnapshot = MutableStateFlow<BreathPresentationSnapshot?>(null)
    val presentationSnapshot: StateFlow<BreathPresentationSnapshot?> =
        _presentationSnapshot.asStateFlow()

    private val _breathRate = MutableStateFlow(0.0)
    val breathRate: StateFlow<Double> = _breathRate.asStateFlow()

    private val _tidalVolume = MutableStateFlow(0.0)
    val tidalVolume: StateFlow<Double> = _tidalVolume.asStateFlow()

    private val _minuteVolume = MutableStateFlow(0.0)
    val minuteVolume: StateFlow<Double> = _minuteVolume.asStateFlow()

    // Tymewear recommends VE 30s for stable zone feedback. These buffers use
    // elapsed time, not breath count, so the window stays 30 seconds at any effort.
    private const val ZONE_SMOOTHING_MS = 30_000L
    private const val VENTILATION_HISTORY_MS = 5 * 60_000L
    private val brBuffer = TimedRollingAverage(ZONE_SMOOTHING_MS)
    private val tvBuffer = TimedRollingAverage(ZONE_SMOOTHING_MS)
    private val veBuffer = TimedRollingAverage(VENTILATION_HISTORY_MS)

    private val _ventilationAverages = MutableStateFlow(VentilationAverages())
    val ventilationAverages: StateFlow<VentilationAverages> = _ventilationAverages.asStateFlow()

    private val _ventilationHistory = MutableStateFlow<List<TimedSample>>(emptyList())
    val ventilationHistory: StateFlow<List<TimedSample>> = _ventilationHistory.asStateFlow()

    private val _smoothBreathRate = MutableStateFlow(0.0)
    val smoothBreathRate: StateFlow<Double> = _smoothBreathRate.asStateFlow()

    private val _smoothTidalVolume = MutableStateFlow(0.0)
    val smoothTidalVolume: StateFlow<Double> = _smoothTidalVolume.asStateFlow()

    private val _smoothMinuteVolume = MutableStateFlow(0.0)
    val smoothMinuteVolume: StateFlow<Double> = _smoothMinuteVolume.asStateFlow()

    private val _ieRatio = MutableStateFlow(0.0)
    val ieRatio: StateFlow<Double> = _ieRatio.asStateFlow()

    private val _veZone = MutableStateFlow(0)
    val veZone: StateFlow<Int> = _veZone.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _batteryPercent = MutableStateFlow(-1)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    fun updateBattery(percent: Int) = synchronized(stateLock) {
        _batteryPercent.value = percent.coerceIn(0, 100)
        refreshPresentationSnapshotLocked()
    }

    // Zone time tracking (live, for TimeInZonesDataType display)
    private val _zoneTimes = MutableStateFlow(ZoneTimes())
    val zoneTimes: StateFlow<ZoneTimes> = _zoneTimes.asStateFlow()

    private val _zoneRecordingState = MutableStateFlow(ZoneRecordingState())
    val zoneRecordingState: StateFlow<ZoneRecordingState> =
        _zoneRecordingState.asStateFlow()

    /** Latest saved settings waiting for the active FIT ride to finish. */
    private var pendingSettings: PersonalSettings? = null

    /**
     * Start or take ownership of a FIT-backed zone session and return its
     * monotonically increasing ID. A genuinely new ride clears the previous totals;
     * a defensive collector replacement keeps the same ride totals and settings.
     */
    fun beginZoneRecordingSession(): Long = synchronized(stateLock) {
        // A replacement collector belongs to the same ride and must retain the
        // definitions already in use. A genuinely new ride gets saved changes.
        val replacingActiveSession = _zoneRecordingState.value.isRecording
        if (!replacingActiveSession) {
            applyPendingSettingsLocked()
            _zoneTimes.value = ZoneTimes()
        }
        val sessionId = _zoneRecordingState.value.sessionId + 1L
        _zoneRecordingState.value = ZoneRecordingState(
            isRecording = true,
            sessionId = sessionId,
        )
        refreshPresentationSnapshotLocked()
        sessionId
    }

    /** Ignore cancellation from an older FIT collector after a replacement starts. */
    fun endZoneRecordingSession(sessionId: Long) = synchronized(stateLock) {
        val current = _zoneRecordingState.value
        if (current.sessionId == sessionId && current.isRecording) {
            _zoneRecordingState.value = current.copy(isRecording = false)
            applyPendingSettingsLocked()
        }
    }

    fun clearZoneRecordingSession() = synchronized(stateLock) {
        val current = _zoneRecordingState.value
        if (current.isRecording) {
            _zoneRecordingState.value = current.copy(isRecording = false)
        }
        applyPendingSettingsLocked()
    }

    fun incrementZoneTime(zone: Int, seconds: Long = 1L) {
        if (seconds <= 0L) return
        synchronized(stateLock) {
            _zoneTimes.value = _zoneTimes.value.add(zone, seconds)
            refreshPresentationSnapshotLocked()
        }
    }

    fun resetZoneTimes() = synchronized(stateLock) {
        _zoneTimes.value = ZoneTimes()
        refreshPresentationSnapshotLocked()
    }

    // Zone thresholds (loaded from prefs). There are no universal defaults;
    // users copy all five values from their latest Tymewear threshold test.
    var enduranceThreshold = Constants.DEFAULT_ENDURANCE.toDouble()
        private set
    var vt1Threshold = Constants.DEFAULT_VT1.toDouble()
        private set
    var vt2Threshold = Constants.DEFAULT_VT2.toDouble()
        private set
    var topZ4Threshold = Constants.DEFAULT_TOP_Z4.toDouble()
        private set
    var vo2maxThreshold = Constants.DEFAULT_VO2MAX.toDouble()
        private set

    private val _thresholdsState = MutableStateFlow(
        VentilationThresholds(
            endurance = Constants.DEFAULT_ENDURANCE.toDouble(),
            vt1 = Constants.DEFAULT_VT1.toDouble(),
            vt2 = Constants.DEFAULT_VT2.toDouble(),
            topZ4 = Constants.DEFAULT_TOP_Z4.toDouble(),
            vo2max = Constants.DEFAULT_VO2MAX.toDouble(),
        ),
    )
    val thresholdsState: StateFlow<VentilationThresholds> = _thresholdsState.asStateFlow()

    private fun thresholdsLocked() = VentilationThresholds(
            endurance = enduranceThreshold,
            vt1 = vt1Threshold,
            vt2 = vt2Threshold,
            topZ4 = topZ4Threshold,
            vo2max = vo2maxThreshold,
        )

    val thresholds: VentilationThresholds
        get() = synchronized(stateLock) { thresholdsLocked() }

    // MI parameters (loaded from prefs)
    var restingBr = Constants.DEFAULT_RESTING_BR.toDouble()
        private set
    var maxBr = Constants.DEFAULT_MAX_BR.toDouble()
        private set
    var maxHr = Constants.DEFAULT_MAX_HR.toDouble()
        private set
    var restingHr = Constants.DEFAULT_RESTING_HR.toDouble()
        private set

    // HR/MI state
    private val _heartRate = MutableStateFlow(0.0)
    val heartRate: StateFlow<Double> = _heartRate.asStateFlow()

    private val _percentHrr = MutableStateFlow(0.0)
    val percentHrr: StateFlow<Double> = _percentHrr.asStateFlow()

    private val _mobilizationIndex = MutableStateFlow(0.0)
    val mobilizationIndex: StateFlow<Double> = _mobilizationIndex.asStateFlow()

    private val _percentBrr = MutableStateFlow(0.0)
    val percentBrr: StateFlow<Double> = _percentBrr.asStateFlow()

    /**
     * Load zone thresholds and MI parameters from SharedPreferences.
     */
    fun loadThresholds(context: Context) {
        val prefs = context.getSharedPreferences("tymewear_prefs", Context.MODE_PRIVATE)
        val endurance = prefs.getFloat("endurance_threshold", Constants.DEFAULT_ENDURANCE).toDouble()
        val vt1 = prefs.getFloat("vt1_threshold", Constants.DEFAULT_VT1).toDouble()
        val vt2 = prefs.getFloat("vt2_threshold", Constants.DEFAULT_VT2).toDouble()
        val topZ4 = prefs.getFloat("topz4_threshold", Constants.DEFAULT_TOP_Z4).toDouble()
        val vo2max = prefs.getFloat("vo2max_threshold", Constants.DEFAULT_VO2MAX).toDouble()
        // Older builds wrote generic placeholder values whenever any setting was
        // saved. Only the explicit flag makes MI personal and active now.
        val miConfigured = prefs.getBoolean("mi_configured", false)
        val loadedRestingBr = if (miConfigured) {
            prefs.getFloat("resting_br", Constants.DEFAULT_RESTING_BR).toDouble()
        } else {
            Constants.DEFAULT_RESTING_BR.toDouble()
        }
        val loadedMaxBr = if (miConfigured) {
            prefs.getFloat("max_br", Constants.DEFAULT_MAX_BR).toDouble()
        } else {
            Constants.DEFAULT_MAX_BR.toDouble()
        }
        val loadedMaxHr = if (miConfigured) {
            prefs.getFloat("max_hr", Constants.DEFAULT_MAX_HR).toDouble()
        } else {
            Constants.DEFAULT_MAX_HR.toDouble()
        }
        val loadedRestingHr = if (miConfigured) {
            prefs.getFloat("resting_hr", Constants.DEFAULT_RESTING_HR).toDouble()
        } else {
            Constants.DEFAULT_RESTING_HR.toDouble()
        }

        val settings = PersonalSettings(
            thresholds = VentilationThresholds(
                endurance = endurance,
                vt1 = vt1,
                vt2 = vt2,
                topZ4 = topZ4,
                vo2max = vo2max,
            ),
            restingBr = loadedRestingBr,
            maxBr = loadedMaxBr,
            maxHr = loadedMaxHr,
            restingHr = loadedRestingHr,
        )

        synchronized(stateLock) {
            if (_zoneRecordingState.value.isRecording) {
                // Keep one zone and MI definition for the whole FIT ride. The
                // most recently saved values become active when recording ends.
                pendingSettings = settings
            } else {
                applySettingsLocked(settings)
            }
        }
    }

    private fun applyPendingSettingsLocked() {
        val settings = pendingSettings ?: return
        pendingSettings = null
        applySettingsLocked(settings)
    }

    private fun applySettingsLocked(settings: PersonalSettings) {
        enduranceThreshold = settings.thresholds.endurance
        vt1Threshold = settings.thresholds.vt1
        vt2Threshold = settings.thresholds.vt2
        topZ4Threshold = settings.thresholds.topZ4
        vo2maxThreshold = settings.thresholds.vo2max
        restingBr = settings.restingBr
        maxBr = settings.maxBr
        maxHr = settings.maxHr
        restingHr = settings.restingHr

        _thresholdsState.value = settings.thresholds
        _veZone.value = settings.thresholds.zone(_smoothMinuteVolume.value)
        recomputeMiLocked()
        refreshRecordingSnapshotLocked()
        refreshPresentationSnapshotLocked()
    }

    fun update(data: Protocol.BreathingData): BreathPresentationSnapshot =
        updateAtElapsedRealtime(data, SystemClock.elapsedRealtime())

    internal fun updateAtElapsedRealtime(
        data: Protocol.BreathingData,
        now: Long,
    ): BreathPresentationSnapshot = synchronized(stateLock) {
        require(now >= 0L)
        val previousBreath = currentBreathSample
        if (previousBreath != null && !RecordingFreshnessPolicy.isFresh(
                sampleElapsedMs = previousBreath.elapsedRealtimeMs,
                nowElapsedMs = now,
                breathRate = previousBreath.data.breathRate,
            )
        ) {
            // A recovered breath must not fill an interval for which the strap had
            // stopped providing fresh measurements. Retain the graph trace, but
            // restart all rolling maths in a new continuity segment.
            brBuffer.markDiscontinuity()
            tvBuffer.markDiscontinuity()
            veBuffer.markDiscontinuity()
        }
        _breathRate.value = data.breathRate
        _tidalVolume.value = data.tidalVolume
        _minuteVolume.value = data.minuteVolume
        _ieRatio.value = data.ieRatio

        // Average VE directly. avg(BR) * avg(TV) is not the same as avg(BR * TV).
        brBuffer.add(data.breathRate, now)
        tvBuffer.add(data.tidalVolume, now)
        veBuffer.add(data.minuteVolume, now)
        val smoothBr = brBuffer.average(ZONE_SMOOTHING_MS, now)
        val smoothTv = tvBuffer.average(ZONE_SMOOTHING_MS, now)
        val averages = VentilationAverages(
            live = data.minuteVolume,
            fiveSeconds = veBuffer.average(5_000L, now),
            fifteenSeconds = veBuffer.average(15_000L, now),
            thirtySeconds = veBuffer.average(ZONE_SMOOTHING_MS, now),
            sixtySeconds = veBuffer.average(60_000L, now),
        )
        val smoothVe = averages.thirtySeconds
        _smoothBreathRate.value = smoothBr
        _smoothTidalVolume.value = smoothTv
        _smoothMinuteVolume.value = smoothVe
        _ventilationAverages.value = averages
        _ventilationHistory.value = veBuffer.snapshot(now)

        _veZone.value = thresholdsLocked().zone(smoothVe)
        _isConnected.value = true
        val sample = BreathSample(now, data)
        currentBreathSample = sample
        recomputeMiLocked()
        refreshRecordingSnapshotLocked(sample)
        refreshPresentationSnapshotLocked(sample)
        // Publish the per-breath trigger only after all values and the immutable
        // FIT snapshot belong to the same breath generation.
        _latestBreath.value = sample

        requireNotNull(_presentationSnapshot.value)
    }

    fun freshRecordingSnapshot(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): RecordingSnapshot? {
        return synchronized(stateLock) {
            val snapshot = _recordingSnapshot.value ?: return@synchronized null
            snapshot.takeIf {
                RecordingFreshnessPolicy.isFresh(
                    sampleElapsedMs = it.breathElapsedRealtimeMs,
                    nowElapsedMs = nowElapsedMs,
                    breathRate = it.breathRate,
                )
            }
        }
    }

    /**
     * Update heart rate from Karoo system stream and recompute %HRR and MI.
     */
    fun updateHr(hr: Double) = synchronized(stateLock) {
        _heartRate.value = hr.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        recomputeMiLocked()
        refreshRecordingSnapshotLocked()
        refreshPresentationSnapshotLocked()
    }

    private fun recomputeMiLocked() {
        val metrics = MobilizationCalculator.calculate(
            breathRate = _smoothBreathRate.value,
            heartRate = _heartRate.value,
            restingBreathRate = restingBr,
            maximumBreathRate = maxBr,
            restingHeartRate = restingHr,
            maximumHeartRate = maxHr,
        )
        _percentHrr.value = metrics.percentHrr
        _percentBrr.value = metrics.percentBrr
        _mobilizationIndex.value = metrics.index
    }

    private fun refreshRecordingSnapshotLocked(sample: BreathSample? = currentBreathSample) {
        val latest = sample ?: run {
            _recordingSnapshot.value = null
            return
        }
        val miConfigured = miConfiguredLocked()
        _recordingSnapshot.value = RecordingSnapshot(
            breathElapsedRealtimeMs = latest.elapsedRealtimeMs,
            breathRate = latest.data.breathRate,
            tidalVolume = latest.data.tidalVolume,
            minuteVolume = latest.data.minuteVolume,
            smoothMinuteVolume = _smoothMinuteVolume.value,
            ieRatio = latest.data.ieRatio,
            veZone = _veZone.value,
            mobilizationIndex = _mobilizationIndex.value,
            percentBrr = _percentBrr.value,
            miConfigured = miConfigured,
            mobilizationIndexAvailable = miConfigured &&
                _heartRate.value > 0.0 && _percentHrr.value >= 10.0,
        )
    }

    private fun miConfiguredLocked(): Boolean =
        restingBr.isFinite() && maxBr.isFinite() &&
            restingHr.isFinite() && maxHr.isFinite() &&
            restingBr > 0.0 && restingBr < maxBr &&
            restingHr > 0.0 && restingHr < maxHr

    private fun refreshPresentationSnapshotLocked(sample: BreathSample? = currentBreathSample) {
        val latest = sample ?: run {
            _presentationSnapshot.value = null
            return
        }
        val miConfigured = miConfiguredLocked()
        _presentationSnapshot.value = BreathPresentationSnapshot(
            data = latest.data,
            ventilationAverages = _ventilationAverages.value,
            smoothBreathRate = _smoothBreathRate.value,
            smoothTidalVolume = _smoothTidalVolume.value,
            smoothMinuteVolume = _smoothMinuteVolume.value,
            veZone = _veZone.value,
            heartRate = _heartRate.value,
            percentHrr = _percentHrr.value,
            percentBrr = _percentBrr.value,
            mobilizationIndex = _mobilizationIndex.value,
            mobilizationReserve = if (
                miConfigured && _heartRate.value > 0.0 && _percentHrr.value >= 10.0
            ) {
                (100.0 - _mobilizationIndex.value).coerceIn(0.0, 100.0)
            } else {
                0.0
            },
            miConfigured = miConfigured,
            batteryPercent = _batteryPercent.value,
            zoneTimeTotal = _zoneTimes.value.total,
        )
    }

    fun setDisconnected() = synchronized(stateLock) {
        val now = SystemClock.elapsedRealtime()
        _isConnected.value = false
        currentBreathSample = null
        _latestBreath.value = null
        _recordingSnapshot.value = null
        _presentationSnapshot.value = null
        _batteryPercent.value = -1
        brBuffer.clear()
        tvBuffer.clear()
        // Keep the last five minutes available to the graph, but lift the pen and
        // reset its smoothing before any later reconnect sample is accepted.
        veBuffer.markDiscontinuity()
        // Zero the user-facing flows so a dropped connection shows an unavailable marker instead
        // of the last cached value (which looks like a freeze).
        _breathRate.value = 0.0
        _tidalVolume.value = 0.0
        _minuteVolume.value = 0.0
        _ieRatio.value = 0.0
        _smoothBreathRate.value = 0.0
        _smoothTidalVolume.value = 0.0
        _smoothMinuteVolume.value = 0.0
        _ventilationAverages.value = VentilationAverages()
        _ventilationHistory.value = veBuffer.snapshot(now)
        _veZone.value = 0
        _percentBrr.value = 0.0
        _mobilizationIndex.value = 0.0
    }
}

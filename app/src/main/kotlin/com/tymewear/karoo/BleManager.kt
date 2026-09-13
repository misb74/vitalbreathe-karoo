package com.tymewear.karoo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

/**
 * Manages BLE scanning and one shared physical GATT connection per VitalPro address.
 *
 * Karoo may briefly replace its device-event subscriber at a ride boundary. Keeping
 * the physical connection alive through that hand-off avoids disconnect/reconnect
 * races and ensures all subscribers observe data parsed by one connection owner.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private companion object {
        const val PAIRING_BLUETOOTH_WAIT_MS = 15_000L
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val sessionsLock = Any()
    private val sessions = mutableMapOf<String, ConnectionSession>()
    private val nextSubscriberId = AtomicLong(0)
    private val managerClosed = AtomicBoolean(false)
    private val scanCooldown = ScanCooldown()
    private val pairingScansLock = Any()
    private val pairingScans = mutableMapOf<Long, PairingScanSession>()
    private val nextPairingScanId = AtomicLong(0)

    data class ScannedDevice(
        val name: String,
        val address: String,
    )

    /** Scan for VitalPro BLE devices, optionally filtering by sensor ID. */
    fun scan(sensorId: String? = null): Flow<ScannedDevice> = callbackFlow {
        val scanId = nextPairingScanId.incrementAndGet()
        val pairingScan = PairingScanSession(
            id = scanId,
            sensorId = sensorId,
            onDevice = { trySend(it) },
            onClosed = { cause -> close(cause) },
        )
        var supersededScans = emptyList<PairingScanSession>()
        val registered = synchronized(pairingScansLock) {
            if (managerClosed.get()) {
                false
            } else {
                // Karoo should expose one pairing scan at a time. Replace any
                // stale scan instead of running parallel Android scans that can
                // trigger the platform's too-frequent throttle.
                supersededScans = pairingScans.values.toList()
                pairingScans.clear()
                pairingScans[scanId] = pairingScan
                true
            }
        }
        if (!registered) {
            close(IllegalStateException("BleManager is shut down"))
            return@callbackFlow
        }

        supersededScans.forEach { it.requestStop() }
        pairingScan.start()
        awaitClose { pairingScan.requestStop() }
    }

    /**
     * Observe the shared physical connection for [address]. The GATT remains alive
     * briefly after the last collector leaves so Karoo can replace a subscriber
     * during ride start without forcing a new Bluetooth connection.
     */
    fun connect(address: String): Flow<ConnectionEvent> = callbackFlow {
        if (managerClosed.get()) {
            close(IllegalStateException("BleManager is shut down"))
            return@callbackFlow
        }

        val requestedAddress = normalizeBleAddress(address)
        val subscriberId = nextSubscriberId.incrementAndGet()
        val subscriber = Subscriber(
            onEvent = { trySend(it) },
            onClosed = { close() },
        )
        var conflictingAddress: String? = null
        val session = synchronized(sessionsLock) {
            if (managerClosed.get()) {
                null
            } else {
                val admission = decideBleSessionAdmission(
                    requestedAddress = requestedAddress,
                    sessions = sessions.values.map(ConnectionSession::snapshot),
                )
                val candidate = when (admission) {
                    BleSessionAdmission.Create -> null
                    is BleSessionAdmission.Reuse -> sessions[normalizeBleAddress(admission.address)]
                    is BleSessionAdmission.ReplaceIdle -> {
                        val oldKey = normalizeBleAddress(admission.oldAddress)
                        val oldSession = sessions.remove(oldKey)
                        oldSession?.requestShutdown("replaced by subscriber for $requestedAddress")
                        null
                    }
                    is BleSessionAdmission.RejectActive -> {
                        conflictingAddress = admission.activeAddress
                        null
                    }
                }

                if (conflictingAddress != null) {
                    null
                } else if (candidate != null && candidate.reserveSubscriber(subscriberId, subscriber)) {
                    candidate
                } else {
                    // The candidate may have stopped between the pure snapshot and
                    // reservation. Replace it atomically with a fresh session.
                    candidate?.let {
                        sessions.remove(requestedAddress, it)
                        it.requestShutdown("subscriber admission raced with shutdown")
                    }
                    ConnectionSession(requestedAddress).also { created ->
                        check(created.reserveSubscriber(subscriberId, subscriber))
                        sessions[requestedAddress] = created
                    }
                }
            }
        } ?: run {
            val message = if (conflictingAddress != null) {
                "VitalBreathe supports one active VitalPro sensor at a time"
            } else {
                "BleManager is shut down"
            }
            if (conflictingAddress != null) {
                Timber.w("Rejected a second active VitalPro connection")
                // Leave the existing strap's global state untouched. This new
                // device emitter receives an explicit terminal status.
                trySend(ConnectionEvent.Rejected(message))
                close()
                return@callbackFlow
            }
            close(IllegalStateException(message))
            return@callbackFlow
        }
        session.activateSubscriber(subscriberId)

        awaitClose {
            session.unsubscribe(subscriberId)
        }
    }

    /** Stop every shared connection. Safe to call more than once. */
    fun shutdown() {
        if (!managerClosed.compareAndSet(false, true)) return
        val active = synchronized(sessionsLock) {
            sessions.values.toList().also { sessions.clear() }
        }
        val activeScans = synchronized(pairingScansLock) {
            pairingScans.values.toList().also { pairingScans.clear() }
        }
        Timber.d(
            "BLE manager shutdown: closing ${active.size} shared session(s) and ${activeScans.size} pairing scan(s)",
        )
        activeScans.forEach {
            it.requestStop(IllegalStateException("BLE manager shut down during pairing scan"))
        }
        active.forEach { it.requestShutdown("manager shutdown") }
    }

    private fun removeSession(address: String, session: ConnectionSession) {
        synchronized(sessionsLock) {
            if (sessions[address] === session) sessions.remove(address)
        }
    }

    private fun removePairingScan(id: Long, scan: PairingScanSession) {
        synchronized(pairingScansLock) {
            if (pairingScans[id] === scan) pairingScans.remove(id)
        }
    }

    /** One manager-owned pairing scan, including the bounded Bluetooth-on wait. */
    private inner class PairingScanSession(
        private val id: Long,
        private val sensorId: String?,
        private val onDevice: (ScannedDevice) -> Unit,
        private val onClosed: (Throwable?) -> Unit,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val stopped = AtomicBoolean(false)
        private val seen = mutableSetOf<String>()
        private var scanner: BluetoothLeScanner? = null
        private var scanCallback: ScanCallback? = null
        private var bluetoothReceiver: BroadcastReceiver? = null
        private var bluetoothTimeout: Runnable? = null
        private var cooldownRunnable: Runnable? = null

        fun start() = runOnMain { waitForBluetoothOrScan() }

        fun requestStop(cause: Throwable? = null) {
            if (!stopped.compareAndSet(false, true)) return
            runOnMain {
                cleanup()
                removePairingScan(id, this)
                onClosed(cause)
            }
        }

        private fun waitForBluetoothOrScan() {
            if (stopped.get()) return
            val adapter = bluetoothAdapter
            if (adapter == null) {
                requestStop(IllegalStateException("Bluetooth adapter not available"))
                return
            }
            val initialState = try {
                adapter.state
            } catch (e: SecurityException) {
                requestStop(IllegalStateException("Bluetooth permission is not granted", e))
                return
            }
            if (initialState == BluetoothAdapter.STATE_ON) {
                startAfterCooldown()
                return
            }

            if (bluetoothReceiver == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                        val state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR,
                        )
                        if (state == BluetoothAdapter.STATE_ON) {
                            runOnMain {
                                unregisterBluetoothReceiver()
                                bluetoothTimeout?.let(handler::removeCallbacks)
                                bluetoothTimeout = null
                                startAfterCooldown()
                            }
                        }
                    }
                }
                bluetoothReceiver = receiver
                try {
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                } catch (e: Exception) {
                    bluetoothReceiver = null
                    requestStop(IllegalStateException("Unable to observe Bluetooth state", e))
                    return
                }
            }

            // Close the race in which Bluetooth reached ON between the first state
            // check and receiver registration.
            val stateAfterRegistration = try {
                adapter.state
            } catch (e: SecurityException) {
                requestStop(IllegalStateException("Bluetooth permission is not granted", e))
                return
            }
            if (stateAfterRegistration == BluetoothAdapter.STATE_ON) {
                unregisterBluetoothReceiver()
                startAfterCooldown()
                return
            }

            val timeout = Runnable {
                bluetoothTimeout = null
                requestStop(
                    IllegalStateException(
                        "Bluetooth did not turn on within ${PAIRING_BLUETOOTH_WAIT_MS}ms",
                    ),
                )
            }
            bluetoothTimeout = timeout
            handler.postDelayed(timeout, PAIRING_BLUETOOTH_WAIT_MS)
            Timber.d("BLE pairing scan waiting for Bluetooth STATE_ON")
        }

        private fun startAfterCooldown() {
            if (stopped.get()) return
            cooldownRunnable?.let(handler::removeCallbacks)
            cooldownRunnable = null
            val delayMs = scanCooldown.remainingDelayMs(SystemClock.elapsedRealtime())
            if (delayMs > 0L) {
                Timber.w("BLE pairing scan cooling down for ${delayMs}ms after scan throttling")
                val runnable = Runnable {
                    cooldownRunnable = null
                    startAfterCooldown()
                }
                cooldownRunnable = runnable
                handler.postDelayed(runnable, delayMs)
                return
            }
            startScanNow()
        }

        private fun startScanNow() {
            if (stopped.get()) return
            val currentScanner = try {
                bluetoothAdapter?.bluetoothLeScanner
            } catch (e: SecurityException) {
                requestStop(IllegalStateException("Bluetooth scan permission is not granted", e))
                return
            }
            if (currentScanner == null) {
                requestStop(IllegalStateException("BLE scanner not available after Bluetooth turned on"))
                return
            }
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post {
                        if (stopped.get() || scanCallback !== this) return@post
                        val name = result.scanRecord?.deviceName
                            ?: runCatching { result.device.name }.getOrNull()
                            ?: return@post
                        val address = try {
                            result.device.address
                        } catch (e: SecurityException) {
                            requestStop(
                                IllegalStateException(
                                    "Bluetooth device address permission is not granted",
                                    e,
                                ),
                            )
                            return@post
                        }
                        if (BuildConfig.DEBUG && !seen.contains(address)) {
                            Timber.d("BLE pairing scan saw $name ($address)")
                        }
                        if (!Protocol.isPairingCandidate(name, sensorId)) return@post
                        if (!seen.add(address)) return@post

                        Timber.d("BLE pairing scan found VitalPro $name ($address)")
                        onDevice(ScannedDevice(name, address))
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        if (stopped.get() || scanCallback !== this) return@post
                        Timber.e("BLE pairing scan failed: ${BleStatus.decodeScan(errorCode)}")
                        scanCooldown.recordFailure(errorCode, SystemClock.elapsedRealtime())
                        stopActiveScanOnly()
                        if (errorCode == ScanCooldown.SCAN_FAILED_SCANNING_TOO_FREQUENT) {
                            startAfterCooldown()
                        } else {
                            requestStop(IllegalStateException("BLE scan failed: $errorCode"))
                        }
                    }
                }
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner = currentScanner
            scanCallback = callback
            try {
                Timber.d("Starting BLE pairing scan (sensorId=$sensorId)")
                currentScanner.startScan(emptyList<ScanFilter>(), settings, callback)
            } catch (e: Exception) {
                Timber.e(e, "Unable to start BLE pairing scan")
                requestStop(IllegalStateException("Unable to start BLE pairing scan", e))
            }
        }

        private fun stopActiveScanOnly() {
            val callback = scanCallback ?: return
            scanCallback = null
            try {
                scanner?.stopScan(callback)
            } catch (e: Exception) {
                Timber.w(e, "Unable to stop BLE pairing scan cleanly")
            } finally {
                scanner = null
            }
        }

        private fun unregisterBluetoothReceiver() {
            val receiver = bluetoothReceiver ?: return
            bluetoothReceiver = null
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Timber.w(e, "Unable to unregister Bluetooth state receiver cleanly")
            }
        }

        private fun cleanup() {
            bluetoothTimeout?.let(handler::removeCallbacks)
            bluetoothTimeout = null
            cooldownRunnable?.let(handler::removeCallbacks)
            cooldownRunnable = null
            unregisterBluetoothReceiver()
            stopActiveScanOnly()
        }

        private fun runOnMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
        }
    }

    private data class Subscriber(
        val onEvent: (ConnectionEvent) -> Unit,
        val onClosed: () -> Unit,
    )

    private data class DescriptorOperation(
        val descriptor: BluetoothGattDescriptor,
        val characteristicUuid: UUID,
        val enableValue: ByteArray,
        val primary: Boolean,
    )

    private enum class SessionState {
        IDLE,
        SCANNING,
        CONNECTING,
        DISCOVERING,
        SUBSCRIBING,
        READY,
        BACKOFF,
        STOPPED,
    }

    /** All mutable BLE state below is confined to this session's main-thread Handler. */
    private inner class ConnectionSession(private val address: String) {
        private val handler = Handler(Looper.getMainLooper())
        private val subscriberOwnership = SubscriberOwnership<Subscriber>()
        private val sessionKey = "$address-${System.identityHashCode(this)}"

        private var started = false
        private var state = SessionState.IDLE
        private var generation = 0L
        private var failureHandledGeneration = -1L
        private var reconnectAttempt = 0

        private var currentGatt: BluetoothGatt? = null
        private var activeScanCallback: ScanCallback? = null
        private var scanTimeoutRunnable: Runnable? = null
        private var reconnectRunnable: Runnable? = null
        private var setupTimeoutRunnable: Runnable? = null
        private var firstPacketTimeoutRunnable: Runnable? = null
        private var batteryRetryRunnable: Runnable? = null
        private var batteryReadTimeoutRunnable: Runnable? = null
        private var batteryPollRunnable: Runnable? = null
        private var subscriberGraceRunnable: Runnable? = null

        private val descriptorQueue = ArrayDeque<DescriptorOperation>()
        private var activeDescriptorOperation: DescriptorOperation? = null

        private var ready = false
        private var receiving = false
        private var connectedEmittedGeneration = -1L
        private var firstValidBreath = false
        private var attemptStartedElapsedMs = 0L
        private var readyElapsedMs = 0L
        private var lastValidBreathElapsedMs = Long.MAX_VALUE
        private var lastPayloadEmitElapsedMs = 0L
        private var latestPresentation: BreathPresentationSnapshot? = null
        private var lastBatteryPercent: Int? = null
        private val batteryReadGate = BatteryReadGate()
        private var batteryQuickRetriesRemaining = 0
        private var cacheRefreshAttempted = false
        private var reconnectDevice: BluetoothDevice? = null
        private val breathParser = Protocol.BreathParser()

        fun snapshot(): BleSessionSnapshot = BleSessionSnapshot(
            address = address,
            accepting = subscriberOwnership.isAccepting(),
            ownerCount = subscriberOwnership.ownerCount(),
        )

        fun reserveSubscriber(id: Long, subscriber: Subscriber): Boolean =
            subscriberOwnership.reserve(id, subscriber)

        fun activateSubscriber(id: Long) {
            handler.post {
                val subscriber = subscriberOwnership.activate(id) ?: return@post

                subscriberGraceRunnable?.let(handler::removeCallbacks)
                subscriberGraceRunnable = null
                Timber.d(
                    "BLE[$address] subscriber+$id count=${subscriberOwnership.ownerCount()} state=$state gen=$generation",
                )

                // Give a replacement Karoo emitter the current connection snapshot.
                if (ready) subscriber.onEvent(ConnectionEvent.Subscribed)
                if (receiving) subscriber.onEvent(ConnectionEvent.Connected)
                latestFreshPresentation()?.let {
                    subscriber.onEvent(ConnectionEvent.Presentation(it))
                    lastPayloadEmitElapsedMs = SystemClock.elapsedRealtime()
                }
                // Preserve Connected-before-battery ordering for a live session.
                // Before the first valid breath, TymewearDevice caches this value.
                BatteryRefreshPolicy.replayableLevel(ready, lastBatteryPercent)
                    ?.let { subscriber.onEvent(ConnectionEvent.BatteryLevel(it)) }

                if (!started) {
                    started = true
                    BleForegroundService.physicalSessionStarted(context, sessionKey)
                    startWatchdog()
                    startTargetedScan("initial connection")
                }
            }
        }

        fun unsubscribe(id: Long) {
            val release = subscriberOwnership.release(id)
            if (release.removed == null) return
            handler.post {
                if (!subscriberOwnership.isAccepting()) return@post
                Timber.d(
                    "BLE[$address] subscriber-$id count=${subscriberOwnership.ownerCount()} state=$state gen=$generation",
                )
                if (release.wasPayloadOwner) {
                    // The previous subscriber resumes payload ownership. Replay
                    // the one-shot battery read that it may have missed.
                    val promoted = subscriberOwnership.latestActive()
                    if (promoted != null) {
                        latestFreshPresentation()?.let {
                            promoted.onEvent(ConnectionEvent.Presentation(it))
                            lastPayloadEmitElapsedMs = SystemClock.elapsedRealtime()
                        }
                        BatteryRefreshPolicy.replayableLevel(ready, lastBatteryPercent)
                            ?.let { promoted.onEvent(ConnectionEvent.BatteryLevel(it)) }
                    }
                }
                release.graceTicket?.let { ticket ->
                    val grace = Runnable {
                        subscriberGraceRunnable = null
                        if (subscriberOwnership.shouldExpire(ticket)) {
                            requestShutdown("subscriber grace expired")
                        }
                    }
                    subscriberGraceRunnable = grace
                    handler.postDelayed(grace, Constants.BLE_SUBSCRIBER_GRACE_MS)
                }
            }
        }

        fun requestShutdown(reason: String) {
            val listeners = subscriberOwnership.stopAndDrain() ?: return
            runOnMain { stopInternal(reason, listeners) }
        }

        private fun runOnMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
        }

        private fun stopInternal(reason: String, listeners: List<Subscriber>) {
            if (state == SessionState.STOPPED) return
            Timber.d("BLE[$address] stopping shared session: $reason state=$state gen=$generation")
            BleDiagnostics.stopped(address, reason)
            state = SessionState.STOPPED
            ready = false
            receiving = false
            latestPresentation = null
            lastPayloadEmitElapsedMs = 0L
            stopActiveScan()
            cancelReconnect()
            cancelSetupTimers()
            cancelBatteryRefresh()
            subscriberGraceRunnable?.let(handler::removeCallbacks)
            subscriberGraceRunnable = null
            descriptorQueue.clear()
            activeDescriptorOperation = null
            closeCurrentGatt(disconnectFirst = true)
            reconnectDevice = null
            TymewearData.setDisconnected()

            listeners.forEach { it.onEvent(ConnectionEvent.Disconnected) }
            listeners.forEach { it.onClosed() }

            BleForegroundService.physicalSessionStopped(context, sessionKey)
            removeSession(address, this)
        }

        private fun startTargetedScan(reason: String) {
            if (!subscriberOwnership.isAccepting()) return
            val cooldownDelayMs = scanCooldown.remainingDelayMs(SystemClock.elapsedRealtime())
            if (cooldownDelayMs > 0L) {
                state = SessionState.BACKOFF
                val expectedGeneration = generation
                if (reconnectRunnable == null) {
                    val runnable = Runnable {
                        reconnectRunnable = null
                        if (subscriberOwnership.isAccepting() && generation == expectedGeneration) {
                            startTargetedScan("$reason after scan cooldown")
                        }
                    }
                    reconnectRunnable = runnable
                    Timber.w(
                        "BLE[$address] deferring targeted scan ${cooldownDelayMs}ms after Android scan throttling",
                    )
                    handler.postDelayed(runnable, cooldownDelayMs)
                }
                return
            }
            state = SessionState.SCANNING
            BleDiagnostics.phase("Scanning", address, generation, reconnectAttempt)
            val configuredSensorId = configuredSensorId()
            val reconnectPolicy = bleReconnectPolicy(configuredSensorId)
            val fallback = reconnectDevice ?: remoteDeviceOrNull()
            val scanner = try {
                bluetoothAdapter?.bluetoothLeScanner
            } catch (e: SecurityException) {
                Timber.w(e, "BLE[$address] scan permission unavailable during targeted reconnect")
                handleTargetedScanMiss(reconnectPolicy, fallback, "Bluetooth scan permission unavailable")
                return
            }
            if (scanner == null) {
                Timber.w("BLE[$address] scanner unavailable")
                handleTargetedScanMiss(reconnectPolicy, fallback, "scanner unavailable")
                return
            }

            val scanStarted = SystemClock.elapsedRealtime()
            val filters = if (!reconnectPolicy.scanAllAdvertisements) {
                val addressFilter = try {
                    ScanFilter.Builder().setDeviceAddress(address).build()
                } catch (e: Exception) {
                    Timber.e(e, "BLE[$address] invalid stored device address")
                    failWithoutGatt("invalid device address")
                    return
                }
                listOf(addressFilter)
            } else {
                // Some BLE devices rotate their address. With a configured ID,
                // scan advertisements and recover the same TYME-XXXX sensor.
                emptyList()
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post {
                        if (activeScanCallback !== this || !subscriberOwnership.isAccepting()) return@post
                        val resultAddress = try {
                            result.device.address
                        } catch (e: SecurityException) {
                            Timber.w(e, "BLE[$address] cannot read targeted scan device address")
                            stopActiveScan()
                            handleTargetedScanMiss(
                                reconnectPolicy,
                                fallback,
                                "Bluetooth device address permission unavailable",
                            )
                            return@post
                        }
                        val advertisedName = result.scanRecord?.deviceName
                            ?: runCatching { result.device.name }.getOrNull()
                        val matchesConfiguredId = configuredSensorId != null &&
                            Protocol.isVitalProDevice(advertisedName) &&
                            Protocol.matchesSensorId(advertisedName, configuredSensorId)
                        val matchesTarget = reconnectPolicy.matchesCandidate(
                            storedAddress = address,
                            candidateAddress = resultAddress,
                            candidateName = advertisedName,
                        )
                        if (!matchesTarget) return@post
                        Timber.d(
                            "BLE[$address] targeted scan hit in ${SystemClock.elapsedRealtime() - scanStarted}ms rssi=${result.rssi} idMatch=$matchesConfiguredId",
                        )
                        stopActiveScan()
                        startGattAttempt(result.device, autoConnect = false, reason = "targeted scan hit")
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        if (activeScanCallback !== this || !subscriberOwnership.isAccepting()) return@post
                        Timber.w("BLE[$address] targeted scan failed: ${BleStatus.decodeScan(errorCode)}")
                        val now = SystemClock.elapsedRealtime()
                        scanCooldown.recordFailure(errorCode, now)
                        val minimumDelayMs = scanCooldown.remainingDelayMs(now)
                        stopActiveScan()
                        handleTargetedScanMiss(
                            reconnectPolicy,
                            fallback,
                            "scan failure ${BleStatus.decodeScan(errorCode)}",
                            minimumReconnectDelayMs = minimumDelayMs,
                        )
                    }
                }
            }

            activeScanCallback = callback
            val timeout = Runnable {
                if (activeScanCallback !== callback || !subscriberOwnership.isAccepting()) return@Runnable
                Timber.w("BLE[$address] targeted scan timed out after ${Constants.BLE_INITIAL_SCAN_TIMEOUT_MS}ms")
                stopActiveScan()
                handleTargetedScanMiss(reconnectPolicy, fallback, "scan timeout")
            }
            scanTimeoutRunnable = timeout

            try {
                Timber.d(
                    "BLE[$address] starting targeted scan reason=$reason sensorId=$configuredSensorId",
                )
                scanner.startScan(filters, settings, callback)
                handler.postDelayed(timeout, Constants.BLE_INITIAL_SCAN_TIMEOUT_MS)
            } catch (e: Exception) {
                Timber.e(e, "BLE[$address] unable to start targeted scan")
                stopActiveScan()
                handleTargetedScanMiss(reconnectPolicy, fallback, "scan exception")
            }
        }

        private fun configuredSensorId(): String? = context
            .getSharedPreferences("tymewear_prefs", Context.MODE_PRIVATE)
            .getString("sensor_id", null)
            .let(Protocol::normalizeSensorId)

        private fun handleTargetedScanMiss(
            reconnectPolicy: BleReconnectPolicy,
            fallback: BluetoothDevice?,
            reason: String,
            minimumReconnectDelayMs: Long = 0L,
        ) {
            if (reconnectPolicy.fallbackToKnownDeviceAfterScanMiss && fallback != null) {
                startGattAttempt(fallback, autoConnect = true, reason = "$reason fallback")
            } else {
                // A configured sensor ID is stable across address rotation. Back
                // off and scan for that identity again instead of pinning every
                // later reconnect to a cached physical address.
                failWithoutGatt(reason, minimumReconnectDelayMs)
            }
        }

        private fun stopActiveScan() {
            scanTimeoutRunnable?.let(handler::removeCallbacks)
            scanTimeoutRunnable = null
            val callback = activeScanCallback ?: return
            activeScanCallback = null
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (e: Exception) {
                Timber.w(e, "BLE[$address] unable to stop targeted scan cleanly")
            }
        }

        private fun remoteDeviceOrNull(): BluetoothDevice? = try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (e: Exception) {
            Timber.e(e, "BLE[$address] unable to resolve stored device")
            null
        }

        private fun startGattAttempt(target: BluetoothDevice, autoConnect: Boolean, reason: String) {
            if (!subscriberOwnership.isAccepting()) return
            stopActiveScan()
            cancelReconnect()
            cancelSetupTimers()
            cancelBatteryRefresh()
            closeCurrentGatt(disconnectFirst = true)
            reconnectDevice = target

            generation += 1
            val attemptGeneration = generation
            failureHandledGeneration = -1L
            state = SessionState.CONNECTING
            ready = false
            receiving = false
            firstValidBreath = false
            lastValidBreathElapsedMs = Long.MAX_VALUE
            lastBatteryPercent = null
            descriptorQueue.clear()
            activeDescriptorOperation = null
            attemptStartedElapsedMs = SystemClock.elapsedRealtime()
            BleDiagnostics.phase("Connecting", address, attemptGeneration, reconnectAttempt)

            Timber.d(
                "BLE[$address] gen=$attemptGeneration connect auto=$autoConnect retry=$reconnectAttempt reason=$reason",
            )

            val callback = createGattCallback(attemptGeneration)
            val newGatt = try {
                target.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: Exception) {
                Timber.e(e, "BLE[$address] gen=$attemptGeneration connectGatt threw")
                null
            }
            currentGatt = newGatt

            if (newGatt == null) {
                failCurrentGeneration(attemptGeneration, "connectGatt returned null")
                return
            }

            val timeoutMs = if (autoConnect) {
                Constants.BLE_AUTOCONNECT_SETUP_TIMEOUT_MS
            } else {
                Constants.BLE_SETUP_TIMEOUT_MS
            }
            val timeout = Runnable {
                if (isCurrentGeneration(attemptGeneration) && !ready) {
                    failCurrentGeneration(attemptGeneration, "setup timeout after ${timeoutMs}ms")
                }
            }
            setupTimeoutRunnable = timeout
            handler.postDelayed(timeout, timeoutMs)
        }

        private fun createGattCallback(attemptGeneration: Long): BluetoothGattCallback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    handler.post {
                        handleConnectionStateChange(gatt, attemptGeneration, status, newState)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    handler.post { handleServicesDiscovered(gatt, attemptGeneration, status) }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    val uuid = characteristic.uuid
                    val copy = value.copyOf()
                    handler.post { handleNotification(gatt, attemptGeneration, uuid, copy) }
                }

                @Suppress("DEPRECATION")
                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    val uuid = characteristic.uuid
                    val copy = characteristic.value?.copyOf() ?: return
                    handler.post { handleNotification(gatt, attemptGeneration, uuid, copy) }
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    handler.post {
                        handleDescriptorWrite(gatt, attemptGeneration, descriptor, status)
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    val uuid = characteristic.uuid
                    val copy = value.copyOf()
                    handler.post {
                        handleCharacteristicRead(gatt, attemptGeneration, uuid, copy, status)
                    }
                }

                @Suppress("DEPRECATION")
                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    val uuid = characteristic.uuid
                    val copy = characteristic.value?.copyOf() ?: byteArrayOf()
                    handler.post {
                        handleCharacteristicRead(gatt, attemptGeneration, uuid, copy, status)
                    }
                }

                override fun onServiceChanged(gatt: BluetoothGatt) {
                    handler.post { handleServiceChanged(gatt, attemptGeneration) }
                }
            }

        private fun handleServiceChanged(gatt: BluetoothGatt, attemptGeneration: Long) {
            if (!isCurrent(gatt, attemptGeneration)) {
                Timber.d(
                    "BLE[$address] ignoring stale service-changed callback gen=$attemptGeneration current=$generation",
                )
                return
            }
            // Android documents this callback as meaning the cached GATT database
            // is out of sync. A fresh generation gives discovery, subscriptions,
            // battery state, and all timeout bookkeeping one clean owner.
            failCurrentGeneration(attemptGeneration, "remote GATT services changed")
        }

        private fun handleConnectionStateChange(
            gatt: BluetoothGatt,
            attemptGeneration: Long,
            status: Int,
            newState: Int,
        ) {
            if (!isCurrent(gatt, attemptGeneration)) {
                Timber.d("BLE[$address] ignoring stale state callback gen=$attemptGeneration current=$generation")
                safeClose(gatt, disconnectFirst = false)
                return
            }

            Timber.d(
                "BLE[$address] gen=$attemptGeneration state=$newState status=${BleStatus.decodeGatt(status)}",
            )
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failCurrentGeneration(
                            attemptGeneration,
                            "connected with ${BleStatus.decodeGatt(status)}",
                        )
                        return
                    }
                    state = SessionState.DISCOVERING
                    BleDiagnostics.phase("Discovering", address, attemptGeneration, reconnectAttempt)
                    val accepted = try {
                        gatt.discoverServices()
                    } catch (e: Exception) {
                        Timber.e(e, "BLE[$address] gen=$attemptGeneration discoverServices threw")
                        false
                    }
                    Timber.d("BLE[$address] gen=$attemptGeneration discoverServices accepted=$accepted")
                    if (!accepted) {
                        failCurrentGeneration(attemptGeneration, "discoverServices rejected")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    failCurrentGeneration(
                        attemptGeneration,
                        "disconnected with ${BleStatus.decodeGatt(status)}",
                    )
                }

                else -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failCurrentGeneration(
                            attemptGeneration,
                            "state=$newState with ${BleStatus.decodeGatt(status)}",
                        )
                    }
                }
            }
        }

        private fun handleServicesDiscovered(
            gatt: BluetoothGatt,
            attemptGeneration: Long,
            status: Int,
        ) {
            if (!isCurrent(gatt, attemptGeneration)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                maybeRefreshGattCache(gatt, "service discovery ${BleStatus.decodeGatt(status)}")
                failCurrentGeneration(
                    attemptGeneration,
                    "service discovery failed: ${BleStatus.decodeGatt(status)}",
                )
                return
            }

            val service = gatt.getService(Protocol.VITALPRO_SERVICE_UUID)
            if (service == null) {
                Timber.e(
                    "BLE[$address] gen=$attemptGeneration missing VitalPro service; available=${gatt.services.joinToString { it.uuid.toString() }}",
                )
                maybeRefreshGattCache(gatt, "VitalPro service missing")
                failCurrentGeneration(attemptGeneration, "VitalPro service missing")
                return
            }

            val priorityAccepted = try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (e: Exception) {
                Timber.w(e, "BLE[$address] gen=$attemptGeneration priority request threw")
                false
            }
            Timber.d("BLE[$address] gen=$attemptGeneration HIGH priority accepted=$priorityAccepted")

            state = SessionState.SUBSCRIBING
            BleDiagnostics.phase("Subscribing", address, attemptGeneration, reconnectAttempt)
            descriptorQueue.clear()
            activeDescriptorOperation = null

            val uuid = Protocol.COMMAND_CHAR_UUID
            val characteristic = service.getCharacteristic(uuid)
            if (characteristic == null) {
                failCurrentGeneration(attemptGeneration, "primary COMMAND characteristic missing")
                return
            }
            val hasNotify =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val hasIndicate =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!hasNotify && !hasIndicate) {
                failCurrentGeneration(attemptGeneration, "primary COMMAND is not notifiable")
                return
            }
            val notificationAccepted = try {
                gatt.setCharacteristicNotification(characteristic, true)
            } catch (e: Exception) {
                Timber.w(e, "BLE[$address] gen=$attemptGeneration set notification threw for $uuid")
                false
            }
            if (!notificationAccepted) {
                failCurrentGeneration(attemptGeneration, "primary local notification rejected")
                return
            }
            val descriptor = characteristic.getDescriptor(Protocol.CCCD_UUID)
            if (descriptor == null) {
                failCurrentGeneration(attemptGeneration, "primary COMMAND CCCD missing")
                return
            }
            descriptorQueue.addLast(
                DescriptorOperation(
                    descriptor = descriptor,
                    characteristicUuid = uuid,
                    enableValue = if (hasNotify) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    },
                    primary = true,
                ),
            )
            writeNextDescriptor(gatt, attemptGeneration)
        }

        private fun writeNextDescriptor(gatt: BluetoothGatt, attemptGeneration: Long) {
            if (!isCurrent(gatt, attemptGeneration)) return
            val operation = descriptorQueue.pollFirst()
            if (operation == null) {
                activeDescriptorOperation = null
                startBatteryRefresh(gatt, attemptGeneration)
                return
            }

            activeDescriptorOperation = operation
            @Suppress("DEPRECATION")
            operation.descriptor.value = operation.enableValue
            val accepted = try {
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(operation.descriptor)
            } catch (e: Exception) {
                Timber.w(
                    e,
                    "BLE[$address] gen=$attemptGeneration descriptor write threw for ${operation.characteristicUuid}",
                )
                false
            }
            Timber.d(
                "BLE[$address] gen=$attemptGeneration descriptor write ${operation.characteristicUuid} accepted=$accepted primary=${operation.primary}",
            )
            if (!accepted) {
                activeDescriptorOperation = null
                if (operation.primary) {
                    failCurrentGeneration(attemptGeneration, "primary descriptor write rejected")
                } else {
                    writeNextDescriptor(gatt, attemptGeneration)
                }
            }
        }

        private fun handleDescriptorWrite(
            gatt: BluetoothGatt,
            attemptGeneration: Long,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!isCurrent(gatt, attemptGeneration)) return
            val operation = activeDescriptorOperation
            if (operation == null || operation.descriptor !== descriptor) {
                failCurrentGeneration(attemptGeneration, "unexpected descriptor callback")
                return
            }
            activeDescriptorOperation = null

            Timber.d(
                "BLE[$address] gen=$attemptGeneration descriptor ${operation.characteristicUuid} status=${BleStatus.decodeGatt(status)} primary=${operation.primary}",
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (operation.primary) {
                    failCurrentGeneration(
                        attemptGeneration,
                        "primary descriptor failed: ${BleStatus.decodeGatt(status)}",
                    )
                } else {
                    writeNextDescriptor(gatt, attemptGeneration)
                }
                return
            }

            if (operation.primary && connectedEmittedGeneration != attemptGeneration) {
                connectedEmittedGeneration = attemptGeneration
                ready = true
                state = SessionState.READY
                cancelReconnect()
                setupTimeoutRunnable?.let(handler::removeCallbacks)
                setupTimeoutRunnable = null
                breathParser.reset()
                readyElapsedMs = SystemClock.elapsedRealtime()
                firstValidBreath = false
                lastValidBreathElapsedMs = Long.MAX_VALUE
                latestPresentation = null
                lastPayloadEmitElapsedMs = 0L
                Timber.d(
                    "BLE[$address] gen=$attemptGeneration READY after ${readyElapsedMs - attemptStartedElapsedMs}ms",
                )
                BleDiagnostics.phase("Subscribed", address, attemptGeneration, reconnectAttempt)
                emitEvent(ConnectionEvent.Subscribed)
                // Promotion can have failed while Nearby Devices permission was
                // unavailable. Reaching a usable GATT generation is an eligible
                // recovery point after the permission is restored.
                BleForegroundService.retryIfActive(context)
                scheduleFirstPacketTimeout(attemptGeneration)
            }

            writeNextDescriptor(gatt, attemptGeneration)
        }

        private fun scheduleFirstPacketTimeout(attemptGeneration: Long) {
            firstPacketTimeoutRunnable?.let(handler::removeCallbacks)
            val timeout = Runnable {
                if (isCurrentGeneration(attemptGeneration) && ready && !firstValidBreath) {
                    failCurrentGeneration(
                        attemptGeneration,
                        "no valid breath within ${Constants.BLE_FIRST_PACKET_TIMEOUT_MS}ms",
                    )
                }
            }
            firstPacketTimeoutRunnable = timeout
            handler.postDelayed(timeout, Constants.BLE_FIRST_PACKET_TIMEOUT_MS)
        }

        private fun handleNotification(
            gatt: BluetoothGatt,
            attemptGeneration: Long,
            characteristicUuid: UUID,
            value: ByteArray,
        ) {
            if (!isCurrent(gatt, attemptGeneration)) return

            if (characteristicUuid != Protocol.COMMAND_CHAR_UUID) {
                emitEvent(ConnectionEvent.Data(characteristicUuid, value))
                return
            }
            if (!ready) {
                Timber.d("BLE[$address] gen=$attemptGeneration dropping primary packet before READY")
                return
            }

            if (Protocol.packetType(value) == Protocol.PKT_BREATH) {
                val parsed = try {
                    breathParser.parse(value)
                } catch (e: Exception) {
                    Timber.e(e, "BLE[$address] gen=$attemptGeneration breath parse failed")
                    null
                }
                if (parsed != null) {
                    val now = SystemClock.elapsedRealtime()
                    lastValidBreathElapsedMs = now
                    BleDiagnostics.breath(address, attemptGeneration, now)
                    // Permission can be restored without forcing an otherwise-live
                    // GATT to reconnect. Retry beside proven sensor traffic, with a
                    // bounded cooldown so persistent Android rejection cannot turn
                    // every breath into another synchronous start attempt and log.
                    BleForegroundService.retryIfActiveFromBreath(context, now)
                    if (!firstValidBreath) {
                        firstValidBreath = true
                        receiving = true
                        reconnectAttempt = 0
                        firstPacketTimeoutRunnable?.let(handler::removeCallbacks)
                        firstPacketTimeoutRunnable = null
                        Timber.d(
                            "BLE[$address] gen=$attemptGeneration first valid breath after ${now - readyElapsedMs}ms",
                        )
                        emitEvent(ConnectionEvent.Connected)
                    }
                    val presentation = TymewearData.update(parsed)
                    latestPresentation = presentation
                    lastPayloadEmitElapsedMs = now
                    emitEvent(ConnectionEvent.Breathing(parsed, presentation))
                } else {
                    if (BuildConfig.DEBUG) {
                        Timber.d(
                            "BLE[$address] gen=$attemptGeneration breath packet warming up or rejected",
                        )
                    }
                }
            } else {
                emitEvent(ConnectionEvent.Data(characteristicUuid, value))
            }
        }

        private fun startBatteryRefresh(gatt: BluetoothGatt, attemptGeneration: Long) {
            if (!isReadyGeneration(gatt, attemptGeneration)) return
            cancelBatteryRefresh()
            batteryQuickRetriesRemaining = BatteryRefreshPolicy.INITIAL_QUICK_RETRIES
            requestBatteryRead(gatt, attemptGeneration)
        }

        private fun requestBatteryRead(gatt: BluetoothGatt, attemptGeneration: Long) {
            if (!isReadyGeneration(gatt, attemptGeneration) || !batteryReadGate.begin()) return
            batteryRetryRunnable?.let(handler::removeCallbacks)
            batteryRetryRunnable = null
            batteryPollRunnable?.let(handler::removeCallbacks)
            batteryPollRunnable = null

            val characteristic = gatt.getService(Protocol.BATTERY_SERVICE_UUID)
                ?.getCharacteristic(Protocol.BATTERY_LEVEL_CHAR_UUID)
            if (characteristic == null) {
                Timber.d(
                    "BLE[$address] gen=$attemptGeneration standard battery level unavailable; continuing without battery",
                )
                cancelBatteryRefresh()
                return
            }

            val accepted = try {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(characteristic)
            } catch (e: Exception) {
                Timber.w(e, "BLE[$address] gen=$attemptGeneration battery read threw")
                false
            }
            Timber.d("BLE[$address] gen=$attemptGeneration battery read accepted=$accepted")
            if (!accepted) {
                batteryReadGate.rejected()
                scheduleAfterBatteryFailure(gatt, attemptGeneration, "request rejected")
                return
            }

            val timeout = Runnable {
                batteryReadTimeoutRunnable = null
                if (!isReadyGeneration(gatt, attemptGeneration) || !batteryReadGate.timedOut()) {
                    return@Runnable
                }
                Timber.w(
                    "BLE[$address] gen=$attemptGeneration battery read timed out after ${BatteryRefreshPolicy.READ_TIMEOUT_MS}ms",
                )
                // Android gives battery callbacks no request token. Do not issue a
                // second read until this operation eventually resolves; otherwise
                // its late callback could be mistaken for the retry. Breathing
                // continues, and a reconnect also resets this optional read gate.
            }
            batteryReadTimeoutRunnable = timeout
            handler.postDelayed(timeout, BatteryRefreshPolicy.READ_TIMEOUT_MS)
        }

        private fun handleCharacteristicRead(
            gatt: BluetoothGatt,
            attemptGeneration: Long,
            characteristicUuid: UUID,
            value: ByteArray,
            status: Int,
        ) {
            if (!isCurrent(gatt, attemptGeneration)) return
            if (characteristicUuid != Protocol.BATTERY_LEVEL_CHAR_UUID) return
            if (!ready) {
                Timber.d("BLE[$address] gen=$attemptGeneration ignoring unexpected battery callback")
                return
            }
            val completion = batteryReadGate.completed()
            if (completion == BatteryReadCompletion.UNEXPECTED) {
                Timber.d("BLE[$address] gen=$attemptGeneration ignoring unexpected battery callback")
                return
            }
            if (completion == BatteryReadCompletion.LATE_AFTER_TIMEOUT) {
                Timber.d("BLE[$address] gen=$attemptGeneration accepting resolved late battery callback")
            }
            batteryReadTimeoutRunnable?.let(handler::removeCallbacks)
            batteryReadTimeoutRunnable = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w(
                    "BLE[$address] gen=$attemptGeneration battery read failed: ${BleStatus.decodeGatt(status)}",
                )
                scheduleAfterBatteryFailure(
                    gatt,
                    attemptGeneration,
                    "read failed: ${BleStatus.decodeGatt(status)}",
                )
                return
            }
            val percent = value.firstOrNull()?.toInt()?.and(0xFF)
            if (percent == null || percent !in 0..100) {
                Timber.w("BLE[$address] gen=$attemptGeneration invalid battery value")
                scheduleAfterBatteryFailure(gatt, attemptGeneration, "invalid value")
                return
            }
            batteryQuickRetriesRemaining = 0
            lastBatteryPercent = percent
            Timber.d("BLE[$address] gen=$attemptGeneration battery=$percent%")
            emitEvent(ConnectionEvent.BatteryLevel(percent))
            scheduleBatteryPoll(gatt, attemptGeneration)
        }

        private fun scheduleAfterBatteryFailure(
            gatt: BluetoothGatt,
            attemptGeneration: Long,
            reason: String,
        ) {
            if (!isReadyGeneration(gatt, attemptGeneration)) return
            val schedule = BatteryRefreshPolicy.afterFailure(batteryQuickRetriesRemaining)
            batteryQuickRetriesRemaining = schedule.nextQuickRetriesRemaining
            when (schedule.action) {
                BatteryRefreshAction.QUICK_RETRY -> {
                    val retry = Runnable {
                        batteryRetryRunnable = null
                        if (isReadyGeneration(gatt, attemptGeneration)) {
                            requestBatteryRead(gatt, attemptGeneration)
                        }
                    }
                    batteryRetryRunnable = retry
                    Timber.w(
                        "BLE[$address] gen=$attemptGeneration battery $reason; retrying in ${schedule.delayMs}ms",
                    )
                    handler.postDelayed(retry, schedule.delayMs)
                }

                BatteryRefreshAction.REGULAR_POLL -> {
                    Timber.w(
                        "BLE[$address] gen=$attemptGeneration battery $reason; next poll in ${schedule.delayMs}ms",
                    )
                    scheduleBatteryPoll(gatt, attemptGeneration, schedule.delayMs)
                }
            }
        }

        private fun scheduleBatteryPoll(
            gatt: BluetoothGatt,
            attemptGeneration: Long,
            delayMs: Long = BatteryRefreshPolicy.POLL_INTERVAL_MS,
        ) {
            if (!isReadyGeneration(gatt, attemptGeneration)) return
            batteryPollRunnable?.let(handler::removeCallbacks)
            val poll = Runnable {
                batteryPollRunnable = null
                if (isReadyGeneration(gatt, attemptGeneration)) {
                    requestBatteryRead(gatt, attemptGeneration)
                }
            }
            batteryPollRunnable = poll
            handler.postDelayed(poll, delayMs)
        }

        private fun startWatchdog() {
            val watchdog = object : Runnable {
                override fun run() {
                    if (!subscriberOwnership.isAccepting()) return
                    val now = SystemClock.elapsedRealtime()
                    if (receiving && lastValidBreathElapsedMs != Long.MAX_VALUE) {
                        val age = now - lastValidBreathElapsedMs
                        if (age > Constants.BLE_DATA_WATCHDOG_TIMEOUT_MS) {
                            failCurrentGeneration(generation, "valid-breath watchdog age=${age}ms")
                        }
                    }
                    // Completed breaths can be 10–15 seconds apart at rest.
                    // Replay the latest still-fresh reading roughly once per second
                    // so Karoo does not treat the source as idle between breaths.
                    latestFreshPresentation(now)?.let { presentation ->
                        if (now - lastPayloadEmitElapsedMs >= Constants.BLE_DATA_WATCHDOG_INTERVAL_MS) {
                            emitEvent(ConnectionEvent.Presentation(presentation))
                            lastPayloadEmitElapsedMs = now
                        }
                    }
                    if (subscriberOwnership.isAccepting()) {
                        handler.postDelayed(this, Constants.BLE_DATA_WATCHDOG_INTERVAL_MS)
                    }
                }
            }
            handler.postDelayed(watchdog, Constants.BLE_DATA_WATCHDOG_INTERVAL_MS)
        }

        private fun latestFreshPresentation(
            nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        ): BreathPresentationSnapshot? {
            // HR, battery, threshold, and zone-time updates can happen between
            // breaths. Replay the current locked snapshot rather than the copy
            // captured when the last BLE notification arrived.
            val presentation = TymewearData.presentationSnapshot.value ?: return null
            if (!receiving || lastValidBreathElapsedMs == Long.MAX_VALUE) return null
            return presentation.takeIf {
                RecordingFreshnessPolicy.isFresh(
                    sampleElapsedMs = lastValidBreathElapsedMs,
                    nowElapsedMs = nowElapsedMs,
                    breathRate = presentation.data.breathRate,
                )
            }
        }

        private fun failWithoutGatt(reason: String, minimumReconnectDelayMs: Long = 0L) {
            cancelBatteryRefresh()
            generation += 1
            val failedGeneration = generation
            state = SessionState.BACKOFF
            ready = false
            receiving = false
            lastValidBreathElapsedMs = Long.MAX_VALUE
            latestPresentation = null
            lastBatteryPercent = null
            lastPayloadEmitElapsedMs = 0L
            Timber.w("BLE[$address] gen=$failedGeneration setup failed without GATT: $reason")
            BleDiagnostics.failure(address, failedGeneration, reconnectAttempt, reason)
            TymewearData.setDisconnected()
            emitEvent(ConnectionEvent.Disconnected)
            scheduleReconnect(failedGeneration, reason, minimumReconnectDelayMs)
        }

        private fun failCurrentGeneration(attemptGeneration: Long, reason: String) {
            if (!subscriberOwnership.isAccepting() || generation != attemptGeneration) return
            if (failureHandledGeneration == attemptGeneration) return
            failureHandledGeneration = attemptGeneration
            Timber.w("BLE[$address] gen=$attemptGeneration failure state=$state: $reason")
            BleDiagnostics.failure(address, attemptGeneration, reconnectAttempt, reason)
            state = SessionState.BACKOFF
            ready = false
            receiving = false
            firstValidBreath = false
            lastValidBreathElapsedMs = Long.MAX_VALUE
            latestPresentation = null
            lastBatteryPercent = null
            lastPayloadEmitElapsedMs = 0L
            cancelSetupTimers()
            cancelBatteryRefresh()
            descriptorQueue.clear()
            activeDescriptorOperation = null
            closeCurrentGatt(disconnectFirst = true)
            TymewearData.setDisconnected()
            emitEvent(ConnectionEvent.Disconnected)
            scheduleReconnect(attemptGeneration, reason)
        }

        private fun scheduleReconnect(
            failedGeneration: Long,
            reason: String,
            minimumDelayMs: Long = 0L,
        ) {
            if (!subscriberOwnership.isAccepting() || reconnectRunnable != null) return
            reconnectAttempt += 1
            val autoConnect = reconnectAttempt > Constants.BLE_RAPID_PHASE_ATTEMPTS
            val normalDelayMs = if (autoConnect) {
                Constants.BLE_SLOW_PHASE_DELAY_MS
            } else {
                Constants.BLE_RAPID_PHASE_DELAY_MS
            }
            val delayMs = maxOf(normalDelayMs, minimumDelayMs)
            val runnable = Runnable {
                reconnectRunnable = null
                if (!subscriberOwnership.isAccepting() || generation != failedGeneration) {
                    Timber.d("BLE[$address] ignoring stale reconnect for gen=$failedGeneration current=$generation")
                    return@Runnable
                }
                val retryReason = "reconnect #$reconnectAttempt after $reason"
                val reconnectPolicy = bleReconnectPolicy(configuredSensorId())
                if (reconnectPolicy.rescanBeforeReconnect) {
                    Timber.d("BLE[$address] $retryReason: rescanning configured sensor ID")
                    startTargetedScan(retryReason)
                    return@Runnable
                }
                val target = reconnectDevice ?: remoteDeviceOrNull()
                if (target == null) {
                    failWithoutGatt("reconnect device unavailable")
                } else {
                    startGattAttempt(
                        target,
                        autoConnect = autoConnect,
                        reason = retryReason,
                    )
                }
            }
            reconnectRunnable = runnable
            Timber.d(
                "BLE[$address] gen=$failedGeneration reconnect #$reconnectAttempt in ${delayMs}ms auto=$autoConnect",
            )
            handler.postDelayed(runnable, delayMs)
        }

        private fun cancelReconnect() {
            reconnectRunnable?.let(handler::removeCallbacks)
            reconnectRunnable = null
        }

        private fun cancelSetupTimers() {
            setupTimeoutRunnable?.let(handler::removeCallbacks)
            setupTimeoutRunnable = null
            firstPacketTimeoutRunnable?.let(handler::removeCallbacks)
            firstPacketTimeoutRunnable = null
        }

        private fun cancelBatteryRefresh() {
            batteryRetryRunnable?.let(handler::removeCallbacks)
            batteryRetryRunnable = null
            batteryReadTimeoutRunnable?.let(handler::removeCallbacks)
            batteryReadTimeoutRunnable = null
            batteryPollRunnable?.let(handler::removeCallbacks)
            batteryPollRunnable = null
            batteryReadGate.reset()
            batteryQuickRetriesRemaining = 0
        }

        private fun isCurrent(gatt: BluetoothGatt, attemptGeneration: Long): Boolean =
            subscriberOwnership.isAccepting() && generation == attemptGeneration && currentGatt === gatt

        private fun isCurrentGeneration(attemptGeneration: Long): Boolean =
            subscriberOwnership.isAccepting() && generation == attemptGeneration && currentGatt != null

        private fun isReadyGeneration(gatt: BluetoothGatt, attemptGeneration: Long): Boolean =
            isCurrent(gatt, attemptGeneration) && ready && state == SessionState.READY

        private fun closeCurrentGatt(disconnectFirst: Boolean) {
            val gatt = currentGatt ?: return
            currentGatt = null
            safeClose(gatt, disconnectFirst)
        }

        private fun safeClose(gatt: BluetoothGatt, disconnectFirst: Boolean) {
            if (disconnectFirst) {
                try {
                    gatt.disconnect()
                } catch (e: Exception) {
                    Timber.w(e, "BLE[$address] disconnect threw")
                }
            }
            try {
                gatt.close()
            } catch (e: Exception) {
                Timber.w(e, "BLE[$address] close threw")
            }
        }

        /** Hidden cache refresh is a one-shot recovery only after discovery is demonstrably bad. */
        private fun maybeRefreshGattCache(gatt: BluetoothGatt, reason: String) {
            if (cacheRefreshAttempted) return
            cacheRefreshAttempted = true
            val refreshed = try {
                val method = gatt.javaClass.getMethod("refresh")
                method.invoke(gatt) as? Boolean ?: false
            } catch (e: Exception) {
                Timber.w(e, "BLE[$address] GATT cache refresh unavailable")
                false
            }
            Timber.w("BLE[$address] conditional GATT cache refresh=$refreshed reason=$reason")
        }

        private fun emitEvent(event: ConnectionEvent) {
            when (event) {
                // Karoo can briefly overlap an old and replacement emitter. Only
                // the newest subscriber may publish payloads or samples duplicate.
                is ConnectionEvent.Breathing,
                is ConnectionEvent.Presentation,
                is ConnectionEvent.Data,
                is ConnectionEvent.BatteryLevel -> subscriberOwnership.latestActive()?.onEvent(event)

                // Status belongs to every subscriber so an outgoing emitter can
                // still shut down cleanly while the replacement takes ownership.
                ConnectionEvent.Connected,
                ConnectionEvent.Disconnected,
                is ConnectionEvent.Rejected,
                ConnectionEvent.Subscribed -> subscriberOwnership.activeSnapshot().forEach { it.onEvent(event) }
            }
        }
    }

    sealed class ConnectionEvent {
        data object Connected : ConnectionEvent()
        data object Disconnected : ConnectionEvent()
        data object Subscribed : ConnectionEvent()
        data class Rejected(val reason: String) : ConnectionEvent()
        data class Breathing(
            val data: Protocol.BreathingData,
            val presentation: BreathPresentationSnapshot,
        ) : ConnectionEvent()
        data class Presentation(val presentation: BreathPresentationSnapshot) : ConnectionEvent()
        data class Data(val characteristicUuid: UUID, val bytes: ByteArray) : ConnectionEvent()
        data class BatteryLevel(val percent: Int) : ConnectionEvent()
    }
}

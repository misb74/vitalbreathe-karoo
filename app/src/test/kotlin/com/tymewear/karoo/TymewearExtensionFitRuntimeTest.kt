package com.tymewear.karoo

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import io.hammerhead.karooext.aidl.IHandler
import io.hammerhead.karooext.aidl.IKarooExtension
import io.hammerhead.karooext.aidl.IKarooSystem
import io.hammerhead.karooext.internal.bundleWithSerializable
import io.hammerhead.karooext.internal.serializableFromBundle
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.KarooEffect
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.KarooEventParams
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TymewearExtensionFitRuntimeTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences(PREFS_NAME, 0).edit().clear().commit()
        TymewearData.setDisconnected()
        TymewearData.clearZoneRecordingSession()
        TymewearData.resetZoneTimes()
        TymewearData.updateHr(0.0)
    }

    @After
    fun tearDown() {
        TymewearData.setDisconnected()
        TymewearData.clearZoneRecordingSession()
        TymewearData.resetZoneTimes()
        TymewearData.updateHr(0.0)
        application.getSharedPreferences(PREFS_NAME, 0).edit().clear().commit()
    }

    @Test
    fun `FIT Binder lifecycle waits for fresh reconnect inputs and excludes reconnect gap`() {
        configureZones()
        val karoo = FakeKarooSystem()
        val shadowApplication = shadowOf(application) as ShadowApplication
        shadowApplication.setComponentNameAndServiceForBindService(
            KAROO_COMPONENT,
            karoo.asBinder(),
        )
        shadowApplication.setBindServiceCallsOnServiceConnectedDirectly(true)

        val serviceController: ServiceController<TymewearExtension> =
            createExtensionService(metadataSettleDurationMs = 0L)
        val service = serviceController.get()
        val extension = IKarooExtension.Stub.asInterface(service.onBind(Intent()))
        val fitHandler = CapturingFitHandler()
        var fitStopped = false
        var serviceDestroyed = false

        try {
            awaitCondition("initial Karoo Bluetooth request") {
                karoo.effects.any { it is RequestBluetooth }
            }
            awaitCondition("extension lifecycle consumers") {
                karoo.activeConsumers().any { it.params is RideState.Params }
            }

            val consumersBeforeFit = karoo.consumerIds()
            extension.startFit(FIT_ID, fitHandler)
            awaitCondition("FIT threshold snapshot") {
                fitHandler.sessionMessagesContaining("vitalbreathe_ve_endurance").size == 1
            }
            assertThresholdSnapshot(
                fitHandler.sessionMessagesContaining("vitalbreathe_ve_endurance").single(),
            )

            val firstElapsed = karoo.awaitNewStreamConsumer(
                dataTypeId = DataType.Type.ELAPSED_TIME,
                excludedIds = consumersBeforeFit,
            )
            val firstFitRide = karoo.awaitNewRideConsumer(consumersBeforeFit)
            assertTrue(TymewearData.zoneRecordingState.value.isRecording)

            TymewearData.update(sampleBreath())
            karoo.emit(firstFitRide, RideState.Recording)
            karoo.emitElapsed(firstElapsed, 1_000.0)
            awaitCondition("first FIT record") { fitHandler.recordMessages().size == 1 }

            karoo.emitElapsed(firstElapsed, 2_000.0)
            awaitCondition("second FIT record") { fitHandler.recordMessages().size == 2 }
            assertEquals(1L, TymewearData.zoneTimes.value.total)
            assertRecordFields(fitHandler.recordMessages().last())

            val recordCountBeforeReconnect = fitHandler.recordMessages().size
            val oldElapsedHandler = firstElapsed.handler
            val oldRideHandler = firstFitRide.handler
            val firstGenerationConsumerIds = karoo.consumerIds()
            val serviceConnection = shadowApplication.boundKarooConnection()

            // Keep these callbacks adjacent to reproduce the StateFlow-conflation case
            // where a fast reconnect can hide the disconnected state from a collector.
            serviceConnection.onServiceDisconnected(KAROO_COMPONENT)
            serviceConnection.onServiceConnected(KAROO_COMPONENT, karoo.asBinder())

            val secondElapsed = karoo.awaitReplacementStreamConsumer(
                dataTypeId = DataType.Type.ELAPSED_TIME,
                oldId = firstElapsed.id,
            )
            val secondRideConsumers = karoo.awaitReplacementRideConsumers(
                excludedIds = firstGenerationConsumerIds,
            )

            // Callbacks from consumers retired by the reconnect must not revive
            // FIT output or add the disconnected interval to a zone.
            oldElapsedHandler.onNext(elapsedEvent(90_000.0))
            oldRideHandler.onNext(karooEventBundle(RideState.Recording))
            assertRemains("stale callbacks produced a FIT record") {
                fitHandler.recordMessages().size == recordCountBeforeReconnect
            }
            assertEquals(1L, TymewearData.zoneTimes.value.total)

            // One replacement input is insufficient. Both consumers must report in
            // the new Karoo connection generation before FIT recording can resume.
            karoo.emitElapsed(secondElapsed, 20_000.0)
            assertRemains("elapsed time combined with stale ride state") {
                fitHandler.recordMessages().size == recordCountBeforeReconnect
            }
            secondRideConsumers.forEach { karoo.emit(it, RideState.Recording) }
            awaitCondition("FIT record after both replacement inputs") {
                fitHandler.recordMessages().size == recordCountBeforeReconnect + 1
            }
            assertEquals("reconnect gap was credited to a zone", 1L, TymewearData.zoneTimes.value.total)

            karoo.emitElapsed(secondElapsed, 21_000.0)
            awaitCondition("post-reconnect zone second") {
                TymewearData.zoneTimes.value.total == 2L
            }

            extension.stopFit(FIT_ID)
            fitStopped = true
            awaitCondition("final FIT session summary") {
                fitHandler.sessionMessagesContaining("tyme_ve_zone1_time").size == 1
            }
            assertFalse(TymewearData.zoneRecordingState.value.isRecording)
            assertSessionSummary(
                fitHandler.sessionMessagesContaining("tyme_ve_zone1_time").single(),
            )
            assertEquals(2, fitHandler.sessionMessages().size)

            TymewearData.updateHr(151.0)
            serviceController.destroy()
            serviceDestroyed = true
            awaitCondition("Karoo Bluetooth release") {
                karoo.effects.any { it is ReleaseBluetooth }
            }
            assertEquals(0.0, TymewearData.heartRate.value, 0.0)
            assertEquals(2, fitHandler.sessionMessages().size)
        } finally {
            if (!fitStopped) {
                runCatching { extension.stopFit(FIT_ID) }
            }
            if (!serviceDestroyed) {
                serviceController.destroy()
            }
        }
    }

    @Test
    fun `live data at ride start waits for FIT metadata before records`() {
        configureZones()
        val karoo = FakeKarooSystem()
        val shadowApplication = shadowOf(application) as ShadowApplication
        shadowApplication.setComponentNameAndServiceForBindService(
            KAROO_COMPONENT,
            karoo.asBinder(),
        )
        shadowApplication.setBindServiceCallsOnServiceConnectedDirectly(true)

        val metadataNowMs = AtomicLong(10_000L)
        val serviceController = createExtensionService(
            metadataSettleDurationMs = FitMetadataSettleGate.DEFAULT_SETTLE_DURATION_MS,
        )
        val service = serviceController.get().also { extensionService ->
            extensionService.fitMetadataNowMs = { metadataNowMs.get() }
        }
        val extension = IKarooExtension.Stub.asInterface(service.onBind(Intent()))
        val fitHandler = CapturingFitHandler()
        val fitId = "fit-live-at-start-metadata-order-test"
        var fitStopped = false
        var serviceDestroyed = false

        try {
            awaitCondition("extension lifecycle consumers") {
                karoo.activeConsumers().any { it.params is RideState.Params }
            }
            TymewearData.update(sampleBreath())
            val consumersBeforeFit = karoo.consumerIds()

            extension.startFit(fitId, fitHandler)

            val thresholdSnapshot = fitHandler
                .sessionMessagesContaining("vitalbreathe_ve_endurance")
                .single()
            assertThresholdSnapshot(thresholdSnapshot)
            assertTrue(fitHandler.allEffects().first() === thresholdSnapshot)

            val elapsed = karoo.awaitNewStreamConsumer(
                dataTypeId = DataType.Type.ELAPSED_TIME,
                excludedIds = consumersBeforeFit,
            )
            val ride = karoo.awaitNewRideConsumer(consumersBeforeFit)
            karoo.emit(ride, RideState.Recording)

            karoo.emitElapsed(elapsed, 1_000.0)
            assertRemains("live data escaped the metadata settle gate") {
                fitHandler.recordMessages().isEmpty()
            }

            metadataNowMs.set(11_999L)
            karoo.emitElapsed(elapsed, 2_000.0)
            assertRemains("record emitted before the full settle interval") {
                fitHandler.recordMessages().isEmpty()
            }

            metadataNowMs.set(12_000L)
            karoo.emitElapsed(elapsed, 3_000.0)
            awaitCondition("first FIT record after metadata settled") {
                fitHandler.recordMessages().size == 1
            }
            assertRecordFields(fitHandler.recordMessages().single())
            assertTrue(
                fitHandler.allEffects().indexOfFirst { it is WriteToRecordMesg } >
                    fitHandler.allEffects().indexOf(thresholdSnapshot),
            )

            karoo.emitElapsed(elapsed, 4_000.0)
            awaitCondition("normal FIT recording after metadata settled") {
                fitHandler.recordMessages().size == 2
            }
            assertEquals(1L, TymewearData.zoneTimes.value.total)

            extension.stopFit(fitId)
            fitStopped = true
            serviceController.destroy()
            serviceDestroyed = true
        } finally {
            if (!fitStopped) {
                runCatching { extension.stopFit(fitId) }
            }
            if (!serviceDestroyed) {
                serviceController.destroy()
            }
        }
    }

    @Test
    fun `blank personal settings write core FIT data without optional or zone summary fields`() {
        val karoo = FakeKarooSystem()
        val shadowApplication = shadowOf(application) as ShadowApplication
        shadowApplication.setComponentNameAndServiceForBindService(
            KAROO_COMPONENT,
            karoo.asBinder(),
        )
        shadowApplication.setBindServiceCallsOnServiceConnectedDirectly(true)

        val serviceController: ServiceController<TymewearExtension> =
            createExtensionService(metadataSettleDurationMs = 0L)
        val extension = IKarooExtension.Stub.asInterface(serviceController.get().onBind(Intent()))
        val fitHandler = CapturingFitHandler()
        val fitId = "fit-blank-settings-test"
        var fitStopped = false
        var serviceDestroyed = false

        try {
            awaitCondition("extension lifecycle consumers") {
                karoo.activeConsumers().any { it.params is RideState.Params }
            }
            val consumersBeforeFit = karoo.consumerIds()
            extension.startFit(fitId, fitHandler)

            val thresholdSnapshot = fitHandler
                .sessionMessagesContaining("vitalbreathe_ve_endurance")
                .single()
            val thresholdValues = thresholdSnapshot.values.associate { field ->
                requireNotNull(field.developerField).fieldName to field.value
            }
            assertEquals(
                setOf(
                    "vitalbreathe_ve_endurance",
                    "vitalbreathe_ve_vt1",
                    "vitalbreathe_ve_vt2",
                    "vitalbreathe_ve_top_z4",
                    "vitalbreathe_ve_vo2max",
                ),
                thresholdValues.keys,
            )
            assertTrue(thresholdValues.values.all { it == 0.0 })

            val elapsed = karoo.awaitNewStreamConsumer(
                dataTypeId = DataType.Type.ELAPSED_TIME,
                excludedIds = consumersBeforeFit,
            )
            val ride = karoo.awaitNewRideConsumer(consumersBeforeFit)

            TymewearData.update(sampleBreath())
            karoo.emit(ride, RideState.Recording)
            karoo.emitElapsed(elapsed, 1_000.0)
            awaitCondition("blank-settings FIT record") {
                fitHandler.recordMessages().size == 1
            }

            val recordValues = fitHandler.recordMessages().single().values.associate { field ->
                requireNotNull(field.developerField).fieldName to field.value
            }
            assertEquals(
                setOf(
                    "tyme_breath_rate",
                    "tyme_tidal_volume",
                    "tyme_minute_volume",
                    "vitalbreathe_ve_30s",
                    "tyme_inhale_exhale_ratio",
                    "tyme_ve_zone",
                ),
                recordValues.keys,
            )
            assertEquals(0.0, recordValues.getValue("tyme_ve_zone"), 0.0)
            assertFalse("tyme_mobilization_index" in recordValues)
            assertFalse("tyme_percent_brr" in recordValues)

            karoo.emitElapsed(elapsed, 2_000.0)
            awaitCondition("second blank-settings FIT record") {
                fitHandler.recordMessages().size == 2
            }
            assertEquals(0L, TymewearData.zoneTimes.value.total)

            extension.stopFit(fitId)
            fitStopped = true
            assertFalse(TymewearData.zoneRecordingState.value.isRecording)
            assertRemains("disabled zones emitted a session summary") {
                fitHandler.sessionMessages().size == 1
            }
            assertTrue(
                fitHandler.sessionMessages().none { message ->
                    message.values.any { field ->
                        field.developerField?.fieldName?.startsWith("tyme_ve_zone") == true
                    }
                },
            )

            serviceController.destroy()
            serviceDestroyed = true
        } finally {
            if (!fitStopped) {
                runCatching { extension.stopFit(fitId) }
            }
            if (!serviceDestroyed) {
                serviceController.destroy()
            }
        }
    }

    @Test
    fun `queued elapsed callback cannot borrow a sensor snapshot that arrived later`() {
        configureZones()
        val karoo = FakeKarooSystem()
        val shadowApplication = shadowOf(application) as ShadowApplication
        shadowApplication.setComponentNameAndServiceForBindService(
            KAROO_COMPONENT,
            karoo.asBinder(),
        )
        shadowApplication.setBindServiceCallsOnServiceConnectedDirectly(true)

        val serviceController: ServiceController<TymewearExtension> =
            createExtensionService(metadataSettleDurationMs = 0L)
        val extension = IKarooExtension.Stub.asInterface(serviceController.get().onBind(Intent()))
        val fitHandler = BlockingRecordFitHandler()
        var fitStopped = false
        var serviceDestroyed = false

        try {
            awaitCondition("extension lifecycle consumers") {
                karoo.activeConsumers().any { it.params is RideState.Params }
            }
            val consumersBeforeFit = karoo.consumerIds()
            extension.startFit("fit-callback-capture-test", fitHandler)
            val elapsed = karoo.awaitNewStreamConsumer(
                dataTypeId = DataType.Type.ELAPSED_TIME,
                excludedIds = consumersBeforeFit,
            )
            val ride = karoo.awaitNewRideConsumer(consumersBeforeFit)

            TymewearData.update(sampleBreath(minuteVolume = 25.0))
            karoo.emit(ride, RideState.Recording)
            karoo.emitElapsed(elapsed, 1_000.0)
            assertTrue(fitHandler.awaitFirstRecord())

            // Hold the FIT collector in the first emitter callback. These old
            // elapsed callbacks therefore queue while no sensor sample is fresh.
            TymewearData.setDisconnected()
            karoo.emitElapsed(elapsed, 2_000.0)
            karoo.emitElapsed(elapsed, 2_100.0)
            karoo.emitElapsed(elapsed, 2_200.0)

            // A new breath arrives before the collector drains its callback.
            // The queued elapsed value must retain the null snapshot captured above.
            TymewearData.update(sampleBreath(minuteVolume = 35.0))
            fitHandler.releaseFirstRecord()
            assertRemains("queued elapsed callback borrowed the later breath") {
                fitHandler.recordMessages().size == 1
            }

            karoo.emitElapsed(elapsed, 3_000.0)
            awaitCondition("record from a newly paired elapsed callback") {
                fitHandler.recordMessages().size == 2
            }
            assertEquals(
                35.0,
                fitHandler.recordValue(
                    fitHandler.recordMessages().last(),
                    "tyme_minute_volume",
                ),
                0.0,
            )
            assertEquals(0L, TymewearData.zoneTimes.value.total)

            extension.stopFit("fit-callback-capture-test")
            fitStopped = true
            assertFalse(TymewearData.zoneRecordingState.value.isRecording)
            serviceController.destroy()
            serviceDestroyed = true
        } finally {
            fitHandler.releaseFirstRecord()
            if (!fitStopped) {
                runCatching { extension.stopFit("fit-callback-capture-test") }
            }
            if (!serviceDestroyed) {
                serviceController.destroy()
            }
        }
    }

    @Test
    fun `failed initial FIT threshold write rolls back session and unfreezes settings`() {
        configureZones()
        val karoo = FakeKarooSystem()
        val shadowApplication = shadowOf(application) as ShadowApplication
        shadowApplication.setComponentNameAndServiceForBindService(
            KAROO_COMPONENT,
            karoo.asBinder(),
        )
        shadowApplication.setBindServiceCallsOnServiceConnectedDirectly(true)

        val serviceController: ServiceController<TymewearExtension> =
            createExtensionService(metadataSettleDurationMs = 0L)
        val extension = IKarooExtension.Stub.asInterface(serviceController.get().onBind(Intent()))
        var recoveredFitStarted = false
        var serviceDestroyed = false

        try {
            assertThrows(IllegalStateException::class.java) {
                extension.startFit("fit-threshold-failure-test", ThrowingFitHandler())
            }
            assertFalse(TymewearData.zoneRecordingState.value.isRecording)

            configureZones(
                endurance = 11f,
                vt1 = 21f,
                vt2 = 31f,
                topZ4 = 41f,
                vo2max = 51f,
            )
            TymewearData.loadThresholds(application)
            assertEquals(11.0, TymewearData.thresholds.endurance, 0.0)

            val recoveredHandler = CapturingFitHandler()
            extension.startFit("fit-after-threshold-failure", recoveredHandler)
            recoveredFitStarted = true
            assertTrue(TymewearData.zoneRecordingState.value.isRecording)
            assertEquals(
                11.0,
                recoveredHandler.sessionMessagesContaining("vitalbreathe_ve_endurance")
                    .single()
                    .values
                    .single { it.developerField?.fieldName == "vitalbreathe_ve_endurance" }
                    .value,
                0.0,
            )

            extension.stopFit("fit-after-threshold-failure")
            recoveredFitStarted = false
            assertFalse(TymewearData.zoneRecordingState.value.isRecording)
            serviceController.destroy()
            serviceDestroyed = true
        } finally {
            if (recoveredFitStarted) {
                runCatching { extension.stopFit("fit-after-threshold-failure") }
            }
            if (!serviceDestroyed) {
                serviceController.destroy()
            }
        }
    }

    @Test
    fun `record write failure after FIT startup retires session and unfreezes settings`() {
        configureZones()
        val karoo = FakeKarooSystem()
        val shadowApplication = shadowOf(application) as ShadowApplication
        shadowApplication.setComponentNameAndServiceForBindService(
            KAROO_COMPONENT,
            karoo.asBinder(),
        )
        shadowApplication.setBindServiceCallsOnServiceConnectedDirectly(true)

        val serviceController: ServiceController<TymewearExtension> =
            createExtensionService(metadataSettleDurationMs = 0L)
        val extension = IKarooExtension.Stub.asInterface(serviceController.get().onBind(Intent()))
        val fitId = "fit-record-write-failure-test"
        val fitHandler = FailingAfterStartFitHandler(FitWriteFailure.RECORD)
        var fitRemoved = false
        var serviceDestroyed = false

        try {
            awaitCondition("extension lifecycle consumers") {
                karoo.activeConsumers().any { it.params is RideState.Params }
            }
            val consumersBeforeFit = karoo.consumerIds()
            extension.startFit(fitId, fitHandler)
            val elapsed = karoo.awaitNewStreamConsumer(
                dataTypeId = DataType.Type.ELAPSED_TIME,
                excludedIds = consumersBeforeFit,
            )
            val ride = karoo.awaitNewRideConsumer(consumersBeforeFit)

            TymewearData.update(sampleBreath())
            karoo.emit(ride, RideState.Recording)
            karoo.emitElapsed(elapsed, 1_000.0)

            assertTrue(fitHandler.awaitFailure())
            awaitCondition("FIT session cleanup after record rejection") {
                !TymewearData.zoneRecordingState.value.isRecording
            }
            assertEquals(1L, fitHandler.failureCount())
            assertNewSettingsApplyImmediately(endurance = 11f)

            // Remove the dead host emitter only after proving cleanup did not
            // depend on Karoo sending stopFit.
            extension.stopFit(fitId)
            fitRemoved = true
            serviceController.destroy()
            serviceDestroyed = true
        } finally {
            if (!fitRemoved) {
                runCatching { extension.stopFit(fitId) }
            }
            if (!serviceDestroyed) {
                serviceController.destroy()
            }
        }
    }

    @Test
    fun `summary write failure after FIT startup retires session and unfreezes settings`() {
        configureZones()
        val karoo = FakeKarooSystem()
        val shadowApplication = shadowOf(application) as ShadowApplication
        shadowApplication.setComponentNameAndServiceForBindService(
            KAROO_COMPONENT,
            karoo.asBinder(),
        )
        shadowApplication.setBindServiceCallsOnServiceConnectedDirectly(true)

        val serviceController: ServiceController<TymewearExtension> =
            createExtensionService(metadataSettleDurationMs = 0L)
        val extension = IKarooExtension.Stub.asInterface(serviceController.get().onBind(Intent()))
        val fitId = "fit-summary-write-failure-test"
        val fitHandler = FailingAfterStartFitHandler(FitWriteFailure.SUMMARY)
        var fitRemoved = false
        var serviceDestroyed = false

        try {
            awaitCondition("extension lifecycle consumers") {
                karoo.activeConsumers().any { it.params is RideState.Params }
            }
            val consumersBeforeFit = karoo.consumerIds()
            extension.startFit(fitId, fitHandler)
            val elapsed = karoo.awaitNewStreamConsumer(
                dataTypeId = DataType.Type.ELAPSED_TIME,
                excludedIds = consumersBeforeFit,
            )
            val ride = karoo.awaitNewRideConsumer(consumersBeforeFit)

            TymewearData.update(sampleBreath())
            karoo.emit(ride, RideState.Recording)
            karoo.emitElapsed(elapsed, 1_000.0)
            awaitCondition("first accepted FIT record") {
                fitHandler.recordMessages().size == 1
            }
            karoo.emitElapsed(elapsed, 2_000.0)
            awaitCondition("one recorded zone second") {
                TymewearData.zoneTimes.value.total == 1L
            }

            // Pausing forces the partial zone summary and exercises the session
            // message failure path independently from record writes.
            karoo.emit(ride, RideState.Paused(auto = false))
            assertTrue(fitHandler.awaitFailure())
            awaitCondition("FIT session cleanup after summary rejection") {
                !TymewearData.zoneRecordingState.value.isRecording
            }
            assertEquals(1L, fitHandler.failureCount())
            assertNewSettingsApplyImmediately(endurance = 12f)

            extension.stopFit(fitId)
            fitRemoved = true
            serviceController.destroy()
            serviceDestroyed = true
        } finally {
            if (!fitRemoved) {
                runCatching { extension.stopFit(fitId) }
            }
            if (!serviceDestroyed) {
                serviceController.destroy()
            }
        }
    }

    private fun assertNewSettingsApplyImmediately(endurance: Float) {
        configureZones(
            endurance = endurance,
            vt1 = endurance + 10f,
            vt2 = endurance + 20f,
            topZ4 = endurance + 30f,
            vo2max = endurance + 40f,
        )
        TymewearData.loadThresholds(application)
        assertEquals(endurance.toDouble(), TymewearData.thresholds.endurance, 0.0)
    }

    private fun createExtensionService(
        metadataSettleDurationMs: Long,
    ): ServiceController<TymewearExtension> =
        Robolectric.buildService(TymewearExtension::class.java).create().also { controller ->
            controller.get().fitMetadataSettleDurationMs = metadataSettleDurationMs
        }

    private fun configureZones(
        endurance: Float = 10f,
        vt1: Float = 20f,
        vt2: Float = 30f,
        topZ4: Float = 40f,
        vo2max: Float = 50f,
    ) {
        application.getSharedPreferences(PREFS_NAME, 0).edit()
            .putFloat("endurance_threshold", endurance)
            .putFloat("vt1_threshold", vt1)
            .putFloat("vt2_threshold", vt2)
            .putFloat("topz4_threshold", topZ4)
            .putFloat("vo2max_threshold", vo2max)
            .commit()
    }

    private fun sampleBreath(minuteVolume: Double = 25.0) = Protocol.BreathingData(
        breathRate = 20.0,
        tidalVolume = 1.25,
        minuteVolume = minuteVolume,
        ieRatio = 0.5,
        veZone = 3,
        inhaleDurationCs = 100,
        exhaleDurationCs = 200,
        tvRaw = 125,
        fieldE = 12,
        timestamp40ms = 1_000L,
    )

    private fun assertRecordFields(record: WriteToRecordMesg) {
        val values = record.values.associate { field ->
            requireNotNull(field.developerField).fieldName to field.value
        }
        assertEquals(
            setOf(
                "tyme_breath_rate",
                "tyme_tidal_volume",
                "tyme_minute_volume",
                "vitalbreathe_ve_30s",
                "tyme_inhale_exhale_ratio",
                "tyme_ve_zone",
            ),
            values.keys,
        )
        assertEquals(20.0, values.getValue("tyme_breath_rate"), 0.0)
        assertEquals(1.25, values.getValue("tyme_tidal_volume"), 0.0)
        assertEquals(25.0, values.getValue("tyme_minute_volume"), 0.0)
        assertEquals(25.0, values.getValue("vitalbreathe_ve_30s"), 0.0)
        assertEquals(0.5, values.getValue("tyme_inhale_exhale_ratio"), 0.0)
        assertEquals(3.0, values.getValue("tyme_ve_zone"), 0.0)
    }

    private fun assertSessionSummary(summary: WriteToSessionMesg) {
        val values = summary.values.associate { field ->
            requireNotNull(field.developerField).fieldName to field.value
        }
        assertEquals(2.0 / 60.0, values.getValue("tyme_ve_zone3_time"), 0.000_001)
        assertEquals(100.0, values.getValue("tyme_ve_zone3_percentage"), 0.0)
    }

    private fun assertThresholdSnapshot(snapshot: WriteToSessionMesg) {
        val values = snapshot.values.associate { field ->
            requireNotNull(field.developerField).fieldName to field.value
        }
        assertEquals(
            setOf(
                "vitalbreathe_ve_endurance",
                "vitalbreathe_ve_vt1",
                "vitalbreathe_ve_vt2",
                "vitalbreathe_ve_top_z4",
                "vitalbreathe_ve_vo2max",
            ),
            values.keys,
        )
        assertEquals(10.0, values.getValue("vitalbreathe_ve_endurance"), 0.0)
        assertEquals(20.0, values.getValue("vitalbreathe_ve_vt1"), 0.0)
        assertEquals(30.0, values.getValue("vitalbreathe_ve_vt2"), 0.0)
        assertEquals(40.0, values.getValue("vitalbreathe_ve_top_z4"), 0.0)
        assertEquals(50.0, values.getValue("vitalbreathe_ve_vo2max"), 0.0)
    }

    private fun ShadowApplication.boundKarooConnection(): ServiceConnection =
        boundServiceConnections.single()

    private fun elapsedEvent(value: Double): Bundle = OnStreamState(
        StreamState.Streaming(
            DataPoint(
                dataTypeId = DataType.Type.ELAPSED_TIME,
                values = mapOf(DataType.Field.SINGLE to value),
            ),
        ),
    ).bundleWithSerializable(HOST_PACKAGE)

    private fun karooEventBundle(event: KarooEvent): Bundle =
        event.bundleWithSerializable(HOST_PACKAGE)

    private fun awaitCondition(
        description: String,
        timeoutMs: Long = 4_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        assertTrue("Timed out waiting for $description", condition())
    }

    private fun assertRemains(
        failureMessage: String,
        durationMs: Long = 200L,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + durationMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            assertTrue(failureMessage, condition())
            Thread.sleep(10L)
        }
    }

    private class CapturingFitHandler : IHandler.Stub() {
        private val effects = CopyOnWriteArrayList<FitEffect>()

        override fun onNext(bundle: Bundle) {
            effects += requireNotNull(bundle.serializableFromBundle<FitEffect>())
        }

        override fun onError(msg: String) {
            throw AssertionError("Unexpected FIT emitter error: $msg")
        }

        override fun onComplete() = Unit

        fun recordMessages(): List<WriteToRecordMesg> = effects.filterIsInstance<WriteToRecordMesg>()

        fun sessionMessages(): List<WriteToSessionMesg> = effects.filterIsInstance<WriteToSessionMesg>()

        fun sessionMessagesContaining(fieldName: String): List<WriteToSessionMesg> =
            sessionMessages().filter { message ->
                message.values.any { it.developerField?.fieldName == fieldName }
            }

        fun allEffects(): List<FitEffect> = effects.toList()
    }

    private class BlockingRecordFitHandler : IHandler.Stub() {
        private val effects = CopyOnWriteArrayList<FitEffect>()
        private val firstRecordEntered = CountDownLatch(1)
        private val releaseFirstRecordLatch = CountDownLatch(1)

        override fun onNext(bundle: Bundle) {
            val effect = requireNotNull(bundle.serializableFromBundle<FitEffect>())
            effects += effect
            if (effect is WriteToRecordMesg && firstRecordEntered.count == 1L) {
                firstRecordEntered.countDown()
                assertTrue(releaseFirstRecordLatch.await(4, TimeUnit.SECONDS))
            }
        }

        override fun onError(msg: String) {
            throw AssertionError("Unexpected FIT emitter error: $msg")
        }

        override fun onComplete() = Unit

        fun awaitFirstRecord(): Boolean = firstRecordEntered.await(4, TimeUnit.SECONDS)

        fun releaseFirstRecord() {
            releaseFirstRecordLatch.countDown()
        }

        fun recordMessages(): List<WriteToRecordMesg> = effects.filterIsInstance<WriteToRecordMesg>()

        fun recordValue(record: WriteToRecordMesg, fieldName: String): Double =
            record.values.single { it.developerField?.fieldName == fieldName }.value
    }

    private class ThrowingFitHandler : IHandler.Stub() {
        override fun onNext(bundle: Bundle) {
            throw IllegalStateException("simulated FIT writer rejection")
        }

        override fun onError(msg: String) = Unit

        override fun onComplete() = Unit
    }

    private enum class FitWriteFailure {
        RECORD,
        SUMMARY,
    }

    private class FailingAfterStartFitHandler(
        private val failure: FitWriteFailure,
    ) : IHandler.Stub() {
        private val effects = CopyOnWriteArrayList<FitEffect>()
        private val failureTriggered = CountDownLatch(1)
        private val failures = AtomicLong(0L)

        override fun onNext(bundle: Bundle) {
            val effect = requireNotNull(bundle.serializableFromBundle<FitEffect>())
            val isZoneSummary = effect is WriteToSessionMesg && effect.values.any {
                it.developerField?.fieldName == "tyme_ve_zone1_time"
            }
            val shouldFail = when (failure) {
                FitWriteFailure.RECORD -> effect is WriteToRecordMesg
                FitWriteFailure.SUMMARY -> isZoneSummary
            }
            if (shouldFail) {
                failures.incrementAndGet()
                failureTriggered.countDown()
                throw IllegalStateException("simulated post-start FIT writer rejection")
            }
            effects += effect
        }

        override fun onError(msg: String) = Unit

        override fun onComplete() = Unit

        fun awaitFailure(): Boolean = failureTriggered.await(4, TimeUnit.SECONDS)

        fun failureCount(): Long = failures.get()

        fun recordMessages(): List<WriteToRecordMesg> = effects.filterIsInstance<WriteToRecordMesg>()
    }

    private class FakeKarooSystem : IKarooSystem.Stub() {
        data class Consumer(
            val id: String,
            val params: KarooEventParams,
            val handler: IHandler,
            val sequence: Long,
        )

        val effects = CopyOnWriteArrayList<KarooEffect>()
        private val consumers = ConcurrentHashMap<String, Consumer>()
        private val sequence = AtomicLong(0L)

        override fun libVersion(): String = "test-karoo-system"

        override fun info(): Bundle = Bundle()

        override fun dispatchEffect(bundle: Bundle) {
            effects += requireNotNull(bundle.serializableFromBundle<KarooEffect>())
        }

        override fun addEventConsumer(id: String, bundle: Bundle, handler: IHandler) {
            val params = requireNotNull(bundle.serializableFromBundle<KarooEventParams>())
            consumers[id] = Consumer(id, params, handler, sequence.incrementAndGet())
        }

        override fun removeEventConsumer(id: String) {
            consumers.remove(id)
        }

        fun activeConsumers(): List<Consumer> = consumers.values.toList()

        fun consumerIds(): Set<String> = consumers.keys.toSet()

        fun emit(consumer: Consumer, event: KarooEvent) {
            consumer.handler.onNext(event.bundleWithSerializable(HOST_PACKAGE))
        }

        fun emitElapsed(consumer: Consumer, value: Double) {
            consumer.handler.onNext(
                OnStreamState(
                    StreamState.Streaming(
                        DataPoint(
                            dataTypeId = DataType.Type.ELAPSED_TIME,
                            values = mapOf(DataType.Field.SINGLE to value),
                        ),
                    ),
                ).bundleWithSerializable(HOST_PACKAGE),
            )
        }

        fun awaitNewStreamConsumer(dataTypeId: String, excludedIds: Set<String>): Consumer =
            awaitConsumer("new $dataTypeId consumer") { consumer ->
                consumer.id !in excludedIds &&
                    (consumer.params as? OnStreamState.StartStreaming)?.dataTypeId == dataTypeId
            }

        fun awaitNewRideConsumer(excludedIds: Set<String>): Consumer =
            awaitConsumer("new FIT ride-state consumer") { consumer ->
                consumer.id !in excludedIds && consumer.params is RideState.Params
            }

        fun awaitReplacementStreamConsumer(dataTypeId: String, oldId: String): Consumer =
            awaitConsumer("replacement $dataTypeId consumer") { consumer ->
                consumer.id != oldId &&
                    (consumer.params as? OnStreamState.StartStreaming)?.dataTypeId == dataTypeId
            }

        fun awaitReplacementRideConsumers(excludedIds: Set<String>): List<Consumer> {
            val deadline = System.nanoTime() + 4_000_000_000L
            while (System.nanoTime() < deadline) {
                val replacements = activeConsumers().filter { consumer ->
                    consumer.id !in excludedIds && consumer.params is RideState.Params
                }
                if (replacements.size >= 2) return replacements
                Thread.sleep(10L)
            }
            throw AssertionError(
                "Timed out waiting for replacement ride-state consumers; active=${activeConsumers()}",
            )
        }

        private fun awaitConsumer(description: String, predicate: (Consumer) -> Boolean): Consumer {
            val deadline = System.nanoTime() + 4_000_000_000L
            while (System.nanoTime() < deadline) {
                activeConsumers().firstOrNull(predicate)?.let { return it }
                Thread.sleep(10L)
            }
            throw AssertionError("Timed out waiting for $description; active=${activeConsumers()}")
        }
    }

    private companion object {
        const val PREFS_NAME = "tymewear_prefs"
        const val FIT_ID = "fit-runtime-test"
        const val HOST_PACKAGE = "io.hammerhead.appstore"
        val KAROO_COMPONENT: ComponentName = ComponentName.createRelative(
            HOST_PACKAGE,
            ".service.AppStoreService",
        )
    }
}

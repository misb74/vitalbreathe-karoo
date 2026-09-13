package com.tymewear.karoo

import android.content.Context
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import kotlin.math.roundToInt

/** At-a-glance ride field inspired by Tymewear's full Garmin data screen. */
@OptIn(FlowPreview::class)
class VitalDashboardDataType(extension: String) : DataTypeImpl(extension, "dashboard") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val layoutVariant = dashboardLayoutFor(config.viewSize)
        val textSizes = dashboardTextSizes(config.textSize, layoutVariant)
        val layoutResource = when (layoutVariant) {
            DashboardLayoutVariant.COMPACT -> R.layout.view_vital_dashboard_compact
            DashboardLayoutVariant.FULL -> R.layout.view_vital_dashboard
        }
        Timber.d(
            "Dashboard startView: viewSize=%dx%d textSize=%d variant=%s",
            config.viewSize.first,
            config.viewSize.second,
            config.textSize,
            layoutVariant,
        )

        scope.launch {
            combine(
                TymewearData.presentationSnapshot,
                TymewearData.thresholdsState,
            ) { snapshot, thresholds -> snapshot to thresholds }
                .sample(1_000L)
                .collect { (snapshot, thresholds) ->
                    val view = RemoteViews(context.packageName, layoutResource)
                    val ve = snapshot?.smoothMinuteVolume ?: 0.0
                    val br = snapshot?.smoothBreathRate ?: 0.0
                    val tv = snapshot?.smoothTidalVolume ?: 0.0
                    val zone = snapshot?.veZone ?: 0
                    val hr = snapshot?.heartRate ?: 0.0
                    val battery = snapshot?.batteryPercent ?: -1

                    view.setTextViewText(
                        R.id.dashboard_ve,
                        if (ve > 0.0) String.format(Locale.US, "%.1f", ve) else "—",
                    )
                    view.setTextViewText(
                        R.id.dashboard_zone,
                        dashboardZoneLabel(snapshot?.veZone, thresholds.isConfigured),
                    )
                    view.setTextViewText(
                        R.id.dashboard_br,
                        if (br > 0.0) "BR ${br.roundToInt()}" else "BR —",
                    )
                    view.setTextViewText(
                        R.id.dashboard_tv,
                        if (tv > 0.0) String.format(Locale.US, "TV %.2f L", tv) else "TV —",
                    )
                    view.setTextViewText(
                        R.id.dashboard_hr,
                        if (hr > 0.0) "HR ${hr.roundToInt()}" else "HR —",
                    )
                    view.setTextViewText(
                        R.id.dashboard_battery,
                        if (battery >= 0) "BAT $battery%" else "BAT —",
                    )
                    view.setFloat(R.id.dashboard_ve, "setTextSize", textSizes.valueSp)
                    listOf(
                        R.id.dashboard_unit,
                        R.id.dashboard_zone,
                    ).forEach { id -> view.setFloat(id, "setTextSize", textSizes.metaSp) }
                    listOf(
                        R.id.dashboard_br,
                        R.id.dashboard_tv,
                        R.id.dashboard_hr,
                        R.id.dashboard_battery,
                    ).forEach { id -> view.setFloat(id, "setTextSize", textSizes.detailSp) }
                    view.setInt(
                        R.id.dashboard_container,
                        "setBackgroundColor",
                        Constants.zoneStyle(zone).second,
                    )
                    emitter.updateView(view)
                }
        }

        emitter.setCancellable { scope.cancel() }
    }
}

internal enum class DashboardLayoutVariant {
    COMPACT,
    FULL,
}

internal data class DashboardTextSizes(
    val valueSp: Float,
    val metaSp: Float,
    val detailSp: Float,
)

/**
 * A half-height field on a 480 x 800 Karoo reports about 480 x 415 pixels before
 * the ride-screen title is applied. Narrow fields need the same stacked layout
 * even when they are tall, because four live readings do not fit safely in one row.
 */
internal fun dashboardLayoutFor(viewSize: Pair<Int, Int>): DashboardLayoutVariant {
    val (width, height) = viewSize
    return if (width < 400 || height < 500) {
        DashboardLayoutVariant.COMPACT
    } else {
        DashboardLayoutVariant.FULL
    }
}

/** Cap Karoo's single-value font recommendation for this multi-value dashboard. */
internal fun dashboardTextSizes(
    recommendedTextSize: Int,
    layout: DashboardLayoutVariant,
): DashboardTextSizes {
    val base = recommendedTextSize.coerceAtLeast(1).toFloat()
    return when (layout) {
        DashboardLayoutVariant.COMPACT -> DashboardTextSizes(
            valueSp = (base * 0.45f).coerceIn(12f, 42f),
            metaSp = (base * 0.20f).coerceIn(10f, 18f),
            detailSp = (base * 0.20f).coerceIn(10f, 18f),
        )

        DashboardLayoutVariant.FULL -> DashboardTextSizes(
            valueSp = (base * 0.52f).coerceIn(14f, 52f),
            metaSp = (base * 0.22f).coerceIn(11f, 18f),
            detailSp = (base * 0.22f).coerceIn(11f, 22f),
        )
    }
}

internal fun dashboardZoneLabel(zone: Int?, zonesConfigured: Boolean): String = when {
    zone == null -> "—"
    !zonesConfigured -> "zones off"
    zone in 1..5 -> "Z$zone"
    else -> "—"
}

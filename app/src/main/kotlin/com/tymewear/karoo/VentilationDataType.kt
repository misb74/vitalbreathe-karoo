package com.tymewear.karoo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Graphical data type showing minute ventilation (VE) with rolling average
 * and color-coded background based on ventilation zones.
 * Tap to cycle true time-based smoothing: live -> 5s -> 15s -> 30s -> 60s.
 */
@OptIn(FlowPreview::class)
class VentilationDataType(extension: String) : DataTypeImpl(extension, "ve") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)

        val tapIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, SmoothingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val valueSize = config.textSize * 0.6f
        val unitSize = config.textSize * 0.25f

        scope.launch {
            combine(
                TymewearData.ventilationAverages,
                SmoothingState.mode,
                TymewearData.thresholdsState,
            ) { averages, mode, thresholds -> Triple(averages, mode, thresholds) }
                .sample(1000L)
                .collect { (averages, mode, thresholds) ->
                    val avg = averages.valueFor(mode.windowMs)
                    // The number and its background always use the same signal.
                    val zone = thresholds.zone(avg)

                    val remoteViews = RemoteViews(context.packageName, R.layout.view_ventilation)

                    // Set VE value text
                    val displayValue = if (avg > 0) String.format(Locale.US, "%.1f", avg) else "—"
                    remoteViews.setTextViewText(R.id.text_value, displayValue)
                    remoteViews.setFloat(R.id.text_value, "setTextSize", valueSize)
                    remoteViews.setFloat(R.id.text_unit, "setTextSize", unitSize)

                    // Set zone background color
                    val (_, bgColor) = Constants.zoneStyle(zone)
                    remoteViews.setInt(R.id.container, "setBackgroundColor", bgColor)

                    // Unit label shows smoothing mode
                    remoteViews.setTextViewText(R.id.text_unit, mode.label)

                    // Tap to cycle smoothing
                    remoteViews.setOnClickPendingIntent(R.id.container, tapIntent)

                    emitter.updateView(remoteViews)
                }
        }

        emitter.setCancellable {
            scope.cancel()
        }
    }

    companion object {
        fun zoneStyle(zone: Int): Pair<String, Int> = Constants.zoneStyle(zone)
    }
}

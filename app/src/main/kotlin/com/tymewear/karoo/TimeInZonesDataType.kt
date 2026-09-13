package com.tymewear.karoo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimeInZonesDataType(extension: String) : DataTypeImpl(extension, "ve_zones") {

    companion object {
        private const val PADDING = 4f
        private const val NUM_ZONES = 5
        private const val MIN_BAR_HEIGHT = 4f
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val bitmapWidth = config.viewSize.first.coerceIn(100, 1000)
        val bitmapHeight = config.viewSize.second.coerceIn(80, 1000)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        scope.launch {
            val tracker = ZoneDisplayTracker()

            while (true) {
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val frame = tracker.next(
                    recordingState = TymewearData.zoneRecordingState.value,
                    recordedZoneTimes = TymewearData.zoneTimes.value,
                    freshLiveZone = TymewearData.freshRecordingSnapshot(nowElapsedMs)?.veZone,
                    nowElapsedMs = nowElapsedMs,
                )

                renderBars(bitmap, frame)
                val remoteViews = RemoteViews(context.packageName, R.layout.view_time_in_zones)
                remoteViews.setImageViewBitmap(R.id.zones_image, bitmap)
                emitter.updateView(remoteViews)
                delay(1000)
            }
        }
        emitter.setCancellable {
            scope.cancel()
        }
    }

    private fun renderBars(bitmap: Bitmap, frame: ZoneDisplayFrame) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val labelSpace = 50f
        val usableWidth = bitmap.width - 2 * PADDING
        val chartBottom = bitmap.height - PADDING - labelSpace
        val usableHeight = chartBottom - PADDING
        val barWidth = usableWidth / NUM_ZONES
        val total = frame.zoneTimes.total

        val paint = Paint().apply { style = Paint.Style.FILL }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 22f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val modePaint = Paint(textPaint).apply {
            textSize = 16f
        }

        canvas.drawText(
            frame.mode.label,
            bitmap.width / 2f,
            chartBottom + 18f,
            modePaint,
        )

        for (zone in 1..NUM_ZONES) {
            val time = frame.zoneTimes[zone]
            val pct = if (total > 0) (time.toFloat() / total * 100f) else 0f
            val scaled = if (total > 0) (time.toFloat() / total) * usableHeight else 0f
            val barHeight = if (time > 0) maxOf(scaled, MIN_BAR_HEIGHT) else 0f
            val x = PADDING + (zone - 1) * barWidth
            val top = chartBottom - barHeight
            val bottom = chartBottom

            paint.color = Constants.zoneStyle(zone).second
            canvas.drawRect(x, top, x + barWidth, bottom, paint)

            // Draw percentage label above bar, pre-stretched vertically
            // to compensate for fitXY squashing on wide display fields
            val label = "${pct.toInt()}%"
            val centerX = x + barWidth / 2
            canvas.save()
            canvas.scale(1f, 1.7f, centerX, top - 4f)
            canvas.drawText(label, centerX, top - 4f, textPaint)
            canvas.restore()

            canvas.drawText("Z$zone", centerX, bitmap.height - PADDING, textPaint)
        }
    }
}

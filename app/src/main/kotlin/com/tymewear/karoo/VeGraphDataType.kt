package com.tymewear.karoo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import kotlin.math.ceil

/**
 * Five-minute minute-ventilation graph. Tap anywhere in the field to cycle the
 * 15, 30 and 60 second averages.
 */
class VeGraphDataType(extension: String) : DataTypeImpl(extension, "ve_graph") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + Constants.coroutineExceptionHandler)
        val bitmapWidth = config.viewSize.first.coerceIn(100, 1000)
        val bitmapHeight = config.viewSize.second.coerceIn(80, 1000)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        Timber.d(
            "VE Graph startView: viewSize=%dx%d bitmap=%dx%d",
            config.viewSize.first,
            config.viewSize.second,
            bitmapWidth,
            bitmapHeight,
        )

        val tapIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, GraphSmoothingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        scope.launch {
            while (true) {
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val mode = GraphSmoothingState.mode.value
                val smoothed = TimedRollingAverage.rollingAverages(
                    TymewearData.ventilationHistory.value,
                    mode.windowMs,
                )
                val latestBreath = TymewearData.latestBreath.value
                val hasFreshBreath = TymewearData.isConnected.value && latestBreath?.let {
                    RecordingFreshnessPolicy.isFresh(
                        sampleElapsedMs = it.elapsedRealtimeMs,
                        nowElapsedMs = nowElapsedMs,
                        breathRate = it.data.breathRate,
                    )
                } == true
                val rendered = renderVeGraph(
                    bitmap = bitmap,
                    points = smoothed,
                    thresholds = TymewearData.thresholdsState.value,
                    mode = mode,
                    nowElapsedMs = nowElapsedMs,
                    hasFreshBreath = hasFreshBreath,
                )

                val remoteViews = RemoteViews(context.packageName, R.layout.view_ve_graph)
                remoteViews.setImageViewBitmap(R.id.ve_graph_image, bitmap)
                val contentDescription = veGraphContentDescription(rendered.header)
                remoteViews.setContentDescription(R.id.ve_graph_image, contentDescription)
                remoteViews.setContentDescription(R.id.ve_graph_container, contentDescription)
                remoteViews.setOnClickPendingIntent(R.id.ve_graph_container, tapIntent)
                emitter.updateView(remoteViews)
                delay(1_000L)
            }
        }

        emitter.setCancellable { scope.cancel() }
    }
}

internal const val VE_GRAPH_HISTORY_MS = 5 * 60_000L
internal const val VE_GRAPH_REFERENCE_WIDTH = 478f
internal const val VE_GRAPH_REFERENCE_HEIGHT = 642f
internal val VE_GRAPH_BACKGROUND_COLOR: Int = Color.rgb(5, 8, 10)
internal val VE_GRAPH_PLOT_COLOR: Int = Color.rgb(10, 15, 18)
internal val VE_GRAPH_TRACE_COLOR: Int = Color.rgb(123, 231, 201)

internal data class VeGraphLayout(
    val scale: Float,
    val plotLeft: Float,
    val plotTop: Float,
    val plotRight: Float,
    val plotBottom: Float,
    val latestDotOuterRadius: Float,
) {
    val plotWidth: Float
        get() = plotRight - plotLeft

    val plotHeight: Float
        get() = plotBottom - plotTop
}

internal data class VeGraphHeader(
    val modeLabel: String,
    val currentValue: String,
    val historyLabel: String,
    val interactionLabel: String,
    val zonesLabel: String,
)

internal data class VeGraphRenderResult(
    val layout: VeGraphLayout,
    val yMax: Double,
    val plottedPointCount: Int,
    val plottedSegmentCount: Int,
    val latestPointX: Float?,
    val latestPointY: Float?,
    val header: VeGraphHeader,
)

/** Keep chart furniture proportional while retaining safe margins on smaller fields. */
internal fun veGraphLayout(width: Int, height: Int): VeGraphLayout {
    require(width > 0 && height > 0)
    val scale = minOf(
        width / VE_GRAPH_REFERENCE_WIDTH,
        height / VE_GRAPH_REFERENCE_HEIGHT,
    ).coerceIn(0.55f, 1.6f)
    val headerBottom = minOf(148f * scale, height * 0.42f)
    val bottomLabelSpace = minOf(46f * scale, height * 0.20f)
    val leftLabelSpace = minOf(70f * scale, width * 0.25f)
    val rightInset = 18f * scale
    val dotRadius = 9f * scale
    val plotTop = headerBottom + 34f * scale
    val plotBottom = maxOf(plotTop + 20f * scale, height - bottomLabelSpace)
        .coerceAtMost(height - 4f * scale)
    val plotRight = maxOf(leftLabelSpace + 20f * scale, width - rightInset)
        .coerceAtMost(width - dotRadius - 1f)

    return VeGraphLayout(
        scale = scale,
        plotLeft = leftLabelSpace,
        plotTop = plotTop,
        plotRight = plotRight,
        plotBottom = plotBottom,
        latestDotOuterRadius = dotRadius,
    )
}

internal fun veGraphHeader(
    points: List<TimedSample>,
    mode: GraphSmoothingMode,
    thresholds: VentilationThresholds,
    hasFreshBreath: Boolean = points.isNotEmpty(),
): VeGraphHeader {
    val current = points.lastOrNull { it.value.isFinite() && it.value > 0.0 }?.value
    return VeGraphHeader(
        modeLabel = "VE / ${mode.label} AVG",
        currentValue = if (hasFreshBreath) {
            current?.let { String.format(Locale.US, "%.1f L/min", it) } ?: "Waiting for breath"
        } else {
            "Waiting for breath"
        },
        historyLabel = "5 MIN HISTORY",
        interactionLabel = "TAP: 15 > 30 > 60s",
        zonesLabel = if (thresholds.isConfigured) "ZONE BANDS ON" else "ZONES OFF",
    )
}

internal fun veGraphContentDescription(header: VeGraphHeader): String {
    val modeSeconds = header.modeLabel.substringAfter("VE / ").substringBefore("s")
    val current = if (header.currentValue == "Waiting for breath") {
        "waiting for a fresh breath"
    } else {
        header.currentValue.replace("L/min", "litres per minute")
    }
    val zones = if (header.zonesLabel == "ZONE BANDS ON") {
        "ventilation zone bands on"
    } else {
        "ventilation zones off"
    }
    return "Minute ventilation, $modeSeconds second average, $current, " +
        "five minute history, $zones. Tap to change smoothing."
}

/**
 * Renders at the exact pixel dimensions Karoo requests. The plot has explicit
 * margins for axis labels and the latest point, so fitXY does not crop either.
 */
internal fun renderVeGraph(
    bitmap: Bitmap,
    points: List<TimedSample>,
    thresholds: VentilationThresholds,
    mode: GraphSmoothingMode,
    nowElapsedMs: Long = points.maxOfOrNull { it.timestampMs } ?: 0L,
    hasFreshBreath: Boolean = points.isNotEmpty(),
): VeGraphRenderResult {
    val canvas = Canvas(bitmap)
    val layout = veGraphLayout(bitmap.width, bitmap.height)
    val scale = layout.scale
    canvas.drawColor(VE_GRAPH_BACKGROUND_COLOR)

    val orderedPoints = points
        .asSequence()
        .filter { it.timestampMs >= 0L && it.value.isFinite() && it.value > 0.0 }
        .sortedBy { it.timestampMs }
        .toList()
    val visiblePoints = orderedPoints.filter {
        nowElapsedMs - it.timestampMs in 0L..VE_GRAPH_HISTORY_MS
    }
    val header = veGraphHeader(visiblePoints, mode, thresholds, hasFreshBreath)

    drawVeGraphHeader(canvas, bitmap.width, header, scale)

    val plot = RectF(
        layout.plotLeft,
        layout.plotTop,
        layout.plotRight,
        layout.plotBottom,
    )
    canvas.drawRect(
        plot,
        Paint().apply {
            color = VE_GRAPH_PLOT_COLOR
            style = Paint.Style.FILL
        },
    )

    val maxData = visiblePoints.maxOfOrNull { it.value } ?: 0.0
    val scaleReference = maxOf(
        50.0,
        maxData * 1.12,
        if (thresholds.isConfigured) thresholds.vo2max * 1.08 else 0.0,
    )
    val yMax = ceil(scaleReference / 10.0) * 10.0

    if (thresholds.isConfigured) {
        drawVeGraphZoneBands(canvas, plot, thresholds, yMax, scale)
    }
    drawVeGraphGridAndAxes(canvas, bitmap.height, plot, yMax, scale)

    val latestCoordinates = if (visiblePoints.isNotEmpty()) {
        drawVeGraphTrace(canvas, plot, visiblePoints, nowElapsedMs, yMax, scale)
    } else {
        drawVeGraphEmptyState(canvas, plot, scale)
        null
    }

    return VeGraphRenderResult(
        layout = layout,
        yMax = yMax,
        plottedPointCount = visiblePoints.size,
        plottedSegmentCount = visiblePoints
            .zipWithNext()
            .count { (first, second) -> first.segmentId != second.segmentId }
            .let { changes -> if (visiblePoints.isEmpty()) 0 else changes + 1 },
        latestPointX = latestCoordinates?.first,
        latestPointY = latestCoordinates?.second,
        header = header,
    )
}

private fun drawVeGraphHeader(
    canvas: Canvas,
    width: Int,
    header: VeGraphHeader,
    scale: Float,
) {
    val left = 16f * scale
    val right = width - 16f * scale
    val modePaint = graphTextPaint(30f * scale, VE_GRAPH_TRACE_COLOR, true)
    canvas.drawText(header.modeLabel, left, 35f * scale, modePaint)

    val valuePaint = graphTextPaint(
        if (header.currentValue == "Waiting for breath") 42f * scale else 62f * scale,
        Color.WHITE,
        true,
    )
    canvas.drawText(header.currentValue, left, 103f * scale, valuePaint)

    val zonePaint = graphTextPaint(28f * scale, Color.rgb(255, 193, 92), true).apply {
        textAlign = Paint.Align.RIGHT
    }
    zonePaint.color = if (header.zonesLabel == "ZONE BANDS ON") {
        VE_GRAPH_TRACE_COLOR
    } else {
        Color.rgb(255, 193, 92)
    }
    canvas.drawText(header.zonesLabel, right, 34f * scale, zonePaint)

    val detailPaint = graphTextPaint(25f * scale, Color.rgb(202, 214, 218), true)
    canvas.drawText(header.historyLabel, left, 139f * scale, detailPaint)
    detailPaint.textAlign = Paint.Align.RIGHT
    canvas.drawText(header.interactionLabel, right, 139f * scale, detailPaint)

    canvas.drawLine(
        left,
        148f * scale,
        right,
        148f * scale,
        Paint().apply {
            color = Color.rgb(64, 76, 80)
            strokeWidth = 1f * scale
        },
    )
}

private fun drawVeGraphZoneBands(
    canvas: Canvas,
    plot: RectF,
    thresholds: VentilationThresholds,
    yMax: Double,
    scale: Float,
) {
    val zoneBounds = doubleArrayOf(
        0.0,
        thresholds.endurance,
        thresholds.vt1,
        thresholds.vt2,
        thresholds.topZ4,
        yMax,
    )
    val fillPaint = Paint().apply { style = Paint.Style.FILL }
    val zoneLabelPaint = graphTextPaint(25f * scale, Color.argb(195, 255, 255, 255), true).apply {
        textAlign = Paint.Align.RIGHT
    }

    for (index in 0 until 5) {
        val bottom = yForValue(zoneBounds[index], yMax, plot)
        val top = yForValue(zoneBounds[index + 1], yMax, plot)
        fillPaint.color = Constants.ZONE_COLORS_ALPHA[index]
        canvas.drawRect(plot.left, top, plot.right, bottom, fillPaint)

        val labelY = (top + bottom) / 2f -
            (zoneLabelPaint.fontMetrics.ascent + zoneLabelPaint.fontMetrics.descent) / 2f
        if (bottom - top >= 29f * scale) {
            canvas.drawText("Z${index + 1}", plot.right - 7f * scale, labelY, zoneLabelPaint)
        }
    }

    val thresholdPaint = Paint().apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 1f * scale
        style = Paint.Style.STROKE
    }
    val thresholdLabelPaint = graphTextPaint(
        22f * scale,
        Color.argb(210, 255, 255, 255),
        true,
    )
    val labels = arrayOf("END", "VT1", "VT2", "TOP Z4", "VO2")
    val values = doubleArrayOf(
        thresholds.endurance,
        thresholds.vt1,
        thresholds.vt2,
        thresholds.topZ4,
        thresholds.vo2max,
    )
    values.forEachIndexed { index, value ->
        val y = yForValue(value, yMax, plot)
        canvas.drawLine(plot.left, y, plot.right, y, thresholdPaint)
        canvas.drawText(labels[index], plot.left + 5f * scale, y - 4f * scale, thresholdLabelPaint)
    }
}

private fun drawVeGraphGridAndAxes(
    canvas: Canvas,
    height: Int,
    plot: RectF,
    yMax: Double,
    scale: Float,
) {
    val gridPaint = Paint().apply {
        color = Color.argb(90, 170, 190, 197)
        strokeWidth = 1f * scale
        style = Paint.Style.STROKE
    }
    val borderPaint = Paint(gridPaint).apply {
        color = Color.argb(170, 190, 210, 216)
    }
    val axisPaint = graphTextPaint(28f * scale, Color.rgb(225, 233, 236), true)

    for (tick in 0..4) {
        val fraction = tick / 4f
        val y = plot.bottom - fraction * plot.height()
        canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
        axisPaint.textAlign = Paint.Align.RIGHT
        val labelY = y - (axisPaint.fontMetrics.ascent + axisPaint.fontMetrics.descent) / 2f
        canvas.drawText(
            formatVeAxisValue(yMax * fraction),
            plot.left - 7f * scale,
            labelY,
            axisPaint,
        )
    }

    for (tick in 0..5) {
        val fraction = tick / 5f
        val x = plot.left + fraction * plot.width()
        canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
        val label = veGraphTimeLabel(tick)
        if (label != null) {
            axisPaint.textAlign = when (tick) {
                0 -> Paint.Align.LEFT
                5 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(label, x, height - 10f * scale, axisPaint)
        }
    }

    axisPaint.textAlign = Paint.Align.LEFT
    canvas.drawText("L/min", plot.left, plot.top - 8f * scale, axisPaint)
    canvas.drawRect(plot, borderPaint)
}

private fun drawVeGraphTrace(
    canvas: Canvas,
    plot: RectF,
    points: List<TimedSample>,
    nowElapsedMs: Long,
    yMax: Double,
    scale: Float,
): Pair<Float, Float> {
    val path = Path()
    var latestX = plot.right
    var latestY = plot.bottom

    var previousSegmentId: Long? = null
    points.forEach { point ->
        val ageMs = (nowElapsedMs - point.timestampMs).coerceIn(0L, VE_GRAPH_HISTORY_MS)
        val x = plot.right - ageMs.toFloat() / VE_GRAPH_HISTORY_MS * plot.width()
        val y = yForVisibleTrace(point.value, yMax, plot, 10f * scale)
        if (previousSegmentId == null || point.segmentId != previousSegmentId) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
        previousSegmentId = point.segmentId
        latestX = x
        latestY = y
    }

    if (points.size >= 2) {
        canvas.drawPath(
            path,
            Paint().apply {
                color = Color.argb(210, 0, 0, 0)
                strokeWidth = 8f * scale
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            },
        )
        canvas.drawPath(
            path,
            Paint().apply {
                color = VE_GRAPH_TRACE_COLOR
                strokeWidth = 6f * scale
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            },
        )
    }

    canvas.drawCircle(
        latestX,
        latestY,
        9f * scale,
        Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = true
        },
    )
    canvas.drawCircle(
        latestX,
        latestY,
        6.5f * scale,
        Paint().apply {
            color = VE_GRAPH_TRACE_COLOR
            style = Paint.Style.FILL
            isAntiAlias = true
        },
    )
    return latestX to latestY
}

private fun drawVeGraphEmptyState(canvas: Canvas, plot: RectF, scale: Float) {
    val centerX = plot.centerX()
    val centerY = plot.centerY()
    val titlePaint = graphTextPaint(34f * scale, Color.WHITE, true).apply {
        textAlign = Paint.Align.CENTER
    }
    val detailPaint = graphTextPaint(27f * scale, Color.rgb(195, 207, 211), false).apply {
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText("Waiting for VE data", centerX, centerY - 5f * scale, titlePaint)
    canvas.drawText("The graph fills as you breathe", centerX, centerY + 34f * scale, detailPaint)
}

private fun graphTextPaint(size: Float, color: Int, bold: Boolean): Paint = Paint().apply {
    this.color = color
    textSize = size
    isAntiAlias = true
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

private fun yForValue(value: Double, yMax: Double, plot: RectF): Float =
    plot.bottom - (value / yMax).toFloat().coerceIn(0f, 1f) * plot.height()

private fun yForVisibleTrace(
    value: Double,
    yMax: Double,
    plot: RectF,
    inset: Float,
): Float = yForValue(value, yMax, plot).coerceIn(plot.top + inset, plot.bottom - inset)

internal fun formatVeAxisValue(value: Double): String =
    if (value % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.1f", value)
    }

internal fun veGraphTimeLabel(tick: Int): String? = when (tick) {
    0 -> "-5m"
    2 -> "-3m"
    5 -> "NOW"
    else -> null
}

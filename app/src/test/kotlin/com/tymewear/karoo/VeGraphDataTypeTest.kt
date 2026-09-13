package com.tymewear.karoo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w256dp-h427dp-300dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VeGraphDataTypeTest {

    private val zonesOff = VentilationThresholds(0.0, 0.0, 0.0, 0.0, 0.0)

    @Test
    fun `full screen Karoo graph reserves readable axes and a safe latest-point margin`() {
        val layout = veGraphLayout(width = 478, height = 642)

        assertTrue(layout.plotLeft >= 40f)
        assertTrue(layout.plotTop >= 175f)
        assertTrue(layout.plotWidth >= 390f)
        assertTrue(layout.plotHeight >= 400f)
        assertTrue(layout.plotRight + layout.latestDotOuterRadius < 478f)
        assertTrue(layout.plotBottom < 642f)
    }

    @Test
    fun `zones-off live graph draws a five-minute trace with an entirely visible latest dot`() {
        val bitmap = karooBitmap()
        val points = listOf(
            TimedSample(0L, 20.0),
            TimedSample(60_000L, 29.0),
            TimedSample(120_000L, 25.0),
            TimedSample(180_000L, 38.0),
            TimedSample(240_000L, 42.0),
            TimedSample(300_000L, 48.0),
        )

        val result = renderVeGraph(bitmap, points, zonesOff, GraphSmoothingMode.SMOOTH_30)
        val latestX = requireNotNull(result.latestPointX)
        val latestY = requireNotNull(result.latestPointY)

        assertEquals(6, result.plottedPointCount)
        assertEquals(60.0, result.yMax, 0.0)
        assertEquals("48.0 L/min", result.header.currentValue)
        assertEquals("VE / 30s AVG", result.header.modeLabel)
        assertEquals("ZONES OFF", result.header.zonesLabel)
        assertTrue(latestX - result.layout.latestDotOuterRadius >= 0f)
        assertTrue(latestX + result.layout.latestDotOuterRadius < bitmap.width)
        assertTrue(latestY - result.layout.latestDotOuterRadius >= 0f)
        assertTrue(latestY + result.layout.latestDotOuterRadius < bitmap.height)
        assertTrue("latest dot is visible", hasTracePixelNear(bitmap, latestX, latestY))
        assertTrue("header text is visible", changedPixels(bitmap, Rect(0, 0, 478, 150)) > 900)
        assertTrue("y labels are visible", changedPixels(bitmap, Rect(0, 175, 62, 600)) > 150)
        assertTrue("time labels are visible", changedPixels(bitmap, Rect(40, 604, 478, 642)) > 150)
        assertTrue("plot is not blank", changedPixels(bitmap, Rect(62, 182, 460, 596)) > 3_000)
    }

    @Test
    fun `one breath still produces a visible current point`() {
        val bitmap = karooBitmap()
        val result = renderVeGraph(
            bitmap,
            listOf(TimedSample(10_000L, 35.0)),
            zonesOff,
            GraphSmoothingMode.SMOOTH_15,
        )

        assertEquals(1, result.plottedPointCount)
        assertEquals("35.0 L/min", result.header.currentValue)
        assertNotNull(result.latestPointX)
        assertTrue(
            hasTracePixelNear(bitmap, result.latestPointX!!, result.latestPointY!!),
        )
    }

    @Test
    fun `empty zones-off graph explains its state instead of showing a blank field`() {
        val bitmap = karooBitmap()
        val result = renderVeGraph(bitmap, emptyList(), zonesOff, GraphSmoothingMode.SMOOTH_60)

        assertEquals(0, result.plottedPointCount)
        assertEquals("Waiting for breath", result.header.currentValue)
        assertEquals("VE / 60s AVG", result.header.modeLabel)
        assertEquals("5 MIN HISTORY", result.header.historyLabel)
        assertEquals("TAP: 15 > 30 > 60s", result.header.interactionLabel)
        assertEquals("ZONES OFF", result.header.zonesLabel)
        val messageArea = Rect(70, 350, 430, 445)
        assertTrue("waiting message is visible", changedPixels(bitmap, messageArea) > 150)
    }

    @Test
    fun `configured thresholds add zone bands without obscuring the live trace`() {
        val bitmap = karooBitmap()
        val thresholds = VentilationThresholds(20.0, 30.0, 40.0, 50.0, 65.0)
        val result = renderVeGraph(
            bitmap,
            listOf(
                TimedSample(0L, 18.0),
                TimedSample(300_000L, 54.0),
            ),
            thresholds,
            GraphSmoothingMode.SMOOTH_30,
        )

        assertEquals("ZONE BANDS ON", result.header.zonesLabel)
        val zoneInterior = bitmap.getPixel(
            result.layout.plotLeft.toInt() + 80,
            result.layout.plotBottom.toInt() - 30,
        )
        assertNotEquals(VE_GRAPH_PLOT_COLOR, zoneInterior)
        assertTrue(
            hasTracePixelNear(bitmap, result.latestPointX!!, result.latestPointY!!),
        )
    }

    @Test
    fun `samples older than the five-minute window do not leak into the plot`() {
        val result = renderVeGraph(
            karooBitmap(),
            listOf(
                TimedSample(0L, 80.0),
                TimedSample(60_001L, 30.0),
                TimedSample(360_001L, 40.0),
            ),
            zonesOff,
            GraphSmoothingMode.SMOOTH_30,
        )

        assertEquals(2, result.plottedPointCount)
        assertEquals(50.0, result.yMax, 0.0)
    }

    @Test
    fun `elapsed time moves the newest sample left and leaves a visible live-data gap`() {
        val result = renderVeGraph(
            karooBitmap(),
            listOf(TimedSample(280_000L, 40.0)),
            zonesOff,
            GraphSmoothingMode.SMOOTH_30,
            nowElapsedMs = 300_000L,
        )

        val expectedGap = result.layout.plotWidth * 20_000f / VE_GRAPH_HISTORY_MS
        val latestPointX = requireNotNull(result.latestPointX)
        assertEquals(result.layout.plotRight - expectedGap, latestPointX, 0.01f)
        assertTrue(latestPointX < result.layout.plotRight)
    }

    @Test
    fun `stale state keeps history but never presents its last point as current`() {
        val result = renderVeGraph(
            karooBitmap(),
            listOf(
                TimedSample(250_000L, 36.0, segmentId = 1L),
                TimedSample(275_000L, 42.0, segmentId = 1L),
            ),
            zonesOff,
            GraphSmoothingMode.SMOOTH_30,
            nowElapsedMs = 280_000L,
            hasFreshBreath = false,
        )

        assertEquals(2, result.plottedPointCount)
        assertNotNull(result.latestPointX)
        assertEquals("Waiting for breath", result.header.currentValue)
    }

    @Test
    fun `reconnect segments remain separate in one retained history`() {
        val bitmap = karooBitmap()
        val result = renderVeGraph(
            bitmap,
            listOf(
                TimedSample(0L, 10.0, segmentId = 1L),
                TimedSample(60_000L, 10.0, segmentId = 1L),
                TimedSample(120_000L, 50.0, segmentId = 2L),
                TimedSample(180_000L, 50.0, segmentId = 2L),
            ),
            zonesOff,
            GraphSmoothingMode.SMOOTH_30,
        )

        assertEquals(2, result.plottedSegmentCount)
        val missingIntervalX = result.layout.plotRight -
            90_000f / VE_GRAPH_HISTORY_MS * result.layout.plotWidth
        val missingIntervalY = result.layout.plotBottom -
            (30.0 / result.yMax).toFloat() * result.layout.plotHeight
        assertFalse(
            "trace must not bridge the missing interval",
            hasTracePixelNear(bitmap, missingIntervalX, missingIntervalY),
        )
    }

    @Test
    fun `all tap-cycle modes have an explicit current smoothing label`() {
        assertEquals(
            listOf("VE / 15s AVG", "VE / 30s AVG", "VE / 60s AVG"),
            GraphSmoothingMode.entries.map { veGraphHeader(emptyList(), it, zonesOff).modeLabel },
        )
    }

    @Test
    fun `accessibility description reports live and waiting graph states`() {
        val live = veGraphHeader(
            listOf(TimedSample(10_000L, 48.0)),
            GraphSmoothingMode.SMOOTH_30,
            zonesOff,
        )
        assertEquals(
            "Minute ventilation, 30 second average, 48.0 litres per minute, " +
                "five minute history, ventilation zones off. Tap to change smoothing.",
            veGraphContentDescription(live),
        )

        val waiting = veGraphHeader(
            emptyList(),
            GraphSmoothingMode.SMOOTH_60,
            VentilationThresholds(20.0, 30.0, 40.0, 50.0, 65.0),
            hasFreshBreath = false,
        )
        assertEquals(
            "Minute ventilation, 60 second average, waiting for a fresh breath, " +
                "five minute history, ventilation zone bands on. Tap to change smoothing.",
            veGraphContentDescription(waiting),
        )
    }

    @Test
    fun `graph layout fills the proven Karoo field without clipping its image`() {
        val root = LayoutInflater.from(RuntimeEnvironment.getApplication())
            .inflate(R.layout.view_ve_graph, null) as ViewGroup
        val image = root.findViewById<ImageView>(R.id.ve_graph_image)
        image.setImageBitmap(karooBitmap())

        root.measure(
            View.MeasureSpec.makeMeasureSpec(478, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(642, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 478, 642)
        val bounds = Rect(0, 0, image.width, image.height)
        root.offsetDescendantRectToMyCoords(image, bounds)

        assertEquals(Rect(0, 0, 478, 642), bounds)
        assertEquals(ImageView.ScaleType.FIT_XY, image.scaleType)
        assertTrue(root.isClickable)
        assertFalse(image.contentDescription.isNullOrBlank())
    }

    @Test
    fun `axis values stay compact and readable`() {
        assertEquals("0", formatVeAxisValue(0.0))
        assertEquals("15", formatVeAxisValue(15.0))
        assertEquals("12.5", formatVeAxisValue(12.5))
        assertEquals(
            listOf("-5m", null, "-3m", null, null, "NOW"),
            (0..5).map(::veGraphTimeLabel),
        )
    }

    private fun karooBitmap(): Bitmap =
        Bitmap.createBitmap(478, 642, Bitmap.Config.ARGB_8888)

    private fun changedPixels(bitmap: Bitmap, area: Rect): Int {
        var count = 0
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel != VE_GRAPH_BACKGROUND_COLOR && pixel != VE_GRAPH_PLOT_COLOR &&
                    Color.alpha(pixel) > 0
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun hasTracePixelNear(bitmap: Bitmap, x: Float, y: Float): Boolean {
        val centerX = x.toInt()
        val centerY = y.toInt()
        for (pixelY in (centerY - 4).coerceAtLeast(0)..(centerY + 4).coerceAtMost(bitmap.height - 1)) {
            for (pixelX in (centerX - 4).coerceAtLeast(0)..(centerX + 4).coerceAtMost(bitmap.width - 1)) {
                if (bitmap.getPixel(pixelX, pixelY) == VE_GRAPH_TRACE_COLOR) return true
            }
        }
        return false
    }
}

package com.tymewear.karoo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Builds privacy-safe public images from the production dashboard layout and
 * graph renderer. To refresh the checked-in PNGs, run:
 *
 * VITALBREATHE_SCREENSHOT_DIR="$PWD/docs/screenshots" \
 * VITALBREATHE_ICON_PATH="$PWD/icon.png" \
 *   ./gradlew testDebugUnitTest \
 *   --tests com.tymewear.karoo.PublicScreenshotGeneratorTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w256dp-h427dp-300dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicScreenshotGeneratorTest {

    @Test
    fun `public screenshots use deterministic synthetic data and current renderers`() {
        val dashboard = renderDashboardScreenshot()
        val graph = renderGraphScreenshot()
        val icon = renderPublicIcon()

        assertEquals(480, dashboard.width)
        assertEquals(800, dashboard.height)
        assertEquals(480, graph.width)
        assertEquals(800, graph.height)
        assertEquals(192, icon.width)
        assertEquals(192, icon.height)
        assertTrue("dashboard has visible content", nonBackgroundPixels(dashboard) > 60_000)
        assertTrue("graph has visible content", nonBackgroundPixels(graph) > 30_000)
        assertEquals("icon corners stay transparent", 0, Color.alpha(icon.getPixel(0, 0)))
        assertTrue("icon has a visible centre mark", Color.alpha(icon.getPixel(96, 96)) > 0)

        val outputDirectory = System.getenv("VITALBREATHE_SCREENSHOT_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: return
        require(outputDirectory.isDirectory) {
            "VITALBREATHE_SCREENSHOT_DIR must name an existing directory"
        }
        writePng(dashboard, File(outputDirectory, "dashboard-full.png"))
        writePng(graph, File(outputDirectory, "ve-graph.png"))

        System.getenv("VITALBREATHE_ICON_PATH")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.also { iconFile ->
                require(iconFile.parentFile?.isDirectory == true) {
                    "VITALBREATHE_ICON_PATH must have an existing parent directory"
                }
                writePng(icon, iconFile)
            }
    }

    private fun renderPublicIcon(): Bitmap {
        val icon = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val drawable = requireNotNull(
            RuntimeEnvironment.getApplication().getDrawable(R.drawable.ic_breathing),
        )
        drawable.setBounds(0, 0, icon.width, icon.height)
        drawable.draw(Canvas(icon))
        return icon
    }

    private fun renderDashboardScreenshot(): Bitmap {
        val content = Bitmap.createBitmap(CONTENT_WIDTH, CONTENT_HEIGHT, Bitmap.Config.ARGB_8888)
        val root = LayoutInflater.from(RuntimeEnvironment.getApplication())
            .inflate(R.layout.view_vital_dashboard, null) as ViewGroup
        val syntheticValues = mapOf(
            R.id.dashboard_ve to "30.0",
            R.id.dashboard_unit to "VE30 L/min",
            R.id.dashboard_zone to "zones off",
            R.id.dashboard_br to "BR 20",
            R.id.dashboard_tv to "TV 1.50 L",
            R.id.dashboard_hr to "HR 135",
            R.id.dashboard_battery to "BAT 88%",
        )
        syntheticValues.forEach { (id, value) -> root.findViewById<TextView>(id).text = value }

        val textSizes = dashboardTextSizes(200, DashboardLayoutVariant.FULL)
        root.findViewById<TextView>(R.id.dashboard_ve)
            .setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizes.valueSp)
        listOf(R.id.dashboard_unit, R.id.dashboard_zone).forEach { id ->
            root.findViewById<TextView>(id)
                .setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizes.metaSp)
        }
        listOf(
            R.id.dashboard_br,
            R.id.dashboard_tv,
            R.id.dashboard_hr,
            R.id.dashboard_battery,
        ).forEach { id ->
            root.findViewById<TextView>(id)
                .setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizes.detailSp)
        }

        root.measure(
            View.MeasureSpec.makeMeasureSpec(CONTENT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(CONTENT_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, CONTENT_WIDTH, CONTENT_HEIGHT)
        root.draw(Canvas(content))
        return frameScreenshot("VITAL DASHBOARD", content)
    }

    private fun renderGraphScreenshot(): Bitmap {
        val content = Bitmap.createBitmap(CONTENT_WIDTH, CONTENT_HEIGHT, Bitmap.Config.ARGB_8888)
        val points = listOf(
            TimedSample(0L, 18.0, segmentId = 1L),
            TimedSample(15_000L, 19.0, segmentId = 1L),
            TimedSample(30_000L, 21.0, segmentId = 1L),
            TimedSample(45_000L, 20.0, segmentId = 1L),
            TimedSample(60_000L, 23.0, segmentId = 1L),
            TimedSample(75_000L, 25.0, segmentId = 1L),
            TimedSample(90_000L, 24.0, segmentId = 1L),
            TimedSample(105_000L, 27.0, segmentId = 1L),
            TimedSample(120_000L, 29.0, segmentId = 1L),
            TimedSample(135_000L, 28.0, segmentId = 1L),
            TimedSample(180_000L, 24.0, segmentId = 2L),
            TimedSample(195_000L, 26.0, segmentId = 2L),
            TimedSample(210_000L, 28.0, segmentId = 2L),
            TimedSample(225_000L, 31.0, segmentId = 2L),
            TimedSample(240_000L, 30.0, segmentId = 2L),
            TimedSample(255_000L, 33.0, segmentId = 2L),
            TimedSample(270_000L, 35.0, segmentId = 2L),
            TimedSample(285_000L, 34.0, segmentId = 2L),
            TimedSample(300_000L, 36.0, segmentId = 2L),
        )
        val zonesOff = VentilationThresholds(0.0, 0.0, 0.0, 0.0, 0.0)
        val result = renderVeGraph(
            bitmap = content,
            points = points,
            thresholds = zonesOff,
            mode = GraphSmoothingMode.SMOOTH_30,
            nowElapsedMs = 300_000L,
            hasFreshBreath = true,
        )
        assertEquals("36.0 L/min", result.header.currentValue)
        assertEquals("ZONES OFF", result.header.zonesLabel)
        assertEquals(2, result.plottedSegmentCount)
        return frameScreenshot("VE GRAPH", content)
    }

    private fun frameScreenshot(title: String, content: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(232, 235, 236))

        val card = RectF(1f, CARD_TOP, 479f, CARD_BOTTOM)
        val cardPath = Path().apply { addRoundRect(card, 20f, 20f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(cardPath)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(content, 1f, CONTENT_TOP, null)

        val headerPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(1f, CARD_TOP, 479f, DEMO_TOP, headerPaint)

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val dotRadius = 17f
        val titleGap = 11f
        val groupWidth = dotRadius * 2f + titleGap + titlePaint.measureText(title)
        val groupLeft = (SCREEN_WIDTH - groupWidth) / 2f
        val headerCenterY = (CARD_TOP + DEMO_TOP) / 2f
        canvas.drawCircle(
            groupLeft + dotRadius,
            headerCenterY,
            dotRadius,
            Paint().apply {
                color = Color.rgb(17, 164, 103)
                style = Paint.Style.FILL
                isAntiAlias = true
            },
        )
        val titleBaseline = headerCenterY -
            (titlePaint.fontMetrics.ascent + titlePaint.fontMetrics.descent) / 2f
        canvas.drawText(title, groupLeft + dotRadius * 2f + titleGap, titleBaseline, titlePaint)

        canvas.drawRect(
            1f,
            DEMO_TOP,
            479f,
            CONTENT_TOP,
            Paint().apply {
                color = Color.rgb(218, 245, 237)
                style = Paint.Style.FILL
            },
        )
        val demoPaint = Paint().apply {
            color = Color.rgb(14, 91, 69)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val demoBaseline = (DEMO_TOP + CONTENT_TOP) / 2f -
            (demoPaint.fontMetrics.ascent + demoPaint.fontMetrics.descent) / 2f
        canvas.drawText("DEMO DATA - SYNTHETIC VALUES", SCREEN_WIDTH / 2f, demoBaseline, demoPaint)
        canvas.restore()

        canvas.drawRoundRect(
            card,
            20f,
            20f,
            Paint().apply {
                color = Color.rgb(92, 99, 102)
                style = Paint.Style.STROKE
                strokeWidth = 1f
                isAntiAlias = true
            },
        )
        return output
    }

    private fun writePng(bitmap: Bitmap, output: File) {
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Unable to write ${output.absolutePath}"
            }
        }
    }

    private fun nonBackgroundPixels(bitmap: Bitmap): Int {
        val background = Color.rgb(232, 235, 236)
        var changed = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != background) changed++
            }
        }
        return changed
    }

    companion object {
        private const val SCREEN_WIDTH = 480
        private const val SCREEN_HEIGHT = 800
        private const val CONTENT_WIDTH = 478
        private const val CONTENT_HEIGHT = 684
        private const val CARD_TOP = 8f
        private const val DEMO_TOP = 72f
        private const val CONTENT_TOP = 104f
        private const val CARD_BOTTOM = 788f
    }
}

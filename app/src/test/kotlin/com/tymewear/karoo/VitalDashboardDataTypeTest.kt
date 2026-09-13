package com.tymewear.karoo

import android.graphics.Rect
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class VitalDashboardDataTypeTest {

    @Test
    fun `half-height Karoo field uses compact dashboard`() {
        assertEquals(
            DashboardLayoutVariant.COMPACT,
            dashboardLayoutFor(viewSize = 480 to 415),
        )
    }

    @Test
    fun `full-height wide field keeps full dashboard`() {
        assertEquals(
            DashboardLayoutVariant.FULL,
            dashboardLayoutFor(viewSize = 480 to 700),
        )
    }

    @Test
    fun `narrow field uses compact dashboard even when tall`() {
        assertEquals(
            DashboardLayoutVariant.COMPACT,
            dashboardLayoutFor(viewSize = 320 to 700),
        )
    }

    @Test
    fun `multi-value text is capped below a single-value field recommendation`() {
        assertEquals(
            DashboardTextSizes(valueSp = 42f, metaSp = 18f, detailSp = 18f),
            dashboardTextSizes(
                recommendedTextSize = 200,
                layout = DashboardLayoutVariant.COMPACT,
            ),
        )
        assertEquals(
            DashboardTextSizes(valueSp = 52f, metaSp = 18f, detailSp = 22f),
            dashboardTextSizes(
                recommendedTextSize = 200,
                layout = DashboardLayoutVariant.FULL,
            ),
        )
    }

    @Test
    @Config(qualifiers = "w256dp-h427dp-300dpi")
    fun `worst case live values fit compact and full layouts at Karoo density`() {
        assertDashboardFits(
            layoutResource = R.layout.view_vital_dashboard_compact,
            widthPx = 480,
            heightPx = 351,
            textSizes = dashboardTextSizes(200, DashboardLayoutVariant.COMPACT),
        )
        assertDashboardFits(
            layoutResource = R.layout.view_vital_dashboard,
            widthPx = 480,
            heightPx = 636,
            textSizes = dashboardTextSizes(200, DashboardLayoutVariant.FULL),
        )
    }

    @Test
    @Config(qualifiers = "w256dp-h427dp-300dpi")
    fun `worst case live values fit a narrow tall compact field`() {
        assertDashboardFits(
            layoutResource = R.layout.view_vital_dashboard_compact,
            widthPx = 320,
            heightPx = 636,
            textSizes = dashboardTextSizes(200, DashboardLayoutVariant.COMPACT),
        )
    }

    @Test
    fun `zone label distinguishes no data from disabled zones`() {
        assertEquals("—", dashboardZoneLabel(zone = null, zonesConfigured = false))
        assertEquals("zones off", dashboardZoneLabel(zone = 0, zonesConfigured = false))
    }

    @Test
    fun `zone label reports a configured zone and rejects invalid data`() {
        assertEquals("Z3", dashboardZoneLabel(zone = 3, zonesConfigured = true))
        assertEquals("—", dashboardZoneLabel(zone = 0, zonesConfigured = true))
    }

    private fun assertDashboardFits(
        layoutResource: Int,
        widthPx: Int,
        heightPx: Int,
        textSizes: DashboardTextSizes,
    ) {
        val root = LayoutInflater.from(RuntimeEnvironment.getApplication())
            .inflate(layoutResource, null) as ViewGroup
        val values = mapOf(
            R.id.dashboard_ve to "120.0",
            R.id.dashboard_unit to "VE30 L/min",
            R.id.dashboard_zone to "zones off",
            R.id.dashboard_br to "BR 120",
            R.id.dashboard_tv to "TV 9.99 L",
            R.id.dashboard_hr to "HR 250",
            R.id.dashboard_battery to "BAT 100%",
        )
        values.forEach { (id, text) -> root.findViewById<TextView>(id).text = text }
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
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)

        values.keys.forEach { id ->
            val textView = root.findViewById<TextView>(id)
            val bounds = Rect(0, 0, textView.width, textView.height)
            root.offsetDescendantRectToMyCoords(textView, bounds)
            assertTrue("view $id starts inside dashboard: $bounds", bounds.left >= 0 && bounds.top >= 0)
            assertTrue(
                "view $id ends inside dashboard: $bounds",
                bounds.right <= widthPx && bounds.bottom <= heightPx,
            )
            assertEquals("view $id stays on one line", 1, textView.lineCount)
            assertEquals("view $id is not ellipsized", 0, textView.layout.getEllipsisCount(0))
            assertTrue(
                "view $id text fits its measured width",
                textView.layout.getLineWidth(0) <= textView.width,
            )
        }
    }
}

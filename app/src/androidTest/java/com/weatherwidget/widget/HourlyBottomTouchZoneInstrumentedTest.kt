package com.weatherwidget.widget

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the hourly graph footer touch target.
 *
 * The zoom zones only cover the graph body (graph_interaction_body). The bottom row
 * uses per-hour tap zones (graph_bottom_hour_zones) that sit below the graph body
 * inside graph_interaction_container. These tests verify correct sizing and alignment.
 */
@RunWith(AndroidJUnit4::class)
class HourlyBottomTouchZoneInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun graphBottomHourZones_matchesReservedFooterHeight() {
        val root = inflateMeasuredWidget()
        val reservedFooter = root.findViewById<View>(R.id.graph_bottom_reserved_space)
        val bottomHourZones = root.findViewById<View>(R.id.graph_bottom_hour_zones)

        // Show both to compare measured heights
        reservedFooter.visibility = View.VISIBLE
        bottomHourZones.visibility = View.VISIBLE
        measureAndLayout(root)

        assertEquals(
            "Per-hour bottom zones height must match the reserved footer height",
            reservedFooter.height,
            bottomHourZones.height,
        )
        assertTrue(
            "Regression guard: bottom touch target must remain at least 56dp tall",
            bottomHourZones.height >= dpToPx(56f),
        )
    }

    @Test
    fun graphBottomHourZones_startsAtGraphBodyBoundary() {
        val root = inflateMeasuredWidget()
        val graphBody = root.findViewById<View>(R.id.graph_interaction_body)
        val bottomHourZones = root.findViewById<View>(R.id.graph_bottom_hour_zones)

        bottomHourZones.visibility = View.VISIBLE
        measureAndLayout(root)

        assertEquals(
            "Per-hour bottom zones should begin exactly where the zoomable graph body ends",
            graphBody.bottom,
            bottomHourZones.top,
        )
    }

    private fun inflateMeasuredWidget(): FrameLayout {
        return LayoutInflater.from(context)
            .inflate(R.layout.widget_weather, null, false) as FrameLayout
    }

    private fun measureAndLayout(root: FrameLayout) {
        val widthPx = dpToPx(600f)
        val heightPx = dpToPx(400f)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        ).toInt()
    }
}

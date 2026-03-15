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
 * The zoom zones only cover the graph body. The bottom footer used by hourly and precipitation
 * mode must reserve the same vertical space as the graph bottom tap zone, otherwise taps on the
 * rendered hour-icon row can leak into the zoom hit area.
 */
@RunWith(AndroidJUnit4::class)
class HourlyBottomTouchZoneInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun graphBottomZone_matchesReservedFooterHeight() {
        val root = inflateMeasuredWidget()
        val reservedFooter = root.findViewById<View>(R.id.graph_bottom_reserved_space)
        val bottomZone = root.findViewById<View>(R.id.graph_bottom_zone)

        bottomZone.visibility = View.VISIBLE
        measureAndLayout(root)

        assertEquals(
            "Bottom tap zone height must match the footer height reserved out of the zoom body",
            reservedFooter.height,
            bottomZone.height,
        )
        assertTrue(
            "Regression guard: bottom touch target must remain at least 56dp tall",
            bottomZone.height >= dpToPx(56f),
        )
    }

    @Test
    fun graphBottomZone_startsAtGraphBodyBoundary() {
        val root = inflateMeasuredWidget()
        val graphBody = root.findViewById<View>(R.id.graph_interaction_body)
        val reservedFooter = root.findViewById<View>(R.id.graph_bottom_reserved_space)
        val bottomZone = root.findViewById<View>(R.id.graph_bottom_zone)

        bottomZone.visibility = View.VISIBLE
        measureAndLayout(root)

        assertEquals(
            "Reserved footer should begin exactly where the zoomable graph body ends",
            graphBody.bottom,
            reservedFooter.top,
        )
        assertEquals(
            "Bottom tap zone should align with the reserved footer after root padding is applied",
            reservedFooter.top + root.paddingBottom,
            bottomZone.top,
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

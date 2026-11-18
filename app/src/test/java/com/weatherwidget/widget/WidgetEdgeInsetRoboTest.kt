package com.weatherwidget.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.handlers.SimLoopTouchTarget
import com.weatherwidget.widget.handlers.WidgetInsets
import com.weatherwidget.widget.handlers.WidgetSizeCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Lays out the real `widget_weather.xml` at a fixed widget size and ties it to the Kotlin that
 * sizes the bitmap ([WidgetSizeCalculator.computeBitmapDimensions]) and places pill touch zones
 * ([SimLoopTouchTarget.graphViewSizeDp]) through [WidgetInsets].
 *
 * Fold 4, 2026-11-18: the graph ended 27 px inside the widget while the Daily Calendar widget below
 * it drew to its edge; the widget's 8 dp root padding and 4 dp graph margins were removed
 * (plans/261118-remove-widget-edge-padding.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WidgetEdgeInsetRoboTest {
    private lateinit var context: Context
    private lateinit var root: ViewGroup
    private var density = 1f

    private val widgetWidthDp = 400
    private val widgetHeightDp = 220

    /** Views that must track `graph_view` horizontally: they map taps onto the painted graph. */
    private val graphTrackingIds = listOf(
        R.id.graph_day_zones,
        R.id.graph_interaction_container,
        R.id.graph_bottom_zone,
        R.id.graph_bottom_day_zones,
        R.id.graph_night_rain_zones,
        R.id.graph_bottom_hour_zones,
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        density = context.resources.displayMetrics.density
        root = LayoutInflater.from(context).inflate(R.layout.widget_weather, null) as ViewGroup
        (listOf(R.id.graph_view, R.id.current_weather_container, R.id.top_right_header_container) + graphTrackingIds)
            .forEach { root.findViewById<View>(it).visibility = View.VISIBLE }
        val w = px(widgetWidthDp.toFloat())
        val h = px(widgetHeightDp.toFloat())
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
    }

    private fun px(dp: Float): Int = (dp * density + 0.5f).toInt()

    private fun View.bounds() = "[$left,$top][$right,$bottom]"

    @Test
    fun `graph_view fills the widget edge to edge`() {
        val graph = root.findViewById<View>(R.id.graph_view)
        println("density=$density root=${root.width}x${root.height} padding=${root.paddingLeft},${root.paddingTop},${root.paddingRight},${root.paddingBottom} graph_view=${graph.bounds()}")
        assertEquals("root padding", px(WidgetInsets.ROOT_PADDING_DP), root.paddingLeft)
        assertEquals("root padding", px(WidgetInsets.ROOT_PADDING_DP), root.paddingTop)
        assertEquals("root padding", px(WidgetInsets.ROOT_PADDING_DP), root.paddingRight)
        assertEquals("root padding", px(WidgetInsets.ROOT_PADDING_DP), root.paddingBottom)
        assertEquals("graph left", 0, graph.left)
        assertEquals("graph top", 0, graph.top)
        assertEquals("graph right", root.width, graph.right)
        assertEquals("graph bottom", root.height, graph.bottom)
    }

    @Test
    fun `bitmap and pill-zone sizing match the laid-out graph_view`() {
        val graph = root.findViewById<View>(R.id.graph_view)
        val (viewWdp, viewHdp) = SimLoopTouchTarget.graphViewSizeDp(widgetWidthDp, widgetHeightDp)
        val dims = WidgetSizeCalculator.computeBitmapDimensions(context, widgetWidthDp, widgetHeightDp)
        val rawW = dims.widthPx / dims.bitmapScale
        val rawH = dims.heightPx / dims.bitmapScale
        println("graph_view=${graph.width}x${graph.height}px simLoopView=${viewWdp}x${viewHdp}dp bitmap=${dims.widthPx}x${dims.heightPx} scale=${dims.bitmapScale} raw=${rawW}x$rawH")
        assertEquals("SimLoopTouchTarget width", graph.width.toFloat(), viewWdp * density, 1f)
        assertEquals("SimLoopTouchTarget height", graph.height.toFloat(), viewHdp * density, 1f)
        assertEquals("bitmap width (unscaled)", graph.width.toFloat(), rawW, 2f)
        assertEquals("bitmap height (unscaled)", graph.height.toFloat(), rawH, 2f)
    }

    @Test
    fun `touch-zone containers track graph_view horizontally`() {
        val graph = root.findViewById<View>(R.id.graph_view)
        graphTrackingIds.forEach { id ->
            val v = root.findViewById<View>(id)
            val name = context.resources.getResourceEntryName(id)
            println("$name=${v.bounds()} graph_view=${graph.bounds()}")
            assertEquals("$name left", graph.left, v.left)
            assertEquals("$name right", graph.right, v.right)
        }
    }

    @Test
    fun `no header view is pushed off the widget's sides`() {
        // Negative margins used to reach into the 8 dp padding; without it they would leave the widget.
        listOf(
            R.id.current_weather_container,
            R.id.top_right_header_container,
            R.id.text_mode_api_source_container,
            R.id.text_mode_api_touch_zone,
        ).forEach { id ->
            val v = root.findViewById<View>(id)
            v.visibility = View.VISIBLE
        }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.width, root.height)
        val slack = px(2f) // top_right_header_container keeps its old -2 dp overhang
        listOf(
            R.id.current_weather_container,
            R.id.top_right_header_container,
            R.id.text_mode_api_source_container,
            R.id.text_mode_api_touch_zone,
        ).forEach { id ->
            val v = root.findViewById<View>(id)
            val name = context.resources.getResourceEntryName(id)
            println("$name=${v.bounds()} root=${root.width}x${root.height}")
            assertTrue("$name left ${v.left} off the widget", v.left >= 0)
            assertTrue("$name right ${v.right} off the widget (${root.width})", v.right <= root.width + slack)
        }
    }

    /**
     * The chevrons' ink sits on the widget edge (user, 2026-11-18: "Left and right arrows need to move
     * to the edge"). The `_edge` drawables put the ink at the viewport's outer edge; the button has no
     * outer padding and its content box equals the drawable, so `centerInside` does not scale or
     * centre it away from the edge. `DailyViewHandler.setupNavigationButtons` re-applies the same
     * padding at runtime.
     */
    @Test
    fun `nav arrow chevrons sit on the widget edge`() {
        data class Arrow(val id: Int, val drawable: Int, val atRight: Boolean)
        listOf(
            Arrow(R.id.nav_left, R.drawable.ic_chevron_left_edge, atRight = false),
            Arrow(R.id.nav_right, R.drawable.ic_chevron_right_edge, atRight = true),
        ).forEach { a ->
            val b = root.findViewById<ImageButton>(a.id)
            val name = context.resources.getResourceEntryName(a.id)
            val d = b.drawable
            val contentW = b.width - b.paddingLeft - b.paddingRight
            println("$name=${b.bounds()} padding=${b.paddingLeft},${b.paddingRight} drawable=${d.intrinsicWidth}x${d.intrinsicHeight} res=${shadowOf(d).createdFromResId}")
            assertEquals("$name drawable", a.drawable, shadowOf(d).createdFromResId)
            assertEquals("$name content box = drawable width (no centring gap)", d.intrinsicWidth, contentW)
            if (a.atRight) {
                assertEquals("$name at the right edge", root.width, b.right)
                assertEquals("$name outer padding", 0, b.paddingRight)
            } else {
                assertEquals("$name at the left edge", 0, b.left)
                assertEquals("$name outer padding", 0, b.paddingLeft)
            }
        }
    }
}

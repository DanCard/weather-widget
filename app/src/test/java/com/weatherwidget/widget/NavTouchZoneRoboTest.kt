package com.weatherwidget.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.shared.graph.NavArrowGeometry
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class NavTouchZoneRoboTest {

    private lateinit var context: Context
    private lateinit var rootView: View

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        rootView = LayoutInflater.from(context).inflate(R.layout.widget_weather, null)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    @Test
    fun `nav_left_zone has 40dp width`() {
        val navLeftZone = rootView.findViewById<FrameLayout>(R.id.nav_left_zone)
        assertEquals("nav_left_zone width should be 40dp", dpToPx(40), navLeftZone.layoutParams.width)
    }

    @Test
    fun `nav_right_zone has 40dp width`() {
        val navRightZone = rootView.findViewById<FrameLayout>(R.id.nav_right_zone)
        assertEquals("nav_right_zone width should be 40dp", dpToPx(40), navRightZone.layoutParams.width)
    }

    @Test
    fun `nav_left button has 40dp width`() {
        val navLeft = rootView.findViewById<ImageButton>(R.id.nav_left)
        assertEquals("nav_left width should be 40dp", dpToPx(40), navLeft.layoutParams.width)
    }

    @Test
    fun `nav_right button has 40dp width`() {
        val navRight = rootView.findViewById<ImageButton>(R.id.nav_right)
        assertEquals("nav_right width should be 40dp", dpToPx(40), navRight.layoutParams.width)
    }

    @Test
    fun `current_temp_zone is declared after nav buttons for z-order priority`() {
        val root = rootView as ViewGroup
        val navLeftIndex = indexOfChild(root, R.id.nav_left)
        val navRightIndex = indexOfChild(root, R.id.nav_right)
        val tempContainerIndex = indexOfChild(root, R.id.current_weather_container)

        assertTrue("current_temp_container must be after nav_left in z-order",
            tempContainerIndex > navLeftIndex)
        assertTrue("current_temp_container must be after nav_right in z-order",
            tempContainerIndex > navRightIndex)
    }

    private fun indexOfChild(parent: ViewGroup, childId: Int): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).id == childId) return i
        }
        return -1
    }

    /**
     * [NavArrowGeometry] reserves space for the arrows in the graph bitmap, where they are not drawn
     * and cannot be measured. Its dp constants are therefore a hand-copy of this layout, and a silent
     * drift would leave free-floating labels dodging a rectangle that is no longer the chevron —
     * green tests, label back on the arrow. These assertions are the only thing tying the two together.
     */
    @Test
    fun `NavArrowGeometry ARROW_WIDTH_DP matches the real nav button width`() {
        listOf(R.id.nav_left, R.id.nav_right).forEach { id ->
            val button = rootView.findViewById<ImageButton>(id)
            assertEquals(
                "NavArrowGeometry.ARROW_WIDTH_DP is out of sync with widget_weather.xml",
                dpToPx(NavArrowGeometry.ARROW_WIDTH_DP.toInt()),
                button.layoutParams.width,
            )
        }
    }

    @Test
    fun `NavArrowGeometry ARROW_HEIGHT_DP matches the real nav button minHeight`() {
        listOf(R.id.nav_left, R.id.nav_right).forEach { id ->
            val button = rootView.findViewById<ImageButton>(id)
            assertEquals(
                "NavArrowGeometry.ARROW_HEIGHT_DP is out of sync with widget_weather.xml",
                dpToPx(NavArrowGeometry.ARROW_HEIGHT_DP.toInt()),
                button.minimumHeight,
            )
        }
    }

    /**
     * Only `ARROW_WIDTH_DP - GRAPH_INSET_DP` of the arrow overlaps the bitmap, because graph_view is
     * inset from the widget edge. Drop this term and every reservation is 4dp too wide.
     */
    @Test
    fun `NavArrowGeometry GRAPH_INSET_DP matches graph_view's horizontal margin`() {
        val graphView = rootView.findViewById<View>(R.id.graph_view)
        val params = graphView.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(
            "NavArrowGeometry.GRAPH_INSET_DP is out of sync with graph_view's layout_marginStart",
            dpToPx(NavArrowGeometry.GRAPH_INSET_DP.toInt()),
            params.marginStart,
        )
        assertEquals(
            "graph_view's start and end margins must match for the mirrored arrow reservation",
            params.marginStart,
            params.marginEnd,
        )
    }
}

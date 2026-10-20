package com.weatherwidget.widget.handlers

import com.weatherwidget.shared.graph.GraphRect
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The pill lives in bitmap pixels behind `scaleType="fitCenter"`; its tap zone lives in content dp.
 * These pin the mapping between the two, which the first version did not have at all.
 */
@Category(ShortDuration::class)
class SimLoopTouchTargetTest {
    @Test
    fun `graph view size matches computeBitmapDimensions paddings`() {
        assertEquals(376f to 204f, SimLoopTouchTarget.graphViewSizeDp(400, 220))
    }

    @Test
    fun `same aspect - bitmap px scale straight into dp, offset by the graph inset`() {
        // 376x204dp view showing a 2.625x bitmap (Pixel density 2.625, bitmapScale 1).
        val r = SimLoopTouchTarget.bitmapToContentDp(
            GraphRect(262.5f, 131.25f, 525f, 189f),
            bitmapWidthPx = 987, bitmapHeightPx = 535,
            viewWidthDp = 376f, viewHeightDp = 204f,
        )
        val k = minOf(376f / 987, 204f / 535)
        assertEquals(4f + (376f - 987 * k) / 2f + 262.5f * k, r.left, 0.01f)
        assertEquals((204f - 535 * k) / 2f + 131.25f * k, r.top, 0.01f)
    }

    @Test
    fun `letterboxed vertically - pill moves down by the centring offset`() {
        // Bitmap wider than the view's aspect: fitCenter scales by width and centres vertically.
        val r = SimLoopTouchTarget.bitmapToContentDp(
            GraphRect(0f, 0f, 100f, 50f),
            bitmapWidthPx = 1000, bitmapHeightPx = 400,
            viewWidthDp = 500f, viewHeightDp = 300f,
        )
        assertEquals(4f, r.left, 0.01f)
        assertEquals(50f, r.top, 0.01f) // (300 - 400*0.5) / 2
        assertEquals(54f, r.right, 0.01f)
        assertEquals(75f, r.bottom, 0.01f)
    }

    @Test
    fun `zone covers the pill, stays under the header and off the nav arrows`() {
        val content = 384f to 204f
        // A pill just under the plot top at the right edge, near the right arrow's band.
        val pill = GraphRect(270f, 50f, 340f, 72f)
        val zone = SimLoopTouchTarget.zoneDp(pill, plotBottomDp = 170f, contentWidthDp = content.first, contentHeightDp = content.second)
        assertTrue("covers pill: $zone", zone.left <= pill.left && zone.right >= pill.right && zone.top <= pill.top && zone.bottom >= pill.bottom)
        assertTrue("below header: $zone", zone.top >= SimLoopTouchTarget.HEADER_TOUCH_BOTTOM_DP)
        val arrowTop = (content.second - 80f) / 2f
        assertFalse(zone.intersects(GraphRect(content.first - 40f, arrowTop, content.first, arrowTop + 80f)))
    }
}

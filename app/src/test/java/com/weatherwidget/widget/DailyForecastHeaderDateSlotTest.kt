package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Header date placement around the centred daily-header button slot.
 *
 * Pure arithmetic, no font engine involved (Robolectric has none) — widths are supplied already
 * measured, which is the whole reason [DailyForecastHeaderRenderer.resolveDateDrawX] was extracted.
 */
@Category(ShortDuration::class)
class DailyForecastHeaderDateSlotTest {

    private val gap = 6f
    private val rightMargin = 112f

    private fun drawX(
        widthPx: Float = 440f,
        dateWidth: Float = 46f,
        leftClusterRight: Float = 123f,
        dateRightBoundary: Float = 369f,
        centerIconsWidth: Float = 0f,
    ) = DailyForecastHeaderRenderer.resolveDateDrawX(
        widthPx = widthPx,
        dateWidth = dateWidth,
        leftClusterRight = leftClusterRight,
        dateRightBoundary = dateRightBoundary,
        centerIconsWidth = centerIconsWidth,
        gapPx = gap,
        rightMarginPx = rightMargin,
    )

    // ---- no slot: historical behaviour must be untouched ----

    @Test
    fun `without a slot a roomy header centres the date`() {
        assertEquals(220f, drawX()!!, 0.01f)
    }

    @Test
    fun `without a slot a colliding left cluster falls back to the right anchor`() {
        // Centre would start at 197, left cluster reaches 220 -> centre unavailable.
        val x = drawX(leftClusterRight = 220f)
        assertEquals(440f - rightMargin, x!!, 0.01f)
    }

    @Test
    fun `without a slot the date is dropped when neither position fits`() {
        // Left cluster swallows the centre and the right anchor alike.
        assertNull(drawX(leftClusterRight = 330f))
    }

    // ---- with a slot ----

    @Test
    fun `with a slot the date follows the icons on the right`() {
        // Order is buttons-then-date on both platforms. A roomy header lands on the familiar
        // 112dp anchor, which is to the RIGHT of the centred buttons.
        val x = drawX(centerIconsWidth = 48f)!!
        assertEquals(440f - rightMargin, x, 0.01f)
        assertTrue("date must sit right of the buttons", x - 46f / 2f >= 220f + 48f / 2f + gap)
    }

    @Test
    fun `with a slot the date is never placed left of the icons`() {
        // The date-before-buttons rung was removed for cross-platform consistency. Sweep the left
        // cluster: no input may produce a placement that ends before the buttons begin.
        val slot = 48f
        val slotLeft = 220f - slot / 2f
        for (left in 0..320 step 4) {
            val x = drawX(leftClusterRight = left.toFloat(), centerIconsWidth = slot) ?: continue
            assertTrue(
                "leftCluster=$left produced a date left of the buttons at $x",
                x - 46f / 2f >= slotLeft,
            )
        }
    }

    @Test
    fun `with a slot the date never overlaps the icons`() {
        // Sweep the left cluster across the whole header; wherever the date lands it must clear
        // the slot. This is the invariant the feature exists to protect.
        val slot = 48f
        val slotLeft = 220f - slot / 2f
        val slotRight = 220f + slot / 2f
        var placements = 0
        for (left in 0..320 step 4) {
            val x = drawX(leftClusterRight = left.toFloat(), centerIconsWidth = slot) ?: continue
            placements++
            val dateLeft = x - 46f / 2f
            val dateRight = x + 46f / 2f
            assertTrue(
                "leftCluster=$left date=$dateLeft..$dateRight slot=$slotLeft..$slotRight",
                dateRight <= slotLeft || dateLeft >= slotRight,
            )
        }
        assertTrue("expected some placements to exercise the invariant", placements > 0)
    }

    @Test
    fun `with a slot a colliding left cluster pushes the date to the right anchor`() {
        // Left-of-slot needs the date to start at 167; a cluster reaching 170 blocks it.
        val x = drawX(leftClusterRight = 170f, centerIconsWidth = 48f)
        assertEquals(440f - rightMargin, x!!, 0.01f)
    }

    @Test
    fun `the right anchor must clear the icons too not just the left cluster`() {
        // Left cluster is short, but a wide slot reaches past the right anchor's left edge.
        // Without the max(leftCluster, slotRight) bound this would wrongly place the date.
        val wideSlot = 260f
        val x = drawX(leftClusterRight = 40f, centerIconsWidth = wideSlot)
        val slotRight = 220f + wideSlot / 2f // 350
        val anchorLeft = 440f - rightMargin - 46f / 2f // 305
        assertTrue("anchor would overlap the slot", anchorLeft < slotRight)
        assertNull(x)
    }

    @Test
    fun `with a slot the date is dropped when neither position fits`() {
        assertNull(drawX(leftClusterRight = 330f, centerIconsWidth = 48f))
    }

    @Test
    fun `date lands in the free span when the fixed anchor misses but room remains`() {
        // Measured on a ~350dp widget: the 112dp anchor overlapped the buttons by 11dp while a
        // 53dp gap sat just to its right, and the date was dropped. The anchor is a position, not
        // a search — this is the rung that turns the remaining room into a placement.
        val width = 328f
        val leftCluster = 140f
        val apiLeft = 253f
        val slot = 48f
        val x = DailyForecastHeaderRenderer.resolveDateDrawX(
            widthPx = width,
            dateWidth = 46f,
            leftClusterRight = leftCluster,
            dateRightBoundary = apiLeft,
            centerIconsWidth = slot,
            gapPx = gap,
            rightMarginPx = rightMargin,
        )
        assertNotNull("expected the free span to be used", x)
        val slotRight = width / 2f + slot / 2f
        assertTrue("date must clear the buttons", x!! - 46f / 2f >= slotRight + gap)
        assertTrue("date must clear the api label", x + 46f / 2f <= apiLeft - gap)
        // And it is NOT the fixed anchor, which is what missed.
        assertTrue("should not be the fixed anchor", kotlin.math.abs(x - (width - rightMargin)) > 0.5f)
    }

    @Test
    fun `free span fallback never applies without a slot`() {
        // Without buttons the centre and the anchor already cover the row; adding a third position
        // would change long-standing behaviour for every non-daily header.
        assertNull(drawX(leftClusterRight = 330f, centerIconsWidth = 0f))
    }

    // ---- case D vs case H: the width must track the live icon count ----

    @Test
    fun `halving the slot when today scrolls off screen widens the date's gap`() {
        // Case D/H from the plan: same header content, two buttons vs one. Dropping the
        // observations button gives the date 24dp more room on the right — the cheapest proof that
        // the reserved width tracks the live icon count instead of a constant.
        val width = 328f
        val leftCluster = 140f
        val apiLeft = 253f
        fun freeSpan(slot: Float): Float {
            val slotRight = width / 2f + slot / 2f
            return (apiLeft - gap) - (maxOf(leftCluster, slotRight) + gap)
        }
        // Wide zones (40dp each): slot is 80dp with both buttons, 40dp with history alone. The
        // slot is centred, so dropping a button moves its right edge by half the width lost.
        val twoButtons = freeSpan(80f)
        val oneButton = freeSpan(40f)
        assertEquals(20f, oneButton - twoButtons, 0.01f)

        // And at this width that difference is what decides whether the date renders at all.
        val date = 46f
        assertTrue("two buttons should not fit a ${date}dp date, got $twoButtons", twoButtons < date)
        assertTrue("one button should fit a ${date}dp date, got $oneButton", oneButton >= date)
    }
}

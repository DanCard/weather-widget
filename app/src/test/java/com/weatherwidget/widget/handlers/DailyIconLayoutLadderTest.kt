package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The home button's seat in the daily header — [HeaderWidthChecker.resolveDailyIconLayoutFromBounds].
 *
 * Framework-free for the same reason as [DailyIconPlacementTest]: Robolectric has no font engine,
 * so bounds arrive already measured.
 */
@Category(ShortDuration::class)
class DailyIconLayoutLadderTest {

    private val gap = 6f
    private val width = 440f
    private val apiLeft = 369f
    private val centerZone = 40f
    private val inlineZone = 32f
    private val inlineMargin = 1f

    private fun layout(
        leftClusterRight: Float,
        wantHome: Boolean,
        baseIconCount: Int = 2,
        apiLeftPx: Float = apiLeft,
    ) = HeaderWidthChecker.resolveDailyIconLayoutFromBounds(
        widthPx = width,
        leftClusterRight = leftClusterRight,
        apiLeft = apiLeftPx,
        gapPx = gap,
        centerZoneWidthPx = centerZone,
        inlineZoneWidthPx = inlineZone,
        inlineFirstMarginPx = inlineMargin,
        baseIconCount = baseIconCount,
        wantHome = wantHome,
    )

    @Test
    fun `a roomy header seats all three buttons in the centre`() {
        // Three 40px zones span 160..280; a left cluster ending at 123 clears it.
        val result = layout(leftClusterRight = 123f, wantHome = true)
        assertEquals(DailyIconPlacement.CENTER, result.placement)
        assertEquals(3, result.iconCount)
        assertTrue(result.homeShown)
    }

    @Test
    fun `the preferred source asks for nothing and the row stays two wide`() {
        val result = layout(leftClusterRight = 123f, wantHome = false)
        assertEquals(DailyIconPlacement.CENTER, result.placement)
        assertEquals(2, result.iconCount)
        assertFalse(result.homeShown)
    }

    @Test
    fun `falling to inline is not a reason to drop the home button`() {
        // leftCluster 177 collides with the centred slot but leaves room inline. Every button is
        // visible on the INLINE rung, so the home button keeps its seat rather than being traded
        // for a centred pair.
        val result = layout(leftClusterRight = 177f, wantHome = true)
        assertEquals(DailyIconPlacement.INLINE, result.placement)
        assertEquals(3, result.iconCount)
        assertTrue(result.homeShown)
    }

    @Test
    fun `the home button is dropped rather than the pair when three will not fit`() {
        // Inline needs leftCluster + zones + margin + gap <= apiLeft: 3 zones need <= 266,
        // 2 zones need <= 298. At 280 only the pair fits, and the pair is what must survive —
        // losing the observations and history buttons to make room for the way back would be
        // trading two working affordances for one.
        val result = layout(leftClusterRight = 280f, wantHome = true)
        assertEquals(DailyIconPlacement.INLINE, result.placement)
        assertEquals(2, result.iconCount)
        assertFalse(result.homeShown)
    }

    @Test
    fun `a navigated header with one button still has room for home`() {
        // Observations drop when today and yesterday are both off screen; the freed zone is exactly
        // what the home button then occupies, so a panned header does not lose the way back.
        val result = layout(leftClusterRight = 280f, wantHome = true, baseIconCount = 1)
        assertEquals(2, result.iconCount)
        assertTrue(result.homeShown)
    }

    @Test
    fun `wanting home never costs the existing buttons their place`() {
        // The home button MAY move the row from CENTER to INLINE — that is the deliberate trade in
        // `falling to inline is not a reason to drop the home button`, and inline still shows
        // everything. What it must never do is make a row that had somewhere to go have nowhere,
        // or shrink the button count that was already going to be drawn.
        for (left in 0..360 step 4) {
            val without = layout(leftClusterRight = left.toFloat(), wantHome = false)
            val withHome = layout(leftClusterRight = left.toFloat(), wantHome = true)
            if (without.placement != DailyIconPlacement.HIDDEN) {
                assertTrue(
                    "leftCluster=$left hid the pair for home: $withHome",
                    withHome.placement != DailyIconPlacement.HIDDEN,
                )
            }
            assertTrue(
                "leftCluster=$left dropped a button for home: without=$without with=$withHome",
                withHome.iconCount >= without.iconCount,
            )
        }
    }

    @Test
    fun `a hidden row cannot gain a home button`() {
        val result = layout(leftClusterRight = 340f, wantHome = true)
        assertEquals(DailyIconPlacement.HIDDEN, result.placement)
        assertFalse(result.homeShown)
    }
}

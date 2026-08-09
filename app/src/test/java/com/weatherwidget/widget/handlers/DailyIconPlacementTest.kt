package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The CENTER / INLINE / HIDDEN ladder for the daily header buttons.
 *
 * Framework-free: bounds are supplied already measured, because Robolectric has no font engine and
 * anything driven by `measureText` there is not a trustworthy assertion.
 */
@Category(ShortDuration::class)
class DailyIconPlacementTest {

    private val gap = 6f
    private val width = 440f
    private val apiLeft = 369f

    private fun placement(
        leftClusterRight: Float,
        centerIconsWidth: Float = 80f,
        inlineIconsWidth: Float = 65f,
        widthPx: Float = width,
        apiLeftPx: Float = apiLeft,
    ) = HeaderWidthChecker.resolveDailyIconPlacementFromBounds(
        widthPx = widthPx,
        leftClusterRight = leftClusterRight,
        apiLeft = apiLeftPx,
        centerIconsWidth = centerIconsWidth,
        inlineIconsWidth = inlineIconsWidth,
        gapPx = gap,
    )

    @Test
    fun `roomy header centres the buttons`() {
        // Slot spans 180..260; a left cluster ending at 123 clears it.
        assertEquals(DailyIconPlacement.CENTER, placement(leftClusterRight = 123f))
    }

    @Test
    fun `buttons fall back to inline when the centre collides with the left cluster`() {
        // Left cluster reaches 177, one dp past the slot's left edge minus the gap. The buttons
        // must NOT vanish here — the original design had no fallback and lost BOTH buttons on this
        // shape, which is the defect the inline rung exists to prevent.
        val result = placement(leftClusterRight = 177f)
        assertEquals(DailyIconPlacement.INLINE, result)
    }

    @Test
    fun `a one dp collision is enough to leave the centre`() {
        // slotLeft = 180, so a cluster at 174 exactly fits (174 + 6 == 180) and 175 does not.
        assertEquals(DailyIconPlacement.CENTER, placement(leftClusterRight = 174f))
        assertEquals(DailyIconPlacement.INLINE, placement(leftClusterRight = 175f))
    }

    @Test
    fun `buttons are hidden only when even inline will not clear the api label`() {
        // Inline needs leftCluster + 65 + 6 <= 369, i.e. leftCluster <= 298.
        assertEquals(DailyIconPlacement.INLINE, placement(leftClusterRight = 298f))
        assertEquals(DailyIconPlacement.HIDDEN, placement(leftClusterRight = 299f))
    }

    @Test
    fun `zero width means hidden`() {
        assertEquals(DailyIconPlacement.HIDDEN, placement(leftClusterRight = 10f, centerIconsWidth = 0f))
    }

    @Test
    fun `the slot must clear the api label on the right as well`() {
        // Short left cluster, but a slot wide enough to reach the API label.
        assertEquals(
            DailyIconPlacement.INLINE,
            placement(leftClusterRight = 40f, centerIconsWidth = 340f),
        )
    }

    @Test
    fun `dropping the observations button never makes placement worse`() {
        // One button is strictly narrower than two, so no header may fall from CENTER to INLINE
        // (or to HIDDEN) purely by losing a button. Guards the iconCount plumbing against being
        // replaced by a constant.
        val rank = mapOf(
            DailyIconPlacement.CENTER to 2,
            DailyIconPlacement.INLINE to 1,
            DailyIconPlacement.HIDDEN to 0,
        )
        for (left in 0..360 step 4) {
            val two = placement(leftClusterRight = left.toFloat(), centerIconsWidth = 80f, inlineIconsWidth = 65f)
            val one = placement(leftClusterRight = left.toFloat(), centerIconsWidth = 40f, inlineIconsWidth = 33f)
            assertTrue("leftCluster=$left two=$two one=$one", rank.getValue(one) >= rank.getValue(two))
        }
    }

    @Test
    fun `narrow headers use the tighter zone so the date keeps its gap`() {
        // The airy 40dp zone is what pushed the date off a ~350dp widget; below the wide cutoff
        // the pair must tighten back so the date's gap on the right survives.
        assertEquals(
            HeaderConstants.DAILY_CENTER_ICON_ZONE_NARROW_DP,
            HeaderWidthChecker.dailyCenterIconZoneWidthDp(350),
            0.01f,
        )
        assertTrue(
            HeaderWidthChecker.dailyCenterIconsWidthDp(2, 350) <=
                HeaderWidthChecker.dailyCenterIconsWidthDp(2, 440),
        )
    }

    @Test
    fun `icon count scales the reserved width linearly`() {
        assertEquals(0f, HeaderWidthChecker.dailyCenterIconsWidthDp(0, 440), 0.01f)
        assertEquals(
            2f * HeaderWidthChecker.dailyCenterIconsWidthDp(1, 440),
            HeaderWidthChecker.dailyCenterIconsWidthDp(2, 440),
            0.01f,
        )
    }
}

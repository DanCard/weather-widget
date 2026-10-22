package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The daily header button zones' widths, which feed `HeaderWidthChecker.measureHeader` and
 * `positionDailyIcons` alike. Which buttons show and where is `HeaderFitTest` (`:shared`).
 */
@Category(ShortDuration::class)
class DailyIconZoneWidthTest {

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

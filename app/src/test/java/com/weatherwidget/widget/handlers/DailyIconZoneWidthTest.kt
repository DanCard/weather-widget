package com.weatherwidget.widget.handlers

import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The centred header button zones' widths, which feed `HeaderWidthChecker.measureHeader` and
 * `positionDailyIcons` / `positionCenterIcons` alike. Which buttons show, where, and how far the
 * zones narrow is `HeaderFitTest` (`:shared`); the rungs, `HeaderButtonZoneTest` and
 * `PositionDailyIconsRoboTest` (API 31+; plain JUnit sees SDK_INT 0, the XML width).
 */
@Category(ShortDuration::class)
class DailyIconZoneWidthTest {

    @Test
    fun `the laid-out zone is the one the fit chose, else the preferred`() {
        val density = 2.625f
        assertEquals(29f, HeaderWidthChecker.centerIconZoneWidthDp(HeaderFit.Plan(centreZone = 29f * density), 440, density), 0.01f)
        assertEquals(
            HeaderWidthChecker.centerIconZoneWidthDp(440),
            HeaderWidthChecker.centerIconZoneWidthDp(HeaderFit.Plan(), 440, density),
            0.01f,
        )
    }
}

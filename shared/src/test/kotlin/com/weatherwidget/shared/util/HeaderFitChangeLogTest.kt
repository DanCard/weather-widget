package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HeaderFitChangeLogTest {
    private val shown = HeaderFit.Plan(
        showTemp = true,
        buttons = 3,
        homeShown = true,
        placement = HeaderFit.ButtonPlacement.SHOWN,
        buttonZone = 40f,
    )
    private val hidden = HeaderFit.Plan(showTemp = true, placement = HeaderFit.ButtonPlacement.HIDDEN)

    @Test
    fun firstPlanForAKeyIsRecorded() {
        assertTrue(HeaderFitChangeLog().changed("w12/daily", shown))
    }

    @Test
    fun repeatedPlanIsNotRecordedAgain() {
        val log = HeaderFitChangeLog()
        log.changed("w12/daily", shown)
        assertFalse(log.changed("w12/daily", shown))
    }

    @Test
    fun buttonsHiddenThenShownAreEachRecorded() {
        val log = HeaderFitChangeLog()
        log.changed("w12/daily", shown)
        assertTrue("shown -> hidden must persist", log.changed("w12/daily", hidden))
        assertTrue("hidden -> shown must persist", log.changed("w12/daily", shown))
    }

    @Test
    fun buttonCountAndHomeChangesAreRecorded() {
        val log = HeaderFitChangeLog()
        log.changed("w12/hourly", shown)
        assertTrue(log.changed("w12/hourly", shown.copy(buttons = 2)))
        assertTrue(log.changed("w12/hourly", shown.copy(buttons = 2, homeShown = false)))
    }

    @Test
    fun textOnlyAndZoneNarrowingChangesAreNotRecorded() {
        val log = HeaderFitChangeLog()
        log.changed("w12/daily", shown)
        assertFalse(log.changed("w12/daily", shown.copy(showDate = true, showRain = true)))
        assertFalse(log.changed("w12/daily", shown.copy(buttonZone = 32f)))
    }

    @Test
    fun keysAreTrackedIndependently() {
        val log = HeaderFitChangeLog()
        log.changed("w12/daily", shown)
        assertTrue("another view starts fresh", log.changed("w12/hourly", shown))
        assertTrue("another widget starts fresh", log.changed("w13/daily", shown))
    }
}

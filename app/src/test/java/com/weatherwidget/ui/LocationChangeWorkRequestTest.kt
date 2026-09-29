package com.weatherwidget.ui

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The setup-screen change's forced sync is expedited on Android 12+: as plain work it waited 22 s
 * in JobScheduler on 2026-09-29 while the old city stayed on screen. Below API 31 expedited work
 * is a foreground service needing getForegroundInfo(), which the worker lacks — so never there.
 */
@Category(ShortDuration::class)
class LocationChangeWorkRequestTest {
    @Test
    fun `a location change is expedited on API 31+`() {
        assertTrue(LocationUpdater.buildForceRefreshRequest("Kyiv", banner = true, sdkInt = 31).workSpec.expedited)
        assertTrue(LocationUpdater.buildForceRefreshRequest("Kyiv", banner = false, sdkInt = 37).workSpec.expedited)
    }

    @Test
    fun `below API 31 nothing is expedited`() {
        assertFalse(LocationUpdater.buildForceRefreshRequest("Kyiv", banner = true, sdkInt = 30).workSpec.expedited)
    }

    @Test
    fun `a plain forced refresh is not expedited`() {
        // Only the one fetch the user is watching; everything else keeps normal scheduling.
        assertFalse(LocationUpdater.buildForceRefreshRequest(null, banner = false, sdkInt = 37).workSpec.expedited)
    }
}

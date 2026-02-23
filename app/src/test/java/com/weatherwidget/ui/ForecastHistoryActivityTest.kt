package com.weatherwidget.ui

import com.weatherwidget.ui.ForecastHistoryActivity.ButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.resolveButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.shouldLaunchHourly
import com.weatherwidget.ui.ForecastHistoryActivity.GraphMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastHistoryActivityTest {

    // --- shouldLaunchHourly ---

    @Test
    fun `shouldLaunchHourly - no date - returns false`() {
        assertFalse(shouldLaunchHourly(hasDate = false, snapshotsEmpty = true))
    }

    @Test
    fun `shouldLaunchHourly - date present, snapshots empty - returns true`() {
        assertTrue(shouldLaunchHourly(hasDate = true, snapshotsEmpty = true))
    }

    @Test
    fun `shouldLaunchHourly - date present, snapshots exist - returns false`() {
        assertFalse(shouldLaunchHourly(hasDate = true, snapshotsEmpty = false))
    }

    // --- resolveButtonMode ---

    @Test
    fun `resolveButtonMode - no snapshots - returns HOURLY regardless of graphMode`() {
        assertEquals(ButtonMode.HOURLY, resolveButtonMode(snapshotsEmpty = true, GraphMode.EVOLUTION))
        assertEquals(ButtonMode.HOURLY, resolveButtonMode(snapshotsEmpty = true, GraphMode.ERROR))
    }

    @Test
    fun `resolveButtonMode - has snapshots, evolution mode - returns EVOLUTION`() {
        assertEquals(ButtonMode.EVOLUTION, resolveButtonMode(snapshotsEmpty = false, GraphMode.EVOLUTION))
    }

    @Test
    fun `resolveButtonMode - has snapshots, error mode - returns ERROR`() {
        assertEquals(ButtonMode.ERROR, resolveButtonMode(snapshotsEmpty = false, GraphMode.ERROR))
    }
}

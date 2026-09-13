package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopLocationChangeFeedbackTest {

    private val mountainView = DesktopConfig(lat = 37.4168, lon = -122.0890, label = "Mountain View")
    private val sanFrancisco = DesktopConfig(lat = 37.7749, lon = -122.4194, label = "San Francisco")

    @Test
    fun `picker move to a new site names the place`() {
        assertEquals(
            "San Francisco",
            DesktopLocationChangeFeedback.pendingPlaceName("location-picker", mountainView, sanFrancisco),
        )
    }

    @Test
    fun `first-ever location names the place`() {
        assertEquals(
            "San Francisco",
            DesktopLocationChangeFeedback.pendingPlaceName("location-picker", null, sanFrancisco),
        )
    }

    @Test
    fun `picker re-save of the same site is not a move`() {
        val jittered = mountainView.copy(lat = mountainView.lat + 0.0004, lon = mountainView.lon - 0.0003)
        assertNull(DesktopLocationChangeFeedback.pendingPlaceName("location-picker", mountainView, jittered))
    }

    @Test
    fun `non-picker writers never trigger the interstitial`() {
        for (source in listOf("settings", "settings-close", "popup", "observations", "observations-window")) {
            assertNull(source, DesktopLocationChangeFeedback.pendingPlaceName(source, mountainView, sanFrancisco))
        }
    }
}

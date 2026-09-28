package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.test.category.ShortDuration
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val today = LocalDate.of(2026, 9, 28)

    private fun snapshot(vararg dates: String) = ForecastSnapshot(
        raw = RawFetch(daily = dates.map { DailyForecast(date = it, highTemp = 70f, lowTemp = 55f, condition = "Clear") }),
        resolved = ResolvedView(),
    )

    private val banner = com.weatherwidget.shared.util.LocationChangePaintPolicy.Feedback.BANNER
    private val interstitial = com.weatherwidget.shared.util.LocationChangePaintPolicy.Feedback.INTERSTITIAL

    @Test
    fun `cache with a row for today is adopted under the banner`() {
        val d = DesktopLocationChangeFeedback.decide(snapshot("2026-09-28", "2026-09-29"), hasRenderOnScreen = true, today)
        assertEquals(banner, d.feedback)
        assertTrue(d.adoptCached)
    }

    @Test
    fun `two-week-old cache keeps the previous site under the banner`() {
        // 2026-09-28: 94043's newest NWS rows were 09-15..09-21; adopting them left the popup
        // blank until the first fetch landed 26 s later.
        val stale = snapshot(*(15..21).map { "2026-09-%02d".format(it) }.toTypedArray())
        val d = DesktopLocationChangeFeedback.decide(stale, hasRenderOnScreen = true, today)
        assertEquals(banner, d.feedback)
        assertFalse(d.adoptCached)
    }

    @Test
    fun `nothing cached and nothing on screen shows the interstitial`() {
        val d = DesktopLocationChangeFeedback.decide(null, hasRenderOnScreen = false, today)
        assertEquals(interstitial, d.feedback)
        assertFalse(d.adoptCached)
    }
}

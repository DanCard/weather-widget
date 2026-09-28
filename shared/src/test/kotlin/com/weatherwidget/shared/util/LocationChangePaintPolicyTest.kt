package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.test.category.ShortDuration
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class LocationChangePaintPolicyTest {

    private val mountainView = 37.4168 to -122.0890
    private val sanFrancisco = 37.7749 to -122.4194

    @Test
    fun `setup change to a new site with no cache shows the interstitial`() {
        assertTrue(
            LocationChangePaintPolicy.shouldShowInterstitial(
                userInitiated = true,
                previous = mountainView,
                newLat = sanFrancisco.first,
                newLon = sanFrancisco.second,
                hasCachedRowsAtNewSite = false,
            ),
        )
    }

    @Test
    fun `first-ever location with no cache shows the interstitial`() {
        assertTrue(
            LocationChangePaintPolicy.shouldShowInterstitial(
                userInitiated = true,
                previous = null,
                newLat = sanFrancisco.first,
                newLon = sanFrancisco.second,
                hasCachedRowsAtNewSite = false,
            ),
        )
    }

    @Test
    fun `follow-device move never shows the interstitial`() {
        assertFalse(
            LocationChangePaintPolicy.shouldShowInterstitial(
                userInitiated = false,
                previous = mountainView,
                newLat = sanFrancisco.first,
                newLon = sanFrancisco.second,
                hasCachedRowsAtNewSite = false,
            ),
        )
    }

    @Test
    fun `switching to a site that already has today's rows paints nothing`() {
        assertFalse(
            LocationChangePaintPolicy.shouldShowInterstitial(
                userInitiated = true,
                previous = mountainView,
                newLat = sanFrancisco.first,
                newLon = sanFrancisco.second,
                hasCachedRowsAtNewSite = true,
            ),
        )
    }

    @Test
    fun `re-saving the same site is jitter, not a change`() {
        val jittered = mountainView.first + 0.0004 to mountainView.second - 0.0003
        assertTrue(LocationChangePaintPolicy.isSameSite(mountainView, jittered.first, jittered.second))
        assertFalse(
            LocationChangePaintPolicy.shouldShowInterstitial(
                userInitiated = true,
                previous = mountainView,
                newLat = jittered.first,
                newLon = jittered.second,
                hasCachedRowsAtNewSite = false,
            ),
        )
    }

    private val today = LocalDate.of(2026, 9, 28)

    private fun day(date: String, normal: Boolean = false) =
        DailyForecast(date = date, highTemp = 70f, lowTemp = 55f, condition = "Clear", isClimateNormal = normal)

    @Test
    fun `a forecast row for today counts as cached`() {
        assertTrue(LocationChangePaintPolicy.hasTodayRow(listOf(day("2026-09-27"), day("2026-09-28")), today))
    }

    @Test
    fun `only two-week-old rows do not count as cached`() {
        // 2026-09-28 desktop: Mountain View's newest rows were for 09-15..09-21; adopting them
        // painted an empty graph for the whole 26 s first fetch.
        val stale = (15..21).map { day("2026-09-%02d".format(it)) }
        assertFalse(LocationChangePaintPolicy.hasTodayRow(stale, today))
    }

    @Test
    fun `a climate-normal stand-in for today does not count as cached`() {
        assertFalse(LocationChangePaintPolicy.hasTodayRow(listOf(day("2026-09-28", normal = true)), today))
    }

    @Test
    fun `no rows do not count as cached`() {
        assertFalse(LocationChangePaintPolicy.hasTodayRow(emptyList(), today))
    }

    @Test
    fun `short place name keeps the leading city`() {
        assertEquals("Warsaw", LocationChangePaintPolicy.shortPlaceNameOrNull("Warsaw, Masovian Voivodeship, Poland"))
        assertEquals("Kyiv", LocationChangePaintPolicy.shortPlaceNameOrNull("Kyiv, Ukraine"))
        assertEquals("Mountain View", LocationChangePaintPolicy.shortPlaceNameOrNull("Mountain View"))
    }

    @Test
    fun `short place name gives up on a house number or postcode lead`() {
        // Same leading shape, different meaning: 860 is a house number (the next part is a street),
        // 94043 is the postcode (the next part is the city).
        assertNull(
            LocationChangePaintPolicy.shortPlaceNameOrNull(
                "860, Avery Drive, Mountain View, Santa Clara County, California, 94043, United States",
            ),
        )
        assertNull(
            LocationChangePaintPolicy.shortPlaceNameOrNull(
                "94043, Mountain View, Santa Clara County, California, United States",
            ),
        )
    }

    @Test
    fun `short place name gives up on bare coordinates`() {
        assertNull(LocationChangePaintPolicy.shortPlaceNameOrNull("37.4166, -122.0889"))
    }

    @Test
    fun `user move with something on screen gets a banner over it`() {
        assertEquals(
            LocationChangePaintPolicy.Feedback.BANNER,
            LocationChangePaintPolicy.feedback(userInitiated = true, siteChanged = true, hasRenderToKeep = true),
        )
    }

    @Test
    fun `user move with nothing on screen gets the interstitial`() {
        assertEquals(
            LocationChangePaintPolicy.Feedback.INTERSTITIAL,
            LocationChangePaintPolicy.feedback(userInitiated = true, siteChanged = true, hasRenderToKeep = false),
        )
    }

    @Test
    fun `background moves and re-saves get no feedback`() {
        assertEquals(
            LocationChangePaintPolicy.Feedback.NONE,
            LocationChangePaintPolicy.feedback(userInitiated = false, siteChanged = true, hasRenderToKeep = true),
        )
        assertEquals(
            LocationChangePaintPolicy.Feedback.NONE,
            LocationChangePaintPolicy.feedback(userInitiated = true, siteChanged = false, hasRenderToKeep = true),
        )
    }
}

package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
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
}

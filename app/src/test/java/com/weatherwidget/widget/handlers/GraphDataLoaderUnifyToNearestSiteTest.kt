package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Regression for the daily cloud-split flap (plans/260710-daily-cloud-cover-flap-stale-fragment.md):
 * a raw proximity-box query returns frozen fragments from earlier GPS fixes alongside the
 * current site, and downstream firstOrNull-style selections (DailyNoonCloudCover) then pick a
 * stale row. unifyToNearestSite must keep only the physical site nearest the widget location.
 */
@Category(ShortDuration::class)
class GraphDataLoaderUnifyToNearestSiteTest {

    // Real coordinates from the diagnosed device: current site plus two stale fragments.
    private val widgetLat = 37.41681
    private val widgetLon = -122.08892

    private fun row(lat: Double, lon: Double, cloud: Int?, fetchedAt: Long = 0L) =
        HourlyForecastEntity(
            dateTime = 1_783_468_800_000L, // arbitrary fixed hour
            locationLat = lat,
            locationLon = lon,
            temperature = 70f,
            condition = "Sunny",
            source = "NWS",
            cloudCover = cloud,
            fetchedAt = fetchedAt,
        )

    @Test
    fun dropsStaleFragmentsFromOtherSites() {
        val fresh = row(37.417, -122.089, cloud = 65)
        val staleNear = row(37.424, -122.088, cloud = 31)
        val staleFar = row(37.39, -122.081, cloud = 25)

        val unified = GraphDataLoader.unifyToNearestSite(
            listOf(staleFar, staleNear, fresh), // stale rows first: order must not matter
            widgetLat,
            widgetLon,
        )

        assertEquals(listOf(fresh), unified)
    }

    @Test
    fun keepsSubPrecisionFragmentsOfTheSameSite() {
        // Tens of metres apart: the same physical site accumulating GPS-jitter fragments.
        val fragmentA = row(37.4168014, -122.0889761, cloud = 60)
        val fragmentB = row(37.4168434, -122.0890549, cloud = 65)
        val staleSite = row(37.39, -122.081, cloud = 25)

        val unified = GraphDataLoader.unifyToNearestSite(
            listOf(staleSite, fragmentA, fragmentB),
            widgetLat,
            widgetLon,
        )

        assertEquals(2, unified.size)
        assertTrue(unified.containsAll(listOf(fragmentA, fragmentB)))
    }

    @Test
    fun fallsBackToNearestAvailableSiteWhenCurrentSiteHasNoRows() {
        // Only stale sites cached (e.g. right after moving): nearest one wins rather than
        // returning nothing — cached data with a location offset beats a blank widget.
        val staleNear = row(37.424, -122.088, cloud = 31)
        val staleFar = row(37.39, -122.081, cloud = 25)

        val unified = GraphDataLoader.unifyToNearestSite(
            listOf(staleFar, staleNear),
            widgetLat,
            widgetLon,
        )

        assertEquals(listOf(staleNear), unified)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals(
            emptyList<HourlyForecastEntity>(),
            GraphDataLoader.unifyToNearestSite(emptyList(), widgetLat, widgetLon),
        )
    }
}

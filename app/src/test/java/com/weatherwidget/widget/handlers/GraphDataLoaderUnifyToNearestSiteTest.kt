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
    private companion object {
        const val HOUR = 1_783_468_800_000L
        const val ONE_HOUR = 3_600_000L
    }

    private val widgetLat = 37.41681
    private val widgetLon = -122.08892

    private fun row(
        lat: Double,
        lon: Double,
        cloud: Int?,
        fetchedAt: Long = 0L,
        dateTime: Long = HOUR,
        source: String = "NWS",
    ) =
        HourlyForecastEntity(
            dateTime = dateTime,
            locationLat = lat,
            locationLon = lon,
            temperature = 70f,
            condition = "Sunny",
            source = source,
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

    // ---------------------------------------------------------------------------------------
    // Hours the winning site cannot cover.
    //
    // HourlyForecastStitcher.collapse lets an hour with no same-site row borrow from a fragment
    // within 0.01 deg rather than render blank; this collapse used to delete those rows again, and
    // the graph simply ended (2026-09-03: "missing=7 ranges=4a-10a" with the data 0.007 deg away).
    // See plans/260903-unify-must-keep-hours-the-nearest-site-cannot-cover.md.
    // ---------------------------------------------------------------------------------------

    @Test
    fun borrowsAnHourTheNearestSiteDoesNotCover() {
        val covered = row(37.417, -122.089, cloud = 65, dateTime = HOUR)
        // 0.0072 deg away: outside sameSite (0.002), inside the stitcher's fallback (0.01).
        val uncovered = row(37.424, -122.088, cloud = 31, dateTime = HOUR + ONE_HOUR)

        val unified = GraphDataLoader.unifyToNearestSite(
            listOf(uncovered, covered),
            widgetLat,
            widgetLon,
        )

        assertEquals(
            "the hour the nearest site cannot cover must survive, not blank the curve",
            listOf(HOUR, HOUR + ONE_HOUR),
            unified.map { it.dateTime },
        )
        assertEquals(listOf(65, 31), unified.map { it.cloudCover })
    }

    @Test
    fun borrowedRowIsRestampedOntoTheWinningSite() {
        // Carrying the donor's real coordinates re-opens the 2026-08-28 failure where a downstream
        // firstOrNull() adopted a borrowed row's site as the render location.
        val covered = row(37.417, -122.089, cloud = 65, dateTime = HOUR)
        val donor = row(37.424, -122.088, cloud = 31, dateTime = HOUR + ONE_HOUR)

        val unified = GraphDataLoader.unifyToNearestSite(listOf(donor, covered), widgetLat, widgetLon)

        // Pin the borrow first: without it the rest of this test passes vacuously on a one-row list.
        val borrowedRow = unified.single { it.dateTime == HOUR + ONE_HOUR }
        assertEquals(donor.cloudCover, borrowedRow.cloudCover)
        assertEquals(
            "output must stay coordinate-homogeneous",
            1,
            unified.map { it.locationLat to it.locationLon }.distinct().size,
        )
        assertEquals(
            "the borrowed row must carry the winning site, not the donor's coordinates",
            covered.locationLat to covered.locationLon,
            borrowedRow.locationLat to borrowedRow.locationLon,
        )
    }

    @Test
    fun doesNotBorrowFromBeyondTheNearbyFallbackTolerance() {
        val covered = row(37.417, -122.089, cloud = 65, dateTime = HOUR)
        // 0.027 deg away — a genuinely different place, not a jitter fragment.
        val tooFar = row(37.39, -122.081, cloud = 25, dateTime = HOUR + ONE_HOUR)

        val unified = GraphDataLoader.unifyToNearestSite(listOf(tooFar, covered), widgetLat, widgetLon)

        assertEquals(listOf(HOUR), unified.map { it.dateTime })
    }

    @Test
    fun genericRowDoesNotSuppressTheBorrowForTheRealSource() {
        // Keying coverage on dateTime alone would let this Generic row mark the hour "covered" and
        // silently drop the NWS borrow — the display source is what the graph actually draws.
        val covered = row(37.417, -122.089, cloud = 65, dateTime = HOUR)
        val genericAtSite = row(37.417, -122.089, cloud = 10, dateTime = HOUR + ONE_HOUR, source = "Generic")
        val nwsDonor = row(37.424, -122.088, cloud = 31, dateTime = HOUR + ONE_HOUR)

        val unified = GraphDataLoader.unifyToNearestSite(
            listOf(genericAtSite, nwsDonor, covered),
            widgetLat,
            widgetLon,
        )

        assertEquals(
            "NWS must still be borrowed for an hour only Generic covers at the winning site",
            listOf(31),
            unified.filter { it.dateTime == HOUR + ONE_HOUR && it.source == "NWS" }.map { it.cloudCover },
        )
    }

    @Test
    fun freshestDonorWinsAndRowOrderDoesNotDecide() {
        // Both donors are the same distance from the widget, so only fetchedAt may break the tie.
        // Row order deciding a collapse is what produced the -13.7 deg today-column delta.
        val covered = row(37.417, -122.089, cloud = 65, dateTime = HOUR)
        val stale = row(37.4238, -122.0889, cloud = 10, fetchedAt = 1_000L, dateTime = HOUR + ONE_HOUR)
        val fresh = row(37.4238, -122.0889, cloud = 31, fetchedAt = 9_000L, dateTime = HOUR + ONE_HOUR)

        val forward = GraphDataLoader.unifyToNearestSite(listOf(covered, stale, fresh), widgetLat, widgetLon)
        val reversed = GraphDataLoader.unifyToNearestSite(listOf(fresh, stale, covered), widgetLat, widgetLon)

        assertEquals(listOf(31), forward.filter { it.dateTime == HOUR + ONE_HOUR }.map { it.cloudCover })
        assertEquals(forward, reversed)
    }

    @Test
    fun coveredHourNeverPrefersANearbyFragment() {
        // The regression this collapse exists to prevent: DailyNoonCloudCover picking a stale
        // fragment over the fresh row FOR AN HOUR BOTH COVER. Borrowing must not reach those hours.
        val fresh = row(37.417, -122.089, cloud = 65, fetchedAt = 9_000L, dateTime = HOUR)
        val staleNearby = row(37.424, -122.088, cloud = 31, fetchedAt = 1_000L, dateTime = HOUR)

        val unified = GraphDataLoader.unifyToNearestSite(listOf(staleNearby, fresh), widgetLat, widgetLon)

        assertEquals(listOf(fresh), unified)
    }
}

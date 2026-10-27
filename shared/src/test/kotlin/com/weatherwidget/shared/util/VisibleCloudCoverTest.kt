package com.weatherwidget.shared.util

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/** The single resolver every cloud read in the app now goes through. */
@Category(ShortDuration::class)
class VisibleCloudCoverTest {

    @Test
    fun `the weighted sum of bands overrides the total`() {
        // low 50% * 90 + mid 25% * 100 + high 25% * 100 = 45 + 25 + 25 = 95
        assertEquals(95, VisibleCloudCover.of(total = 40, low = 90, mid = 100, high = 100))
    }

    @Test
    fun `a clear low layer under covered mid and high reports weighted sum`() {
        // low 50% * 2 + mid 25% * 63 + high 25% * 100 = 1 + 15.75 + 25 = 41.75 -> 42
        assertEquals(42, VisibleCloudCover.of(total = 95, low = 2, mid = 63, high = 100))
    }

    @Test
    fun `weighted sum of three bands without total`() {
        // low 50% * 19 + mid 25% * 75 + high 25% * 44 = 9.5 + 18.75 + 11 = 39.25 -> 39
        assertEquals(39, VisibleCloudCover.of(total = null, low = 19, mid = 75, high = 44))
    }

    @Test
    fun `a single band is enough and normalizes to full weight`() {
        assertEquals(44, VisibleCloudCover.of(total = null, mid = 44))
    }

    @Test
    fun `two bands normalize weights proportionally`() {
        // low 50% * 40 + mid 25% * 80 -> (20 + 20) / 0.75 = 40 / 0.75 = 53.33 -> 53
        assertEquals(53, VisibleCloudCover.of(total = null, low = 40, mid = 80))
    }

    @Test
    fun `zero bands compute to zero even if total is set`() {
        assertEquals(0, VisibleCloudCover.of(total = 100, low = 0, mid = 0, high = 0))
    }

    @Test
    fun `fallback to total when no bands are reported`() {
        assertEquals(75, VisibleCloudCover.of(total = 75))
        assertEquals(0, VisibleCloudCover.of(total = 0))
    }

    /** A missing value must never become a zero — that is a clear sky nobody observed. */
    @Test
    fun `nothing reported stays null`() {
        assertNull(VisibleCloudCover.of(total = null, low = null, mid = null, high = null))
    }

    @Test
    fun `out-of-range values are clamped`() {
        assertEquals(100, VisibleCloudCover.of(total = 140))
        assertEquals(0, VisibleCloudCover.of(total = -5))
        assertEquals(100, VisibleCloudCover.of(total = null, low = 120, mid = 110, high = 150))
    }

    @Test
    fun `the observation overload reads the same way`() {
        val row = ObservationReading(
            stationId = "KNUQ", stationName = "KNUQ", timestamp = 0L, temperature = 60f,
            condition = "Cloudy", locationLat = 37.42, locationLon = -122.08, api = "NWS",
            cloudCover = null, cloudCoverLow = 19, cloudCoverMid = 75,
        )
        // (0.50 * 19 + 0.25 * 75) / 0.75 = (9.5 + 18.75) / 0.75 = 37.67 -> 38
        assertEquals(38, with(VisibleCloudCover) { row.visibleCloudCover() })
    }
}

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
    fun `the total wins over every band`() {
        assertEquals(40, VisibleCloudCover.of(total = 40, low = 90, mid = 100, high = 100))
    }

    /**
     * The reversal itself: a row where the low layer is clear and the column is covered used to
     * report the low value, painting a clear sky over a covered one on 12.9% of stored hours.
     */
    @Test
    fun `a clear low layer under a covered column reports the column`() {
        assertEquals(95, VisibleCloudCover.of(total = 95, low = 2, mid = 63, high = 100))
    }

    /**
     * Station sources store no total by design — a METAR reports cumulative layers — so their bands
     * hold the whole report, and the max across them is the cumulative sky cover.
     */
    @Test
    fun `with no total the bands' maximum stands in`() {
        assertEquals(75, VisibleCloudCover.of(total = null, low = 19, mid = 75, high = 44))
    }

    @Test
    fun `a single band is enough`() {
        assertEquals(44, VisibleCloudCover.of(total = null, mid = 44))
    }

    /** A zero total is a report of a clear sky, not an absent value; it must not fall through. */
    @Test
    fun `a zero total is honoured over a present band`() {
        assertEquals(0, VisibleCloudCover.of(total = 0, low = 0, mid = 80))
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
    }

    @Test
    fun `the observation overload reads the same way`() {
        val row = ObservationReading(
            stationId = "KNUQ", stationName = "KNUQ", timestamp = 0L, temperature = 60f,
            condition = "Cloudy", locationLat = 37.42, locationLon = -122.08, api = "NWS",
            cloudCover = null, cloudCoverLow = 19, cloudCoverMid = 75,
        )
        assertEquals(75, with(VisibleCloudCover) { row.visibleCloudCover() })
    }
}

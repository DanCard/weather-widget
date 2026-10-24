package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class PriorDayBandForecastTest {

    private val hour = 3_600_000L
    private val target = 1_755_720_000_000L

    private fun snapshot(leadHours: Long, mid: Int? = 50, high: Int? = null) =
        PriorDayBandForecast.BandSnapshot(
            hourMs = target,
            bucketMs = target - leadHours * hour,
            bands = CloudBands(mid = mid, high = high),
        )

    @Test
    fun `picks the most recent snapshot that is still a day ahead`() {
        val selected = PriorDayBandForecast.select(
            listOf(
                snapshot(leadHours = 40, mid = 10),
                snapshot(leadHours = 26, mid = 20),
                snapshot(leadHours = 24, mid = 30),
            ),
        )

        assertEquals(CloudBands(mid = 30), selected[target])
    }

    /**
     * The rule that separates this from [com.weatherwidget.shared.util.DailySnapshotSelector],
     * which falls back to the earliest candidate. Here that fallback would file a prediction made
     * two hours before the hour as a day-ago forecast.
     */
    @Test
    fun `a snapshot made too close to the hour does not qualify`() {
        assertTrue(PriorDayBandForecast.select(listOf(snapshot(leadHours = 2))).isEmpty())
    }

    @Test
    fun `a snapshot from far too long ago does not qualify`() {
        val tooOld = PriorDayBandForecast.MAX_LEAD_MS / hour + 1
        assertTrue(PriorDayBandForecast.select(listOf(snapshot(leadHours = tooOld))).isEmpty())
    }

    @Test
    fun `exactly the lead time qualifies`() {
        val selected = PriorDayBandForecast.select(listOf(snapshot(leadHours = 24, mid = 77)))
        assertEquals(CloudBands(mid = 77), selected[target])
    }

    /** A row reporting neither band is not a prediction of a clear sky. */
    @Test
    fun `a snapshot with no bands at all is dropped`() {
        assertTrue(
            PriorDayBandForecast.select(
                listOf(snapshot(leadHours = 25, mid = null, high = null)),
            ).isEmpty(),
        )
    }

    @Test
    fun `one band present is enough to keep the snapshot`() {
        val selected = PriorDayBandForecast.select(
            listOf(snapshot(leadHours = 25, mid = null, high = 90)),
        )
        assertEquals(CloudBands(mid = null, high = 90), selected[target])
    }

    @Test
    fun `hours are selected independently`() {
        val other = target + 3 * hour
        val selected = PriorDayBandForecast.select(
            listOf(
                snapshot(leadHours = 25, mid = 11),
                PriorDayBandForecast.BandSnapshot(
                    hourMs = other,
                    bucketMs = other - 30 * hour,
                    bands = CloudBands(mid = 22),
                ),
            ),
        )

        assertEquals(CloudBands(mid = 11), selected[target])
        assertEquals(CloudBands(mid = 22), selected[other])
    }

    @Test
    fun `an empty input yields an empty map`() {
        assertTrue(PriorDayBandForecast.select(emptyList()).isEmpty())
    }

    @Test
    fun `selectAtSite ignores a neighbouring town and prefers the nearer fragment in one bucket`() {
        val lat = 37.417
        val lon = -122.089
        fun at(siteLat: Double, mid: Int, hourMs: Long = target) = PriorDayBandForecast.BandSnapshot(
            hourMs = hourMs, bucketMs = hourMs - 24 * hour, bands = CloudBands(mid = mid),
            locationLat = siteLat, locationLon = lon,
        )

        val selected = PriorDayBandForecast.selectAtSite(
            listOf(
                at(siteLat = 37.418, mid = 55), // jitter fragment, listed first
                at(siteLat = lat, mid = 30),
                at(siteLat = 37.467, mid = 90, hourMs = target + hour), // another town in the box
            ),
            lat, lon,
        )

        assertEquals(mapOf(target to CloudBands(mid = 30)), selected)
    }
}

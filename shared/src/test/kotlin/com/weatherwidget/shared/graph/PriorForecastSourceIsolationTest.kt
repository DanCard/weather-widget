package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * `OPEN_METEO_PRIOR24` rows live in `hourly_forecast_history` beside the app's own snapshots. They
 * must never surface as an Open-Meteo forecast.
 *
 * The hazard is specific: [HourlyForecastStitcher]'s history fallback picks the row with the
 * greatest `fetchedAt`, and prior-run rows are written *now* while describing a 24h-old prediction.
 * Filed under the real source id they would win as "the latest forecast" for any past hour that had
 * aged out of the live table — silently changing the temperature and precipitation graphs, which
 * read the same stitched list. The distinct source id is what prevents that, so it is asserted here
 * rather than assumed.
 */
@Category(ShortDuration::class)
class PriorForecastSourceIsolationTest {

    private val hour = 3_600_000L
    private val now = 1_755_720_000_000L
    private val lat = 37.417
    private val lon = -122.089

    private fun row(source: String, offsetHours: Long, cover: Int, fetchedAt: Long) = HourlyForecast(
        dateTime = now + offsetHours * hour,
        temperature = 60f,
        condition = "Cloudy",
        cloudCover = cover,
        source = source,
        fetchedAt = fetchedAt,
        locationLat = lat,
        locationLon = lon,
    )

    @Test
    fun `prior24 rows do not change the stitched forecast when a live row exists`() {
        val live = listOf(row("OPEN_METEO", -2, 50, now))
        val ownSnapshots = listOf(row("OPEN_METEO", -2, 100, now - 26 * hour))

        val without = HourlyForecastStitcher.stitch(live, ownSnapshots, now, lat, lon)
        val with = HourlyForecastStitcher.stitch(
            live,
            ownSnapshots + row(PriorDayCloudForecast.SOURCE_ID, -2, 100, now),
            now, lat, lon,
        )

        assertEquals(without, with)
    }

    /**
     * The dangerous case: no live row for the hour (aged out of the REPLACE-overwritten table), so
     * the stitcher falls through to history and picks by freshest `fetchedAt` — which is exactly
     * where a prior-run row would win.
     */
    @Test
    fun `prior24 rows do not win the history fallback for a past hour with no live row`() {
        val ownSnapshots = listOf(row("OPEN_METEO", -30, 100, now - 26 * hour))

        val without = HourlyForecastStitcher.stitch(emptyList(), ownSnapshots, now, lat, lon)
        val with = HourlyForecastStitcher.stitch(
            emptyList(),
            // Freshest fetchedAt in the set, and it describes a day-ago prediction.
            ownSnapshots + row(PriorDayCloudForecast.SOURCE_ID, -30, 7, now),
            now, lat, lon,
        )

        assertEquals(
            "a prior-run row must never become the latest forecast for an aged-out hour",
            without, with,
        )
    }

    @Test
    fun `prior24 source id is not the open-meteo source id`() {
        assertEquals(false, PriorDayCloudForecast.SOURCE_ID == "OPEN_METEO")
    }

    @Test
    fun `prediction bucket is the hour minus the lead time and is deterministic`() {
        val hourStart = now - 5 * hour
        assertEquals(hourStart - 24 * hour, PriorDayCloudForecast.predictionBucketFor(hourStart))
        assertEquals(
            PriorDayCloudForecast.predictionBucketFor(hourStart),
            PriorDayCloudForecast.predictionBucketFor(hourStart),
        )
    }
}

package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyNoonCloudCoverTest {

    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 6, 19)

    private fun hour(h: Int, cloud: Int?, source: String) = HourlyForecast(
        dateTime = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli(),
        temperature = 70f,
        condition = "Sunny",
        cloudCover = cloud,
        source = source,
    )

    private fun siteHour(
        cloud: Int,
        lat: Double,
        lon: Double,
        fetchedAt: Long,
    ) = hour(12, cloud, "NWS").copy(
        locationLat = lat,
        locationLon = lon,
        fetchedAt = fetchedAt,
    )

    private fun resolve(hourly: List<HourlyForecast>, displaySource: String, rowSource: String? = null) =
        DailyNoonCloudCover.resolveNoonCloudCoverPercent(hourly, date, displaySource, rowSource, zone)

    private fun resolveMeasured(hourly: List<HourlyForecast>, displaySource: String, rowSource: String? = null) =
        DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(hourly, date, displaySource, rowSource, zone)

    @Test
    fun returnsOnlyTheDisplayedSourcesCloud() {
        val hourly = listOf(
            hour(12, 20, "NWS"),
            hour(12, 80, "OPEN_METEO"),
        )
        assertEquals(20, resolve(hourly, "NWS"))
        assertEquals(80, resolve(hourly, "OPEN_METEO"))
    }

    @Test
    fun reportsTotalCloudWhenOpenMeteoTotalAndLowDiverge() {
        // Reversed 2026-08-27: the day icon reports the TOTAL, like every other cloud read. A noon
        // sample of "column overcast, ground clear" is an overcast noon — the icon saying otherwise
        // was the daily-view face of the 12.9% of hours where the graph painted a clear sky over a
        // covered one. See VisibleCloudCover.
        val hourly = listOf(
            hour(12, 100, WeatherSource.OPEN_METEO.id).copy(cloudCoverLow = 0),
        )

        assertEquals(100, resolve(hourly, WeatherSource.OPEN_METEO.id))
        assertEquals(100, resolveMeasured(hourly, WeatherSource.OPEN_METEO.id))
    }

    @Test
    fun assumesZeroWhenDisplayedSourceHasNoNoonData() {
        val hourly = listOf(hour(12, 80, "OPEN_METEO"))
        assertEquals(0, resolve(hourly, "NWS"))
    }

    @Test
    fun measuredReturnsNullWhenDisplayedSourceHasNoNoonData() {
        val hourly = listOf(hour(12, 80, "OPEN_METEO"))
        assertEquals(null, resolveMeasured(hourly, "NWS"))
    }

    @Test
    fun usesExactNoonReadingNotNearestHour() {
        val hourly = listOf(
            hour(10, 90, "NWS"),
            hour(12, 22, "NWS"),
            hour(14, 5, "NWS"),
        )
        assertEquals(22, resolve(hourly, "NWS"))
    }

    @Test
    fun assumesZeroWhenNoonIsAbsentEvenIfOtherHoursExist() {
        val hourly = listOf(
            hour(7, 69, "NWS"),
            hour(11, 65, "NWS"),
            hour(13, 25, "NWS"),
        )
        assertEquals(0, resolve(hourly, "NWS"))
    }

    @Test
    fun assumesZeroWhenNoonRowHasNullCloudCover() {
        val hourly = listOf(hour(12, null, "NWS"))
        assertEquals(0, resolve(hourly, "NWS"))
    }

    @Test
    fun genericGapRowUsesGenericHourly() {
        val hourly = listOf(
            hour(12, 50, "NWS"),
            hour(12, 35, WeatherSource.GENERIC_GAP.id),
        )
        assertEquals(35, resolve(hourly, "NWS", rowSource = WeatherSource.GENERIC_GAP.id))
    }

    @Test
    fun freshestNoonRowWinsAmongSameSiteDuplicates() {
        // One site accumulates a noon row per fetch, and unifying to the nearest site does NOT
        // reduce them to one: on the Fold, 37.416,-122.087 sits inside the same-site box of
        // 37.417,-122.089 (dlat 0.001, dlon 0.002 against a 0.002 tolerance), so both survive.
        // Taking the first match made the daily bar show a five-day-old 26% beside an hourly
        // graph drawing the current 50%.
        val hourly = listOf(
            hour(12, 26, "NWS").copy(fetchedAt = 1_000L), // five days old, sorted first
            hour(12, 50, "NWS").copy(fetchedAt = 9_000L), // current forecast
        )
        assertEquals(50, resolve(hourly, "NWS"))
    }

    @Test
    fun noonSelectionIsIndependentOfRowOrder() {
        // The flap itself: the winner used to follow row order, which follows the query window
        // (ORDER BY dateTime ASC breaks ties arbitrarily), so two render paths reading the same
        // database disagreed. Freshest-wins is order-free.
        val stale = hour(12, 26, "NWS").copy(fetchedAt = 1_000L)
        val fresh = hour(12, 50, "NWS").copy(fetchedAt = 9_000L)

        assertEquals(50, resolve(listOf(stale, fresh), "NWS"))
        assertEquals(50, resolve(listOf(fresh, stale), "NWS"))
    }

    @Test
    fun tieOnFetchedAtKeepsTheFirstRow() {
        // Synthesized rows (climate-normal gap fills) carry no fetchedAt, so every candidate ties
        // at 0. Those must behave exactly as they did before freshest-wins: first row.
        val hourly = listOf(
            hour(12, 26, "NWS"),
            hour(12, 50, "NWS"),
        )
        assertEquals(26, resolve(hourly, "NWS"))
    }

    @Test
    fun olderRowWithCloudBeatsFresherRowWithoutOne() {
        // Freshest row THAT CARRIES A VALUE — a fresher row with no cloud reading must not blank
        // the day, which is what selecting purely by fetchedAt would do.
        val hourly = listOf(
            hour(12, 40, "NWS").copy(fetchedAt = 1_000L),
            hour(12, null, "NWS").copy(fetchedAt = 9_000L),
        )
        assertEquals(40, resolve(hourly, "NWS"))
    }

    @Test
    fun readsTotalCloudFromTheFreshestRow() {
        // The freshest-row rule is what this pins, and it survived the 2026-08-27 switch from the
        // low layer to the total: reading the stale row would report 90 under a sky the fresh row
        // calls clear. The discriminator moved from cloudCoverLow to cloudCover, the rule did not.
        val hourly = listOf(
            hour(12, 90, WeatherSource.OPEN_METEO.id).copy(cloudCoverLow = 90, fetchedAt = 1_000L),
            hour(12, 0, WeatherSource.OPEN_METEO.id).copy(cloudCoverLow = 0, fetchedAt = 9_000L),
        )
        assertEquals(0, resolve(hourly, WeatherSource.OPEN_METEO.id))
    }

    @Test
    fun siteAwareResolutionSelectsFreshDisplaySiteInsteadOfFirstNeighbor() {
        val hourly = listOf(
            siteHour(cloud = 25, lat = 37.37, lon = -122.06, fetchedAt = 1_000L),
            siteHour(cloud = 65, lat = 37.42, lon = -122.08, fetchedAt = 2_000L),
        )

        assertEquals(
            65,
            DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercentAtSite(
                hourly = hourly,
                date = date,
                displaySourceId = "NWS",
                centerLat = 37.42,
                centerLon = -122.08,
                zone = zone,
            ),
        )
    }

    @Test
    fun assumesZeroForOtherDates() {
        val otherDay = date.plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val hourly = listOf(
            HourlyForecast(otherDay, 70f, "Sunny", cloudCover = 99, source = "NWS"),
        )
        assertEquals(0, resolve(hourly, "NWS"))
    }
}

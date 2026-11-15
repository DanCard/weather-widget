package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * [ActualsAggregator.aggregate] builds only the rows its caller wants, and a wanted row is exactly
 * the row the unrestricted call builds. Compared against the unrestricted computation, not against
 * hardcoded temperatures: the point is that skipping changes nothing that is kept
 * (performance/261115-phase3-targeted-refresh-and-daily-blend-cost.md).
 */
@Category(ShortDuration::class)
class ActualsAggregatorWantedRowsTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 6, 21)
    private val lat = 37.4220
    private val lon = -122.0841
    private val updatedAt = 1_750_000_000_000L

    @Before
    fun reset() {
        ActualsAggregator.blendCache.clear()
        ActualsProviderResolver.resetLocationSource()
        // Two sources take their actuals from NWS's rows, so the aggregator runs three groups over
        // the same observations, each against its own forecast — the Pixel's shape (groups=7).
        ActualsProviderResolver.installPreferenceSource { source ->
            WeatherSource.NWS.takeIf { source == WeatherSource.OPEN_METEO || source == WeatherSource.SILURIAN }
        }
    }

    @After
    fun tearDown() = ActualsProviderResolver.resetPreferenceSource()

    private fun epoch(date: LocalDate, hour: Int, minute: Int = 0) =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Three days, two stations with different shapes and a reading gap, so the blend has work to do. */
    private fun observations(): List<ObservationReading> =
        (-1L..1L).flatMap { offset ->
            val date = day.plusDays(offset)
            (0..23).flatMap { h ->
                listOfNotNull(
                    reading("KNUQ", 2f, epoch(date, h), 55f + h + offset * 2),
                    // KPAO misses the small hours and reports off the hour.
                    if (h in 2..5) null else reading("KPAO", 6f, epoch(date, h, 47), 58f + (h % 12) - offset),
                )
            }
        }

    private fun reading(station: String, km: Float, timestamp: Long, temp: Float) = ObservationReading(
        stationId = station, stationName = station, timestamp = timestamp, temperature = temp,
        condition = if (temp > 70f) "Clear" else "Cloudy", locationLat = lat, locationLon = lon, distanceKm = km,
        api = WeatherSource.NWS.id, stationType = StationType.OFFICIAL,
    )

    private fun forecasts(): List<HourlyForecast> =
        WeatherSource.entries.flatMap { source ->
            (-1L..1L).flatMap { offset ->
                (0..23).map { h ->
                    HourlyForecast(
                        dateTime = epoch(day.plusDays(offset), h), temperature = 60f + h / 2f, condition = "Clear",
                        source = source.id, precipAmountMm = if (h == 14) 0.4f else null,
                    )
                }
            }
        }

    private fun aggregate(
        wanted: ((String, LocalDate) -> Boolean)? = null,
        stats: ActualsAggregator.AggregateStats? = null,
    ) = ActualsAggregator.aggregate(
        observations = observations(), hourlyForecasts = forecasts(), locationLat = lat, locationLon = lon,
        zoneId = zone, updatedAtMs = updatedAt, wanted = wanted, stats = stats,
    )

    private fun dateOf(epochDayMs: Long) = LocalDate.ofEpochDay(epochDayMs / 86_400_000L)

    @Test
    fun `the rows for a wanted date equal the unrestricted call's rows for it`() {
        val allStats = ActualsAggregator.AggregateStats()
        val all = aggregate(stats = allStats)
        val oneStats = ActualsAggregator.AggregateStats()
        val one = aggregate(wanted = { _, date -> date == day }, stats = oneStats)

        println("ActualsAggregatorWantedRowsTest: all=${allStats.logFragment()} one=${oneStats.logFragment()} rows=${one.size}")
        val expected = all.filter { dateOf(it.date) == day }
        assertTrue("the fixture yields rows for several sources: ${expected.map { it.source }}", expected.size >= 2)
        assertEquals(expected.sortedBy { it.source }, one.sortedBy { it.source })
        // Three dates per group without the restriction, one with it.
        assertEquals(3 * allStats.groups, allStats.datesBlended)
        assertEquals(oneStats.groups, oneStats.datesBlended)
        assertEquals(2 * oneStats.groups, oneStats.datesSkipped)
    }

    @Test
    fun `the restriction is per source as well as per date`() {
        val all = aggregate()
        val source = all.first().source
        val keep = { s: String, date: LocalDate -> date == day || s == source }

        val some = aggregate(wanted = keep)

        assertEquals(
            all.filter { keep(it.source, dateOf(it.date)) }.sortedBy { it.source + it.date },
            some.sortedBy { it.source + it.date },
        )
        assertTrue("the kept source has its neighbouring days", some.count { it.source == source } == 3)
    }

    @Test
    fun `no restriction builds every date, as before`() {
        val stats = ActualsAggregator.AggregateStats()
        val rows = aggregate(stats = stats)

        assertEquals(0, stats.datesSkipped)
        assertEquals(setOf(day.minusDays(1), day, day.plusDays(1)), rows.map { dateOf(it.date) }.toSet())
    }
}

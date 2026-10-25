package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * Desktop's observation writes follow the shared [com.weatherwidget.shared.observations.ObservationWriteRun]
 * (`plans/261024-shared-observation-persist-rules.md`). Each case failed on the old `persistObservations`:
 * it never thinned personal stations (O1), re-tagged Synoptic only (O2), logged the Tomorrow.io line only
 * when the displayed source borrowed Tomorrow.io (O4), and stamped every row with the persist time (O5).
 */
@Category(ShortDuration::class)
class DesktopObservationWriteRulesTest {
    private lateinit var path: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private val service = mockk<WeatherApiClient>()

    private val lat = 37.4220
    private val lon = -122.0841
    private val min = 60_000L
    private val now = (System.currentTimeMillis() / 3_600_000L) * 3_600_000L

    @Before
    fun setup() {
        path = Files.createTempFile("weather-obs-write-rules", ".db")
        database = DesktopWeatherDatabase(path).apply { initialize() }
        dao = DesktopWeatherDao(database)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(path)
    }

    private fun repository(source: String) = DesktopWeatherRepository(service, dao, lat, lon, source, currentTimeMillis = { now })

    private fun reading(
        station: String,
        timestamp: Long,
        type: StationType,
        api: String = WeatherSource.NWS.id,
        fetchedAt: Long = now,
    ) = ObservationReading(
        stationId = station, stationName = station, timestamp = timestamp, temperature = 60f, condition = "Clear",
        locationLat = lat, locationLon = lon, stationType = type, api = api, fetchedAt = fetchedAt,
    )

    private fun stored(station: String) = dao.getRecentObservations(0L).filter { it.stationId == station }.sortedBy { it.timestamp }

    @Test
    fun `an NWS personal station reporting every 5 minutes is stored at one row per 10 minutes plus its newest`() = runTest {
        dao.upsertHourlyForecasts(lat, lon, WeatherSource.NWS.id, listOf(HourlyForecast(now, 72f, "Clear")))
        val start = now - 60 * min
        coEvery { service.fetchFeedObservations(any(), recentOnly = any()) } returns RawFetch(
            rawObservations = (0 until 6).map { reading("F4751", start + it * 5 * min, StationType.PERSONAL) },
        )

        repository(WeatherSource.NWS.id).refreshObservations()

        assertEquals(listOf(start, start + 10 * min, start + 20 * min, start + 25 * min), stored("F4751").map { it.timestamp })
    }

    @Test
    fun `an NWS station's reclassification reaches its stored rows`() = runTest {
        dao.upsertHourlyForecasts(lat, lon, WeatherSource.NWS.id, listOf(HourlyForecast(now, 72f, "Clear")))
        coEvery { service.fetchFeedObservations(any(), recentOnly = any()) } returnsMany listOf(
            RawFetch(rawObservations = listOf(reading("KXYZ", now - 60 * min, StationType.OFFICIAL))),
            RawFetch(rawObservations = listOf(reading("KXYZ", now - 20 * min, StationType.RAWS))),
        )
        val repository = repository(WeatherSource.NWS.id)

        repository.refreshObservations()
        repository.refreshObservations()

        assertEquals(listOf(StationType.RAWS, StationType.RAWS), stored("KXYZ").map { it.stationType })
    }

    @Test
    fun `a row keeps the fetchedAt its reading carries`() = runTest {
        dao.upsertHourlyForecasts(lat, lon, WeatherSource.NWS.id, listOf(HourlyForecast(now, 72f, "Clear")))
        coEvery { service.fetchFeedObservations(any(), recentOnly = any()) } returns RawFetch(
            rawObservations = listOf(reading("KSJC", now - 10 * min, StationType.OFFICIAL, fetchedAt = now - 7 * min)),
        )

        repository(WeatherSource.NWS.id).refreshObservations()

        assertEquals(listOf(now - 7 * min), stored("KSJC").map { it.fetchedAt })
    }

    @Test
    fun `a Tomorrow io fetch logs its five-minute line while another source is displayed`() = runTest {
        // The current-conditions run's freshness and throttle memory is process-wide: its own site and a
        // clock a year ahead keep this case from depending on which other tests ran first.
        val siteLat = 40.7128
        val siteLon = -74.006
        val later = now + 365L * 24 * 60 * min
        dao.upsertHourlyForecasts(siteLat, siteLon, WeatherSource.OPEN_METEO.id, listOf(HourlyForecast(later, 72f, "Clear")))
        val fiveMinute = reading(
            TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, later - 5 * min, StationType.UNKNOWN, api = WeatherSource.TOMORROW_IO.id,
        ).copy(locationLat = siteLat, locationLon = siteLon)
        coEvery { service.fetchFeedObservations(WeatherSource.TOMORROW_IO, recentOnly = any()) } returns RawFetch(rawObservations = listOf(fiveMinute))
        coEvery { service.fetchFeedObservations(WeatherSource.OPEN_METEO, recentOnly = any()) } returns RawFetch()

        DesktopWeatherRepository(service, dao, siteLat, siteLon, WeatherSource.OPEN_METEO.id, currentTimeMillis = { later })
            .refreshObservations(visibleSources = listOf(WeatherSource.OPEN_METEO, WeatherSource.TOMORROW_IO))

        val lines = dao.getRecentLogsByTags(listOf(TomorrowIoActuals.FETCH_TAG)).map { it.message }
        val runLog = dao.getRecentLogs().filter { it.tag.startsWith("CURR_FETCH") }.map { "${it.tag} ${it.message}" }
        assertTrue("no five-minute line in $lines; run: $runLog", lines.any { it.startsWith("windowHours=") && "rows=1" in it })
    }
}

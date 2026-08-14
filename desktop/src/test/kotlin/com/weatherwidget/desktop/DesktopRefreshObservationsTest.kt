package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.ForecastResult
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import org.junit.experimental.categories.Category

/**
 * Guards the `refreshObservations()` return contract that keeps the genmon panel and the desktop
 * popup showing the SAME temperature.
 *
 * The daemon stores the value `refreshObservations()` returns in `forecastState`, and the panel
 * re-resolves its temperature from `forecastState.rawObservations`; the popup, a separate process,
 * re-reads observations from the DB (`loadCached`). If the returned result carried the freshly
 * fetched *network* list verbatim, the panel's IDW blend could see a slightly different observation
 * set than the popup (e.g. a reading outside `loadCached`'s query range) and the two displays
 * disagreed. The result must instead carry the DB-derived `loadCached().rawObservations`.
 */
@Category(ShortDuration::class)
class DesktopRefreshObservationsTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository
    private val weatherService = mockk<DesktopWeatherService>()

    private val lat = 37.4220
    private val lon = -122.0841
    private val source = "NWS"

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-refresh-obs-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
        repository = DesktopWeatherRepository(weatherService, dao, lat, lon, source)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `refreshObservations returns DB-derived observations not the raw network list`() = runTest {
        val now = (System.currentTimeMillis() / 3600_000L) * 3600_000L

        // A non-empty cache is required for loadCached to return a ForecastResult (it returns null
        // only when both hourly and daily are empty).
        dao.upsertHourlyForecasts(
            lat, lon, source,
            listOf(HourlyForecast(now, 72f, "Clear")),
        )

        val inRange = ObservationReading(
            stationId = "IN_RANGE",
            stationName = "In Range",
            timestamp = now,
            temperature = 73f,
            condition = "Clear",
            locationLat = lat,
            locationLon = lon,
            api = "NWS",
        )
        // Outside loadCached's observation query (obsEnd = now + 2h): it is upserted but not read
        // back, so a DB-derived rawObservations list must exclude it while the network list has it.
        val outOfRange = ObservationReading(
            stationId = "OUT_OF_RANGE",
            stationName = "Out Of Range",
            timestamp = now + 5 * 3600_000L,
            temperature = 999f,
            condition = "Clear",
            locationLat = lat,
            locationLon = lon,
            api = "NWS",
        )
        coEvery { weatherService.fetchObservationsOnly() } returns ForecastResult(
            rawObservations = listOf(inRange, outOfRange),
        )

        val returned = repository.refreshObservations()

        assertEquals(1, returned.raw.rawObservations.size)
        assertEquals("IN_RANGE", returned.raw.rawObservations.single().stationId)
        assertTrue(
            "the panel must resolve from the same observations the popup reads from the DB",
            returned.raw.rawObservations.none { it.stationId == "OUT_OF_RANGE" },
        )
    }
}

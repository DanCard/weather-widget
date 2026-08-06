package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.MediumDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Desktop sibling of the Android NwsGridpointActualsStoreTest — the same "missing API actual"
 * bug: shortly after midnight the NWS gridpoint response still carries yesterday's
 * maxTemperature window while yesterday's minTemperature window has rolled off. The persist
 * path must not erase a previously stored apiLowTemp (upsertDailyHistory is a full-row
 * REPLACE), and the ERA5 backfill must treat a null high OR low as incomplete and fill only
 * the absent field.
 */
@Category(MediumDuration::class)
class DesktopApiActualsMergeTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var weatherService: DesktopWeatherService
    private lateinit var repository: DesktopWeatherRepository

    private val lat = 37.416824
    private val lon = -122.08898
    private val source = WeatherSource.NWS.id
    private val yesterday = LocalDate.now().minusDays(1)
    private val yesterdayStr = yesterday.toString()
    private val yesterdayEpoch = yesterday.toEpochDay() * 86_400_000L

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-api-actuals-test", ".db")
        db = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(db)
        weatherService = mockk()
        repository = DesktopWeatherRepository(weatherService, dao, lat, lon, source)
    }

    @After
    fun teardown() {
        db.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private fun nwsRow(apiHigh: Float?, apiLow: Float?) = DailyHistory(
        date = yesterdayEpoch,
        source = source,
        locationLat = lat,
        locationLon = lon,
        computedHighTemp = 75.06f,
        computedLowTemp = 60.73f,
        condition = "Clear",
        updatedAt = System.currentTimeMillis(),
        forecastHighTemp = 78f,
        forecastLowTemp = 58f,
        apiHighTemp = apiHigh,
        apiLowTemp = apiLow,
    )

    private fun storedNwsRow() = dao.getExtremesInRange(yesterdayEpoch, yesterdayEpoch, lat, lon)
        .single { it.source == source }

    @Test
    fun `partial gridpoint response preserves existing low and other columns`() {
        dao.upsertDailyHistory(listOf(nwsRow(apiHigh = 77.2f, apiLow = 56.1f)))

        repository.persistNwsApiActuals(
            NwsApi.DailyTemperatureExtremes(
                maxByDate = mapOf(yesterdayStr to 82.0f),
                minByDate = emptyMap(),
            ),
        )

        val stored = storedNwsRow()
        assertEquals(82.0f, stored.apiHighTemp)
        assertEquals("null minTemperature must not clobber the stored low", 56.1f, stored.apiLowTemp)
        assertEquals("computed values must survive the full-row REPLACE", 75.06f, stored.computedHighTemp)
        assertEquals(60.73f, stored.computedLowTemp)
        assertEquals("Clear", stored.condition)
        assertEquals(78f, stored.forecastHighTemp)
        assertEquals(58f, stored.forecastLowTemp)
    }

    @Test
    fun `full gridpoint response overwrites both api values`() {
        dao.upsertDailyHistory(listOf(nwsRow(apiHigh = 77.2f, apiLow = 56.1f)))

        repository.persistNwsApiActuals(
            NwsApi.DailyTemperatureExtremes(
                maxByDate = mapOf(yesterdayStr to 80.0f),
                minByDate = mapOf(yesterdayStr to 55.0f),
            ),
        )

        val stored = storedNwsRow()
        assertEquals(80.0f, stored.apiHighTemp)
        assertEquals(55.0f, stored.apiLowTemp)
    }

    @Test
    fun `backfill fills only the missing low and preserves the gridpoint high`() = runTest {
        dao.upsertDailyHistory(listOf(nwsRow(apiHigh = 82.0f, apiLow = null)))
        coEvery { weatherService.fetchHistoricalDailyTemps(any(), any()) } returns listOf(
            DailyForecast(date = yesterdayStr, highTemp = 79.0f, lowTemp = 55.5f, condition = "Clear"),
        )

        repository.backfillNwsApiActualsFromObservations(System.currentTimeMillis())

        val stored = storedNwsRow()
        assertEquals("gridpoint high must be preserved", 82.0f, stored.apiHighTemp)
        assertEquals("archive fills only the missing low", 55.5f, stored.apiLowTemp)
    }

    @Test
    fun `backfill treats a null low as incomplete`() = runTest {
        dao.upsertDailyHistory(listOf(nwsRow(apiHigh = 82.0f, apiLow = null)))
        coEvery { weatherService.fetchHistoricalDailyTemps(any(), any()) } returns listOf(
            DailyForecast(date = yesterdayStr, highTemp = 79.0f, lowTemp = 55.5f, condition = "Clear"),
        )

        repository.backfillNwsApiActualsFromObservations(System.currentTimeMillis())

        // If the row were not considered incomplete, the archive fetch would still have left low null.
        assertEquals(55.5f, storedNwsRow().apiLowTemp)
    }

    @Test
    fun `open-meteo persist sets both api values and preserves existing columns`() {
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = yesterdayEpoch,
                    source = WeatherSource.OPEN_METEO.id,
                    locationLat = lat,
                    locationLon = lon,
                    computedHighTemp = 75.5f,
                    computedLowTemp = 58.4f,
                    condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    forecastHighTemp = 76f,
                    forecastLowTemp = 57f,
                ),
            ),
        )

        repository.persistOpenMeteoApiActuals(
            listOf(DailyForecast(date = yesterdayStr, highTemp = 75.5f, lowTemp = 59.1f, condition = "Clear")),
        )

        val stored = dao.getExtremesInRange(yesterdayEpoch, yesterdayEpoch, lat, lon)
            .single { it.source == WeatherSource.OPEN_METEO.id }
        assertEquals(75.5f, stored.apiHighTemp)
        assertEquals(59.1f, stored.apiLowTemp)
        assertEquals(75.5f, stored.computedHighTemp)
        assertEquals(76f, stored.forecastHighTemp)
    }
}

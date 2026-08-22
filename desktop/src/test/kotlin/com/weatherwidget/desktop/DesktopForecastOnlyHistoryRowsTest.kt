package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Desktop twin of Android's ForecastOnlyHistoryRowsTest — see
 * plans/260822-daily-history-forecast-only-rows.md.
 */
@Category(ShortDuration::class)
class DesktopForecastOnlyHistoryRowsTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository

    private val lat = 37.42
    private val lon = -122.08
    private val source = "OPEN_METEO"
    private val today = LocalDate.now()
    private val yesterday = today.minusDays(1)

    private fun epochDay(date: LocalDate) = date.toEpochDay() * 86_400_000L

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-forecast-only-test", ".db")
        db = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(db)
        repository = DesktopWeatherRepository(DesktopWeatherService(lat, lon, source), dao, lat, lon, source)
    }

    @After
    fun teardown() {
        db.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `creates forecast-only rows for past days with snapshots but no history row`() {
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(date = yesterday.toString(), highTemp = 73.6f, lowTemp = 58.3f, condition = "Clear", source = source),
                DailyForecast(date = today.toString(), highTemp = 80f, lowTemp = 60f, condition = "Clear", source = source),
            ),
        )

        repository.ensureForecastOnlyHistoryRows(System.currentTimeMillis())

        val rows = dao.getExtremesInRange(epochDay(yesterday), epochDay(today), lat, lon)
        val row = rows.single { it.source == source && it.date == epochDay(yesterday) }
        assertNull("no actual may be fabricated", row.computedHighTemp)
        assertNull(row.computedLowTemp)
        // For-storage rounding: non-today forecast values are stored rounded (ForecastTempRounding).
        assertEquals(74f, row.forecastHighTemp)
        assertEquals(58f, row.forecastLowTemp)
        assertEquals(DailyHistoryWriter.FORECAST_ONLY_ROW.storedValue, row.lastWriter)
        assertTrue("today must never get a forecast-only row", rows.none { it.date == epochDay(today) })
    }

    @Test
    fun `is idempotent and leaves real-actuals rows alone`() {
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(date = yesterday.toString(), highTemp = 73f, lowTemp = 58f, condition = "Clear", source = source),
            ),
        )
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = epochDay(yesterday), source = "NWS",
                    locationLat = lat, locationLon = lon,
                    computedHighTemp = 70f, computedLowTemp = 55f, condition = "Clear",
                    updatedAt = 1L,
                ),
            ),
        )

        repository.ensureForecastOnlyHistoryRows(System.currentTimeMillis())
        repository.ensureForecastOnlyHistoryRows(System.currentTimeMillis())

        val rows = dao.getExtremesInRange(epochDay(yesterday), epochDay(yesterday), lat, lon)
        assertEquals("one Meteo row + the untouched NWS row, no duplicates", 2, rows.size)
        assertEquals(70f, rows.single { it.source == "NWS" }.computedHighTemp)
    }
}

package com.weatherwidget.stats

import com.weatherwidget.data.local.DailyExtremeEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Integration tests for AccuracyCalculator using a real in-memory Room database.
 *
 * Each test inserts DailyExtremeEntity (actuals) and ForecastEntity (1-day-ahead snapshot),
 * then verifies that getDailyAccuracyBreakdown() and calculateAccuracy() return correct values.
 */
@RunWith(RobolectricTestRunner::class)
class AccuracyCalculatorIntegrationTest {

    private lateinit var db: WeatherDatabase
    private lateinit var calculator: AccuracyCalculator

    private val lat = 37.42
    private val lon = -122.08
    private val today = LocalDate.now()

    @Before
    fun setup() {
        db = TestDatabase.create()
        calculator = AccuracyCalculator(db.forecastDao(), db.dailyExtremeDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun dateStr(daysAgo: Int): String =
        today.minusDays(daysAgo.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)

    private suspend fun insertExtreme(
        date: String,
        source: WeatherSource,
        highTemp: Float,
        lowTemp: Float,
        condition: String = "Clear",
    ) {
        db.dailyExtremeDao().insertAll(
            listOf(
                DailyExtremeEntity(
                    date = date,
                    source = source.id,
                    locationLat = lat,
                    locationLon = lon,
                    highTemp = highTemp,
                    lowTemp = lowTemp,
                    condition = condition,
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
    }

    /**
     * Inserts a 1-day-ahead forecast snapshot. [targetDate] is the day being predicted,
     * [forecastDate] is the day the snapshot was captured (targetDate - 1 for 1-day-ahead).
     */
    private suspend fun insertForecastSnapshot(
        targetDate: String,
        forecastDate: String,
        source: WeatherSource,
        highTemp: Float,
        lowTemp: Float,
        fetchedAt: Long = System.currentTimeMillis(),
    ) {
        db.forecastDao().insertAll(
            listOf(
                ForecastEntity(
                    targetDate = targetDate,
                    forecastDate = forecastDate,
                    locationLat = lat,
                    locationLon = lon,
                    locationName = "Test",
                    highTemp = highTemp,
                    lowTemp = lowTemp,
                    condition = "Clear",
                    source = source.id,
                    fetchedAt = fetchedAt,
                    batchFetchedAt = fetchedAt,
                ),
            ),
        )
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `getDailyAccuracyBreakdown returns empty when no data`() = runTest {
        val result = calculator.getDailyAccuracyBreakdown(WeatherSource.NWS, lat, lon, 30)
        assertTrue("Expected empty list when DB is empty", result.isEmpty())
    }

    @Test
    fun `getDailyAccuracyBreakdown returns empty when extremes exist but no forecast snapshot`() = runTest {
        insertExtreme(dateStr(1), WeatherSource.NWS, highTemp = 70f, lowTemp = 50f)

        val result = calculator.getDailyAccuracyBreakdown(WeatherSource.NWS, lat, lon, 30)
        assertTrue("No snapshot means no accuracy data", result.isEmpty())
    }

    @Test
    fun `getDailyAccuracyBreakdown returns empty when forecast snapshot exists but no extremes`() = runTest {
        val target = dateStr(1)
        val forecastMade = dateStr(2)
        insertForecastSnapshot(target, forecastMade, WeatherSource.NWS, highTemp = 68f, lowTemp = 48f)

        val result = calculator.getDailyAccuracyBreakdown(WeatherSource.NWS, lat, lon, 30)
        assertTrue("No extremes means no accuracy data", result.isEmpty())
    }

    @Test
    fun `getDailyAccuracyBreakdown computes correct errors for single day`() = runTest {
        val target = dateStr(1)
        val forecastMade = dateStr(2) // 1-day-ahead: forecast captured the day before the target

        insertExtreme(target, WeatherSource.NWS, highTemp = 72f, lowTemp = 50f)
        insertForecastSnapshot(target, forecastMade, WeatherSource.NWS, highTemp = 68f, lowTemp = 53f)

        val result = calculator.getDailyAccuracyBreakdown(WeatherSource.NWS, lat, lon, 30)

        assertEquals(1, result.size)
        val day = result[0]
        assertEquals(target, day.date)
        assertEquals(72, day.actualHigh)
        assertEquals(50, day.actualLow)
        assertEquals(68, day.forecastHigh)
        assertEquals(53, day.forecastLow)
        assertEquals(+4, day.highError)  // actual - forecast = 72 - 68
        assertEquals(-3, day.lowError)   // actual - forecast = 50 - 53
    }

    @Test
    fun `getDailyAccuracyBreakdown selects latest snapshot when multiple exist for same day`() = runTest {
        val target = dateStr(1)
        val forecastMade = dateStr(2)
        val olderTs = System.currentTimeMillis() - 3_600_000L
        val newerTs = System.currentTimeMillis()

        insertForecastSnapshot(target, forecastMade, WeatherSource.NWS, highTemp = 60f, lowTemp = 40f, fetchedAt = olderTs)
        insertForecastSnapshot(target, forecastMade, WeatherSource.NWS, highTemp = 65f, lowTemp = 45f, fetchedAt = newerTs)
        insertExtreme(target, WeatherSource.NWS, highTemp = 70f, lowTemp = 50f)

        val result = calculator.getDailyAccuracyBreakdown(WeatherSource.NWS, lat, lon, 30)

        assertEquals(1, result.size)
        // Latest snapshot (65/45) should be used, not the older one (60/40)
        assertEquals(65, result[0].forecastHigh)
        assertEquals(45, result[0].forecastLow)
        assertEquals(+5, result[0].highError)
        assertEquals(+5, result[0].lowError)
    }

    @Test
    fun `getDailyAccuracyBreakdown handles multiple days`() = runTest {
        for (daysAgo in 1..3) {
            val target = dateStr(daysAgo)
            val forecastMade = dateStr(daysAgo + 1)
            insertExtreme(target, WeatherSource.NWS, highTemp = 70f, lowTemp = 50f)
            insertForecastSnapshot(target, forecastMade, WeatherSource.NWS, highTemp = 68f, lowTemp = 52f)
        }

        val result = calculator.getDailyAccuracyBreakdown(WeatherSource.NWS, lat, lon, 30)

        assertEquals(3, result.size)
        // Results are sorted by date ascending
        assertTrue(result[0].date < result[1].date)
        assertTrue(result[1].date < result[2].date)
        result.forEach { day ->
            assertEquals(+2, day.highError)
            assertEquals(-2, day.lowError)
        }
    }

    @Test
    fun `getDailyAccuracyBreakdown does not mix NWS and Open-Meteo extremes`() = runTest {
        val target = dateStr(1)
        val forecastMade = dateStr(2)

        // NWS actual but Open-Meteo snapshot — should NOT match
        insertExtreme(target, WeatherSource.NWS, highTemp = 72f, lowTemp = 50f)
        insertForecastSnapshot(target, forecastMade, WeatherSource.OPEN_METEO, highTemp = 68f, lowTemp = 48f)

        val nwsResult = calculator.getDailyAccuracyBreakdown(WeatherSource.NWS, lat, lon, 30)
        val meteoResult = calculator.getDailyAccuracyBreakdown(WeatherSource.OPEN_METEO, lat, lon, 30)

        // NWS has an actual but no NWS snapshot → empty
        assertTrue("NWS should have no result without NWS snapshot", nwsResult.isEmpty())
        // Open-Meteo has a snapshot but no Open-Meteo actual → empty
        assertTrue("Open-Meteo should have no result without Open-Meteo actual", meteoResult.isEmpty())
    }

    @Test
    fun `getDailyAccuracyBreakdown ignores data outside 30-day window`() = runTest {
        val target = dateStr(35) // outside 30-day window
        val forecastMade = dateStr(36)

        insertExtreme(target, WeatherSource.NWS, highTemp = 70f, lowTemp = 50f)
        insertForecastSnapshot(target, forecastMade, WeatherSource.NWS, highTemp = 68f, lowTemp = 52f)

        val result = calculator.getDailyAccuracyBreakdown(WeatherSource.NWS, lat, lon, 30)
        assertTrue("Data older than 30 days should be excluded", result.isEmpty())
    }

    @Test
    fun `calculateAccuracy returns null when no data`() = runTest {
        val result = calculator.calculateAccuracy(WeatherSource.NWS, lat, lon, 30)
        assertNull(result)
    }

    @Test
    fun `calculateAccuracy returns correct statistics for perfect forecast`() = runTest {
        for (daysAgo in 1..5) {
            val target = dateStr(daysAgo)
            val forecastMade = dateStr(daysAgo + 1)
            insertExtreme(target, WeatherSource.NWS, highTemp = 72f, lowTemp = 50f)
            insertForecastSnapshot(target, forecastMade, WeatherSource.NWS, highTemp = 72f, lowTemp = 50f)
        }

        val stats = calculator.calculateAccuracy(WeatherSource.NWS, lat, lon, 30)

        assertNotNull(stats)
        assertEquals(5.0, stats!!.accuracyScore, 0.001)
        assertEquals(0.0, stats.avgHighError, 0.001)
        assertEquals(0.0, stats.avgLowError, 0.001)
        assertEquals(0.0, stats.highBias, 0.001)
        assertEquals(0.0, stats.lowBias, 0.001)
        assertEquals(100.0, stats.percentWithin3Degrees, 0.001)
        assertEquals(5, stats.totalForecasts)
    }

    @Test
    fun `calculateAccuracy computes bias sign correctly`() = runTest {
        // Forecast consistently runs 3° high (actual is lower than forecast → negative bias)
        for (daysAgo in 1..3) {
            val target = dateStr(daysAgo)
            val forecastMade = dateStr(daysAgo + 1)
            insertExtreme(target, WeatherSource.NWS, highTemp = 65f, lowTemp = 45f)
            insertForecastSnapshot(target, forecastMade, WeatherSource.NWS, highTemp = 68f, lowTemp = 48f)
        }

        val stats = calculator.calculateAccuracy(WeatherSource.NWS, lat, lon, 30)!!

        // highError = actual(65) - forecast(68) = -3 each day → bias = -3.0 (forecast runs high)
        assertEquals(-3.0, stats.highBias, 0.001)
        assertEquals(-3.0, stats.lowBias, 0.001)
        assertEquals(3.0, stats.avgHighError, 0.001)
        assertEquals(3.0, stats.avgLowError, 0.001)
    }

    @Test
    fun `calculateAccuracy percentWithin3 counts correctly`() = runTest {
        // 2 days within ±3°, 1 day outside
        val daysData = listOf(
            Triple(70f, 68f, 50f), // |high error|=2, within
            Triple(70f, 67f, 50f), // |high error|=3, within (boundary)
            Triple(70f, 74f, 50f), // |high error|=4, outside
        )
        daysData.forEachIndexed { i, (actual, forecast, low) ->
            val daysAgo = i + 1
            insertExtreme(dateStr(daysAgo), WeatherSource.NWS, highTemp = actual, lowTemp = low)
            insertForecastSnapshot(dateStr(daysAgo), dateStr(daysAgo + 1), WeatherSource.NWS, highTemp = forecast, lowTemp = low)
        }

        val stats = calculator.calculateAccuracy(WeatherSource.NWS, lat, lon, 30)!!

        // 2 of 3 days within ±3° for both high AND low
        assertEquals(2.0 / 3.0 * 100.0, stats.percentWithin3Degrees, 0.01)
        assertEquals(3, stats.totalForecasts)
    }
}

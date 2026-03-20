package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.TemperatureInterpolator
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
class WeatherGapIntegrationTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: WeatherRepository

    private val lat = 37.42
    private val lon = -122.08
    private val today = LocalDate.now()
    private val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val tomorrowStr = today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val dayAfterTomorrowStr = today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val threeDaysOutStr = today.plusDays(3).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        db = TestDatabase.create()
        val context = RuntimeEnvironment.getApplication()
        val forecastRepo = ForecastRepository(context, db.forecastDao(), db.hourlyForecastDao(), db.appLogDao(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), db.climateNormalDao(), db.observationDao(), mockk(relaxed = true), mockk(relaxed = true))
        val currentRepo = CurrentTempRepository(context, db.observationDao(), db.hourlyForecastDao(), db.appLogDao(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), TemperatureInterpolator(), mockk(relaxed = true), mockk(relaxed = true))
        repository = WeatherRepository(
            context,
            forecastRepo,
            currentRepo,
            db.forecastDao(),
            db.appLogDao(),
            mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `getCachedDataBySource returns provider for overlapping dates and generic for provider gaps`() = runTest {
        val providerBatchFetchedAt = 1_000L
        val gapBatchFetchedAt = 2_000L
        db.forecastDao().insertAll(
            listOf(
                forecast(todayStr, WeatherSource.SILURIAN, 70f, 50f, batchFetchedAt = providerBatchFetchedAt, fetchedAt = 10_000L),
                forecast(todayStr, WeatherSource.GENERIC_GAP, 65f, 45f, isClimateNormal = true, batchFetchedAt = gapBatchFetchedAt, fetchedAt = 20_000L),
                forecast(tomorrowStr, WeatherSource.SILURIAN, 71f, 51f, batchFetchedAt = providerBatchFetchedAt, fetchedAt = 10_001L),
                forecast(dayAfterTomorrowStr, WeatherSource.GENERIC_GAP, 67f, 47f, isClimateNormal = true, batchFetchedAt = gapBatchFetchedAt, fetchedAt = 20_001L),
            ),
        )

        val result = repository.getCachedDataBySource(lat, lon, WeatherSource.SILURIAN)

        assertEquals(listOf(todayStr, tomorrowStr, dayAfterTomorrowStr), result.map { it.targetDate })
        assertEquals(WeatherSource.SILURIAN.id, result.first { it.targetDate == todayStr }.source)
        assertEquals(WeatherSource.SILURIAN.id, result.first { it.targetDate == tomorrowStr }.source)
        assertEquals(WeatherSource.GENERIC_GAP.id, result.first { it.targetDate == dayAfterTomorrowStr }.source)
    }

    @Test
    fun `getCachedDataBySource preserves generic fallback marker for widget styling`() = runTest {
        db.forecastDao().insertAll(
            listOf(
                forecast(todayStr, WeatherSource.WEATHER_API, 72f, 52f),
                forecast(tomorrowStr, WeatherSource.GENERIC_GAP, 68f, 48f, isClimateNormal = true),
            ),
        )

        val result = repository.getCachedDataBySource(lat, lon, WeatherSource.WEATHER_API)

        val fallbackDay = result.first { it.targetDate == tomorrowStr }
        assertEquals(WeatherSource.GENERIC_GAP.id, fallbackDay.source)
        assertTrue(fallbackDay.isClimateNormal)
    }

    @Test
    fun `getCachedDataBySource keeps old source history but current selection follows newest shorter same-day horizon`() = runTest {
        val olderBatchFetchedAt = 1_000L
        val newerBatchFetchedAt = 2_000L
        val sameDayForecastDate = todayStr

        db.forecastDao().insertAll(
            listOf(
                forecast(todayStr, WeatherSource.SILURIAN, 70f, 50f, forecastDate = sameDayForecastDate, batchFetchedAt = olderBatchFetchedAt, fetchedAt = 10_000L),
                forecast(tomorrowStr, WeatherSource.SILURIAN, 71f, 51f, forecastDate = sameDayForecastDate, batchFetchedAt = olderBatchFetchedAt, fetchedAt = 10_001L),
                forecast(dayAfterTomorrowStr, WeatherSource.SILURIAN, 72f, 52f, forecastDate = sameDayForecastDate, batchFetchedAt = olderBatchFetchedAt, fetchedAt = 10_002L),
                forecast(threeDaysOutStr, WeatherSource.SILURIAN, 73f, 53f, forecastDate = sameDayForecastDate, batchFetchedAt = olderBatchFetchedAt, fetchedAt = 10_003L),

                forecast(todayStr, WeatherSource.SILURIAN, 74f, 54f, forecastDate = sameDayForecastDate, batchFetchedAt = newerBatchFetchedAt, fetchedAt = 20_000L),
                forecast(tomorrowStr, WeatherSource.SILURIAN, 75f, 55f, forecastDate = sameDayForecastDate, batchFetchedAt = newerBatchFetchedAt, fetchedAt = 20_001L),

                forecast(dayAfterTomorrowStr, WeatherSource.GENERIC_GAP, 66f, 46f, isClimateNormal = true, forecastDate = sameDayForecastDate, batchFetchedAt = newerBatchFetchedAt, fetchedAt = 20_100L),
                forecast(threeDaysOutStr, WeatherSource.GENERIC_GAP, 67f, 47f, isClimateNormal = true, forecastDate = sameDayForecastDate, batchFetchedAt = newerBatchFetchedAt, fetchedAt = 20_101L),
            ),
        )

        val historyRows = db.forecastDao().getForecastsInRangeBySource(todayStr, threeDaysOutStr, lat, lon, WeatherSource.SILURIAN.id)
        assertEquals(6, historyRows.size)

        val result = repository.getCachedDataBySource(lat, lon, WeatherSource.SILURIAN)

        assertEquals(listOf(todayStr, tomorrowStr, dayAfterTomorrowStr, threeDaysOutStr), result.map { it.targetDate })
        assertEquals(74f, result.first { it.targetDate == todayStr }.highTemp)
        assertEquals(75f, result.first { it.targetDate == tomorrowStr }.highTemp)
        assertEquals(WeatherSource.GENERIC_GAP.id, result.first { it.targetDate == dayAfterTomorrowStr }.source)
        assertEquals(WeatherSource.GENERIC_GAP.id, result.first { it.targetDate == threeDaysOutStr }.source)
    }

    private fun forecast(
        date: String,
        source: WeatherSource,
        highTemp: Float,
        lowTemp: Float,
        isClimateNormal: Boolean = false,
        forecastDate: String = todayStr,
        batchFetchedAt: Long = System.currentTimeMillis(),
        fetchedAt: Long = System.currentTimeMillis(),
    ) = com.weatherwidget.data.local.ForecastEntity(
        targetDate = date,
        forecastDate = forecastDate,
        locationLat = lat,
        locationLon = lon,
        locationName = "Test",
        highTemp = highTemp,
        lowTemp = lowTemp,
        condition = if (isClimateNormal) "Historical Avg" else "Sunny",
        isClimateNormal = isClimateNormal,
        source = source.id,
        batchFetchedAt = batchFetchedAt,
        fetchedAt = fetchedAt,
    )
}

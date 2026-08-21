package com.weatherwidget.data.repository

import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.shared.util.TemperatureInterpolator
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
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
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
        val forecastRepo = ForecastRepository(
            context,
            db.forecastDao(),
            db.hourlyForecastDao(),
            db.hourlyForecastHistoryDao(),
            db.appLogDao(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            db.climateNormalDao(),
            db.observationDao(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        val currentRepo = CurrentTempRepository(
            context,
            db.observationDao(),
            db.hourlyForecastDao(),
            db.appLogDao(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
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

    /** Seeds the 12 monthly-mean rows read-time gap-fill (ClimateGapFiller) needs — never real ForecastEntity rows. */
    private suspend fun seedNormals(high: Float = 66f, low: Float = 46f) {
        val locationKey = ClimateNormals.locationKey(lat, lon)
        db.climateNormalDao().insertAll(
            (1..12).map { month ->
                ClimateNormalEntity(
                    monthDay = "${month.toString().padStart(2, '0')}-15",
                    locationKey = locationKey,
                    highTemp = high,
                    lowTemp = low,
                )
            },
        )
    }

    @Test
    fun `getCachedDataBySource returns provider for overlapping dates and generic for provider gaps`() = runTest {
        seedNormals()
        val providerBatchFetchedAt = 1_000L
        db.forecastDao().insertAll(
            listOf(
                forecast(todayStr, WeatherSource.SILURIAN, 70f, 50f, batchFetchedAt = providerBatchFetchedAt, fetchedAt = 10_000L),
                forecast(tomorrowStr, WeatherSource.SILURIAN, 71f, 51f, batchFetchedAt = providerBatchFetchedAt, fetchedAt = 10_001L),
            ),
        )

        val result = repository.getCachedDataBySource(lat, lon, WeatherSource.SILURIAN)

        // Generation now extends out to the full cache horizon (not just "the next day"), so assert
        // per-date rather than an exact list.
        assertEquals(WeatherSource.SILURIAN.id, result.first { it.targetDate == dateEpoch(todayStr) }.source)
        assertEquals(WeatherSource.SILURIAN.id, result.first { it.targetDate == dateEpoch(tomorrowStr) }.source)
        assertEquals(WeatherSource.GENERIC_GAP.id, result.first { it.targetDate == dateEpoch(dayAfterTomorrowStr) }.source)
        assertEquals(WeatherSource.GENERIC_GAP.id, result.first { it.targetDate == dateEpoch(threeDaysOutStr) }.source)
    }

    @Test
    fun `getCachedDataBySource preserves generic fallback marker for widget styling`() = runTest {
        seedNormals()
        db.forecastDao().insertAll(listOf(forecast(todayStr, WeatherSource.WEATHER_API, 72f, 52f)))

        val result = repository.getCachedDataBySource(lat, lon, WeatherSource.WEATHER_API)

        val fallbackDay = result.first { it.targetDate == dateEpoch(tomorrowStr) }
        assertEquals(WeatherSource.GENERIC_GAP.id, fallbackDay.source)
        assertTrue(fallbackDay.isClimateNormal)
    }

    @Test
    fun `getCachedDataBySource keeps old source history but current selection follows newest shorter same-day horizon`() = runTest {
        seedNormals()
        val olderBatchFetchedAt = 1_000L
        val newerBatchFetchedAt = 2_000L
        val sameDayForecastDate = todayStr

        db.forecastDao().insertAll(
            listOf(
                forecast(todayStr, WeatherSource.SILURIAN, 70f, 50f, dateOfPrediction = sameDayForecastDate, batchFetchedAt = olderBatchFetchedAt, fetchedAt = 10_000L),
                forecast(tomorrowStr, WeatherSource.SILURIAN, 71f, 51f, dateOfPrediction = sameDayForecastDate, batchFetchedAt = olderBatchFetchedAt, fetchedAt = 10_001L),
                forecast(dayAfterTomorrowStr, WeatherSource.SILURIAN, 72f, 52f, dateOfPrediction = sameDayForecastDate, batchFetchedAt = olderBatchFetchedAt, fetchedAt = 10_002L),
                forecast(threeDaysOutStr, WeatherSource.SILURIAN, 73f, 53f, dateOfPrediction = sameDayForecastDate, batchFetchedAt = olderBatchFetchedAt, fetchedAt = 10_003L),

                forecast(todayStr, WeatherSource.SILURIAN, 74f, 54f, dateOfPrediction = sameDayForecastDate, batchFetchedAt = newerBatchFetchedAt, fetchedAt = 20_000L),
                forecast(tomorrowStr, WeatherSource.SILURIAN, 75f, 55f, dateOfPrediction = sameDayForecastDate, batchFetchedAt = newerBatchFetchedAt, fetchedAt = 20_001L),
            ),
        )

        val historyRows = db.forecastDao().getForecastsInRangeBySource(dateEpoch(todayStr), dateEpoch(threeDaysOutStr), lat, lon, WeatherSource.SILURIAN.id)
        assertEquals(6, historyRows.size)

        val result = repository.getCachedDataBySource(lat, lon, WeatherSource.SILURIAN)

        assertEquals(74f, result.first { it.targetDate == dateEpoch(todayStr) }.highTemp)
        assertEquals(75f, result.first { it.targetDate == dateEpoch(tomorrowStr) }.highTemp)
        // The newest batch only re-forecast today+tomorrow; day-after and beyond fall back to the
        // read-time climate-normal fill (never a leftover persisted gap row).
        assertEquals(WeatherSource.GENERIC_GAP.id, result.first { it.targetDate == dateEpoch(dayAfterTomorrowStr) }.source)
        assertEquals(WeatherSource.GENERIC_GAP.id, result.first { it.targetDate == dateEpoch(threeDaysOutStr) }.source)
    }

    private fun forecast(
        date: String,
        source: WeatherSource,
        highTemp: Float,
        lowTemp: Float,
        isClimateNormal: Boolean = false,
        dateOfPrediction: String = todayStr,
        batchFetchedAt: Long = System.currentTimeMillis(),
        fetchedAt: Long = System.currentTimeMillis(),
    ) = com.weatherwidget.data.local.ForecastEntity(
        targetDate = dateEpoch(date),
        dateOfPrediction = dateEpoch(dateOfPrediction),
        locationLat = lat,
        locationLon = lon,
        highTemp = highTemp,
        lowTemp = lowTemp,
        condition = if (isClimateNormal) "Historical Avg" else "Sunny",
        isClimateNormal = isClimateNormal,
        source = source.id,
        batchFetchedAt = batchFetchedAt,
        fetchedAt = fetchedAt,
    )
}

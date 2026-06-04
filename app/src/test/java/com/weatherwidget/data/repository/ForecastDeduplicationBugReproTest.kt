package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.shared.util.TemperatureInterpolator
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
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
class ForecastDeduplicationBugReproTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: WeatherRepository

    private val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

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
            mockk(),
            mockk(),
            mockk(relaxed = true),
            mockk(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            db.climateNormalDao(),
            db.observationDao(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        val currentRepo = CurrentTempRepository(
            context,
            db.observationDao(),
            db.hourlyForecastDao(),
            db.appLogDao(),
            mockk(),
            mockk(),
            mockk(relaxed = true),
            mockk(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        repository = WeatherRepository(context, forecastRepo, currentRepo, db.forecastDao(), db.appLogDao(), mockk(relaxed = true))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `good forecast is not skipped after a regression`() = runTest {
        // All three saves land in the same snapshot bucket (fetchedAt = now), so the forecast-history
        // cadence cap collapses them to a single latest row. The bug this guards against is the GOOD
        // forecast (Batch 3) being dropped by the dedup comparison after a regression — so the key
        // assertion is that the surviving row carries the good (non-null) high temp, not the count.

        // 1. Save a good forecast (Batch 1)
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = tomorrow, source = "NWS", highTemp = 80f, lowTemp = 54f)),
            LAT, LON, "NWS", batchFetchedAt = 1000L
        )
        assertEquals(1, db.forecastDao().getCount())

        // 2. Save a regressed forecast (missing high) (Batch 2) — different, so it replaces in-bucket.
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = tomorrow, source = "NWS", highTemp = null, lowTemp = 54f)),
            LAT, LON, "NWS", batchFetchedAt = 2000L
        )
        assertEquals(1, db.forecastDao().getCount())

        // 3. Save the good forecast again (Batch 3) — strictly better than the current latest, so the
        // dedup must NOT skip it; it replaces the regressed row within the bucket.
        repository.saveForecastSnapshot(
            listOf(TestData.forecast(targetDate = tomorrow, source = "NWS", highTemp = 80f, lowTemp = 54f)),
            LAT, LON, "NWS", batchFetchedAt = 3000L
        )

        val count = db.forecastDao().getCount()

        // getForecastsInRange returns the row with MAX batchFetchedAt. If Batch 3 were wrongly skipped,
        // the surviving row would be the regressed Batch 2 (null high) — the real regression signal.
        val forecasts = repository.getForecastsInRange(
            LocalDate.now().toEpochDay() * 86400000L,
            LocalDate.now().plusDays(2).toEpochDay() * 86400000L,
            LAT, LON
        )
        val tomorrowForecast = forecasts.find { it.targetDate == TestData.dateEpoch(tomorrow) }

        assertNotNull("Tomorrow forecast should exist", tomorrowForecast)
        assertNotNull("High temp should NOT be null (good forecast must survive)", tomorrowForecast?.highTemp)
        assertEquals(80f, tomorrowForecast?.highTemp)
        // Same-bucket saves collapse to one row under the cadence cap.
        assertEquals(1, count)
    }
}

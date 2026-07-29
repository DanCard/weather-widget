package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestData.LAT
import com.weatherwidget.testutil.TestData.LON
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
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
class ForecastSnapshotDeduplicationTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: WeatherRepository

    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
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
    fun `first forecast creates a snapshot`() = runTest {
        repository.saveForecastSnapshot(listOf(TestData.forecast(targetDate = tomorrow, source = "NWS", highTemp = 70f, lowTemp = 50f)), LAT, LON, "NWS")
        assertEquals(1, db.forecastDao().getCount())
    }

    @Test
    fun `identical forecast skips snapshot`() = runTest {
        val weather = listOf(TestData.forecast(targetDate = tomorrow, source = "NWS", highTemp = 70f, lowTemp = 50f, condition = "Sunny"))
        repository.saveForecastSnapshot(weather, LAT, LON, "NWS")
        assertEquals(1, db.forecastDao().getCount())
        repository.saveForecastSnapshot(weather, LAT, LON, "NWS")
        assertEquals(1, db.forecastDao().getCount())
    }

    @Test
    fun `daily change gate updates exact display site when neighboring fragment already has revision`() = runTest {
        val predictionDate = LocalDate.now().toString()
        val neighboringLat = LAT + 0.05
        val neighboringLon = LON + 0.02

        db.forecastDao().insertAll(
            listOf(
                TestData.forecast(
                    targetDate = tomorrow,
                    dateOfPrediction = predictionDate,
                    source = "NWS",
                    highTemp = 70f,
                    lowTemp = 50f,
                    lat = LAT,
                    lon = LON,
                    fetchedAt = 1_000L,
                    batchFetchedAt = 1_000L,
                ),
                TestData.forecast(
                    targetDate = tomorrow,
                    dateOfPrediction = predictionDate,
                    source = "NWS",
                    highTemp = 75f,
                    lowTemp = 50f,
                    lat = neighboringLat,
                    lon = neighboringLon,
                    fetchedAt = 2_000L,
                    batchFetchedAt = 2_000L,
                ),
            ),
        )

        repository.saveForecastSnapshot(
            listOf(
                TestData.forecast(
                    targetDate = tomorrow,
                    dateOfPrediction = predictionDate,
                    source = "NWS",
                    highTemp = 75f,
                    lowTemp = 50f,
                ),
            ),
            LAT,
            LON,
            "NWS",
            batchFetchedAt = 3_000L,
        )

        val displaySiteLatest = db.forecastDao()
            .getForecastsInRangeBySource(
                dateEpoch(tomorrow),
                dateEpoch(tomorrow),
                LAT,
                LON,
                "NWS",
            )
            .filter { it.locationLat == LAT && it.locationLon == LON }
            .maxBy { it.batchFetchedAt }

        assertEquals(
            "neighboring fragment must not mask the revised display-site high",
            75f,
            displaySiteLatest.highTemp,
        )
        assertEquals(3_000L, displaySiteLatest.batchFetchedAt)
    }

    @Test
    fun `identical sixteen day fetch keeps a coherent latest source batch`() = runTest {
        val forecasts = (0L until 16L).map { dayOffset ->
            TestData.forecast(
                targetDate = LocalDate.now().plusDays(dayOffset).toString(),
                dateOfPrediction = today,
                source = WeatherSource.OPEN_METEO.id,
                highTemp = 70f + dayOffset.toFloat(),
                lowTemp = 50f + dayOffset.toFloat(),
            )
        }

        repository.saveForecastSnapshot(
            forecasts,
            LAT,
            LON,
            WeatherSource.OPEN_METEO.id,
            batchFetchedAt = 1_000L,
        )
        repository.saveForecastSnapshot(
            forecasts,
            LAT,
            LON,
            WeatherSource.OPEN_METEO.id,
            batchFetchedAt = 2_000L,
        )

        assertEquals("unchanged second fetch must not append history rows", 16, db.forecastDao().getCount())

        val latestBatch = repository.getCachedDataBySource(LAT, LON, WeatherSource.OPEN_METEO)
            .filter { it.source == WeatherSource.OPEN_METEO.id }
        assertEquals("all sixteen provider days must remain in the latest coherent batch", 16, latestBatch.size)
        assertTrue(latestBatch.all { it.batchFetchedAt == 2_000L })
    }

    @Test
    fun `changed high temp within a snapshot bucket collapses to one updated row`() = runTest {
        repository.saveForecastSnapshot(listOf(TestData.forecast(targetDate = tomorrow, source = "NWS", highTemp = 70f, lowTemp = 50f)), LAT, LON, "NWS")
        repository.saveForecastSnapshot(listOf(TestData.forecast(targetDate = tomorrow, source = "NWS", highTemp = 72f, lowTemp = 50f)), LAT, LON, "NWS")
        // The change is captured, but within a single snapshot bucket (4h primary / 8h other) the
        // forecast-history cadence cap collapses it to one latest row instead of appending a new
        // snapshot. Both saves here share a bucket (fetchedAt = now). Across buckets it would add a row.
        assertEquals(1, db.forecastDao().getCount())
        val rows = db.forecastDao().getForecastsInRangeBySource(
            dateEpoch(tomorrow), dateEpoch(tomorrow), LAT, LON, "NWS",
        )
        assertEquals(72f, rows.first().highTemp)
    }

    @Test
    fun `historical dates are excluded from snapshots`() = runTest {
        val yesterday = LocalDate.now().minusDays(1).toString()
        repository.saveForecastSnapshot(listOf(TestData.forecast(targetDate = yesterday, source = "NWS")), LAT, LON, "NWS")
        assertEquals(0, db.forecastDao().getCount())
    }

    @Test
    fun `terminal low only nws row is persisted and returned in latest batch`() = runTest {
        val dayAfterTomorrow = LocalDate.now().plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val batchFetchedAt = 1_234_567L
        repository.saveForecastSnapshot(
            listOf(
                TestData.forecast(targetDate = tomorrow, source = "NWS", highTemp = 70f, lowTemp = 50f),
                TestData.forecast(targetDate = dayAfterTomorrow, source = "NWS", highTemp = null, lowTemp = 48f),
            ),
            LAT,
            LON,
            "NWS",
            batchFetchedAt = batchFetchedAt,
        )

        val storedRows = db.forecastDao().getForecastsInRangeBySource(
            startDate = dateEpoch(tomorrow),
            endDate = dateEpoch(dayAfterTomorrow),
            lat = LAT,
            lon = LON,
            source = "NWS",
        )
        val terminalRow = storedRows.first { it.targetDate == dateEpoch(dayAfterTomorrow) }
        assertNull(terminalRow.highTemp)
        assertEquals(48f, terminalRow.lowTemp)
        assertEquals(batchFetchedAt, terminalRow.batchFetchedAt)

        val latestBatch = repository.getCachedDataBySource(LAT, LON, WeatherSource.NWS)
        val latestTerminalRow = latestBatch.first { it.targetDate == dateEpoch(dayAfterTomorrow) }
        assertNull(latestTerminalRow.highTemp)
        assertEquals(48f, latestTerminalRow.lowTemp)

        val batchLogs = db.appLogDao().getLogsByTag("NWS_BATCH_SAVE_SUMMARY", 5)
        assertTrue(batchLogs.any { it.message.contains("savedMaxDate=$dayAfterTomorrow") })
    }
}

package com.weatherwidget.data.repository

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * Integration guard for the cross-fragment change-gate bug (2026-07-10): the live-write gate in
 * saveHourlyEntities must diff incoming rows against the rows at the exact coordinate being
 * written, NOT against the whole proximity box. A GPS-jitter fragment that absorbed the newest
 * fetch would otherwise make a revised value look "unchanged", the write at the display site is
 * skipped, and the widget serves stale data (Sunday noon cloud pinned at 67% while a jittered
 * fragment held the revised 96%).
 *
 * Exercises the real pipeline: mocked NWS API → getWeatherData → saveHourlyEntities → Room.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class HourlyChangeGateSiteExactIntegrationTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: ForecastRepository
    private lateinit var nwsApi: NwsApi

    // Already 3-decimal quantized so the seeded site row shares the PK coordinate the save writes.
    private val siteLat = 37.417
    private val siteLon = -122.089
    private val jitterLat = 37.422
    private val jitterLon = -122.087

    @Before
    fun setup() {
        db = TestDatabase.create()
        nwsApi = mockk()

        val context = RuntimeEnvironment.getApplication()
        val widgetStateManager = mockk<WidgetStateManager>(relaxed = true)
        every { widgetStateManager.isSourceVisible(any()) } answers { firstArg<WeatherSource>() == WeatherSource.NWS }
        every { widgetStateManager.getVisibleSourcesOrder() } returns listOf(WeatherSource.NWS)

        repository = ForecastRepository(
            context = context,
            forecastDao = db.forecastDao(),
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            nwsApi = nwsApi,
            openMeteoApi = mockk(relaxed = true),
            visualCrossingApi = mockk(relaxed = true),
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = widgetStateManager,
            climateNormalDao = db.climateNormalDao(),
            observationDao = db.observationDao(),
            dailyHistoryDao = mockk(relaxed = true),
            observationRepository = mockk(relaxed = true),
            tomorrowIoApi = mockk(relaxed = true),
            openWeatherMapApi = mockk(relaxed = true),
            nwsForecastMapper = NwsForecastMapper(nwsApi, db.appLogDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `revised cloud cover is written at the display site even when a jitter fragment already has it`() = runTest {
        val zoneId = ZoneId.systemDefault()
        val tomorrow = LocalDate.now().plusDays(1)
        val noonMs = tomorrow.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        fun seededRow(lat: Double, lon: Double, cloudCover: Int, fetchedAt: Long) = HourlyForecastEntity(
            dateTime = noonMs,
            locationLat = lat,
            locationLon = lon,
            temperature = 55f,
            condition = "Rain",
            source = WeatherSource.NWS.id,
            precipProbability = 80,
            cloudCover = cloudCover,
            precipAmountMm = null,
            fetchedAt = fetchedAt,
        )

        // Display site holds the pre-revision forecast. The jitter fragment (same proximity box,
        // different quantized pair) already absorbed the revision via a fetch that ran while the
        // device location wobbled. Insert the jitter row LAST so a box-wide associateBy would
        // resolve the hour to it and wrongly conclude "no change".
        db.hourlyForecastDao().insertAll(listOf(seededRow(siteLat, siteLon, cloudCover = 67, fetchedAt = now - 12 * 60 * 60 * 1000)))
        db.hourlyForecastDao().insertAll(listOf(seededRow(jitterLat, jitterLon, cloudCover = 96, fetchedAt = now - 2 * 60 * 60 * 1000)))

        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com/forecast")
        coEvery { nwsApi.getGridPoint(siteLat, siteLon) } returns gridPoint
        coEvery { nwsApi.getGridpointsBundle(gridPoint) } returns NwsApi.GridpointsBundle(
            skyCoverByHour = emptyMap(),
            qpfIntervals = emptyList(),
            dailyTemperatures = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap()),
        )
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
            NwsApi.ForecastPeriod(
                name = "Tomorrow",
                startTime = "${tomorrow}T06:00:00-07:00",
                endTime = "${tomorrow}T18:00:00-07:00",
                temperature = 64,
                temperatureUnit = "F",
                shortForecast = "Rain",
                isDaytime = true,
                precipProbability = 80,
            ),
        )
        // The fresh fetch at the display site carries the revised cloud cover — identical to the
        // jitter fragment's row, differing from the display site's row only in cloudCover.
        coEvery { nwsApi.getHourlyForecast(gridPoint) } returns listOf(
            NwsApi.HourlyForecastPeriod(
                startTime = noonMs,
                localDate = tomorrow.toString(),
                localHour = 12,
                temperature = 55f,
                shortForecast = "Rain",
                precipProbability = 80,
                cloudCover = 96,
            ),
        )

        repository.getWeatherData(siteLat, siteLon, forceRefresh = true)

        val boxRows = db.hourlyForecastDao().getHourlyForecastsBySource(
            noonMs, noonMs, siteLat, siteLon, WeatherSource.NWS.id,
        )
        val siteRow = boxRows.single { it.locationLat == siteLat && it.locationLon == siteLon }
        assertEquals(
            "display-site row must be updated to the revised cloud cover, not masked by the jitter fragment",
            96,
            siteRow.cloudCover,
        )
        // Sanity: both fragments were really in the box the gate queried.
        assertEquals(2, boxRows.size)
    }
}

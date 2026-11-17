package com.weatherwidget.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleWeatherApi
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.DailyForecastIconResolver
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * [ForecastFetchCoordinator] + [ForecastSnapshotStore] + Room + [DailyForecastIconResolver]: a Google
 * fetch stores each daily window's combined chance beside its max, and the widget's daily view shows
 * it once the max reaches 85% — past the 72 h hourly store too.
 * plans/261117-google-combined-daily-rain-chance.md
 */
@Category(LongDuration::class)
class GoogleCombinedRainStorageIntegrationTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private lateinit var coordinator: ForecastFetchCoordinator
    private lateinit var snapshotStore: ForecastSnapshotStore
    private val googleApi = mockk<GoogleWeatherApi>(relaxed = true)
    private val google = WeatherSource.GOOGLE_WEATHER
    private val lat = 37.417
    private val lon = -122.089
    private val hour = 3_600_000L
    private val zone = ZoneId.systemDefault()
    private val clockMs = (System.currentTimeMillis() / hour) * hour + 10 * 60_000L
    private val today = LocalDate.now(zone)
    private val days = (0 until 10).map { today.plusDays(it.toLong()) }

    /** Day 1 and day 5: 85% from 8am to noon; every other hour the 10% drizzle floor. */
    private val stormDays = setOf(today.plusDays(1), today.plusDays(5))

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        db = TestDatabase.create()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val widgetStateManager = WidgetStateManager(context)
        // Google is off by default; a fetch skips a source the user has not shown.
        widgetStateManager.setVisibleSourcesOrder(listOf(google))
        val appLogDao = db.appLogDao()
        snapshotStore = ForecastSnapshotStore(
            forecastDao = db.forecastDao(),
            appLogDao = appLogDao,
            widgetStateManager = widgetStateManager,
            gapFiller = ClimateGapFiller(db.climateNormalDao()),
            clock = { clockMs },
        )
        val hourlyStore = HourlyForecastStore(
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            observationDao = db.observationDao(),
            widgetStateManager = widgetStateManager,
            clock = { clockMs },
            appLogDao = appLogDao,
            dailyHistoryDao = db.dailyHistoryDao(),
        )
        coordinator = ForecastFetchCoordinator(
            context = context,
            appLogDao = appLogDao,
            openMeteoApi = mockk(relaxed = true),
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = widgetStateManager,
            tomorrowIoApi = null,
            openWeatherMapApi = null,
            googleWeatherApi = googleApi,
            nwsForecastMapper = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            hourlyStore = hourlyStore,
            weatherApiHistoryBackfiller = mockk(relaxed = true),
            nwsApiDailyActualsFetcher = null,
            clock = { clockMs },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun download(): List<HourlyForecast> =
        generateSequence(at(today, 0)) { it + hour }.takeWhile { it < at(today.plusDays(9), 0) }.map { ms ->
            val local = java.time.Instant.ofEpochMilli(ms).atZone(zone)
            val storm = local.toLocalDate() in stormDays && local.hour in 8..12
            HourlyForecast(
                dateTime = ms, temperature = 58f, condition = "Rain",
                precipProbability = if (storm) 85 else 10, precipAmountMm = if (storm) 8f else 0f,
            )
        }.toList()

    private suspend fun rows(): Map<LocalDate, ForecastEntity> =
        snapshotStore.getCachedDataBySource(lat, lon, google)
            .filter { it.source == google.id }
            .associateBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }

    @Test
    fun `a Google fetch stores each window's combined chance and the daily view shows it from 85 percent`() = runTest {
        val daily = days.map { DailyForecast(it.toString(), 60f, 50f, "Rain", precipProbability = 85) }
        coEvery { googleApi.getForecast(any(), any(), any(), any(), any(), any()) } returns RawFetch(daily = daily, hourly = download())
        coordinator.fetchFromAllApis(lat, lon, setOf(google))

        // The fetch's own log lines: a skipped source (SOURCE_RETRY ... not_transient) shows here, not in the assertion.
        db.appLogDao().getRecentLogs(40).reversed().forEach { println("log ${it.tag}: ${it.message}") }
        val rows = rows()
        println("stored rows: ${rows.keys.sorted()}")
        listOf(today.plusDays(1), today.plusDays(2), today.plusDays(5)).forEach { day ->
            val row = rows.getValue(day)
            // No hourly: the widget's far days read the row alone.
            val resolved = DailyForecastIconResolver.resolveDailyLabelPrecip(
                weather = row, hourlyForecasts = emptyList(), targetDate = day, isPast = false,
                displaySource = google, centerLat = lat, centerLon = lon,
            )
            println("$day max=${row.hourlyDayPrecipMax} combined=${row.hourlyDayPrecipCombined} night=${row.hourlyNightPrecipMax}/${row.hourlyNightPrecipCombined} shown=${resolved.dayPrecip}/${resolved.nightPrecip}")
            if (day in stormDays) {
                assertEquals(85, row.hourlyDayPrecipMax)
                assertEquals(100, row.hourlyDayPrecipCombined)
                assertEquals(100, resolved.dayPrecip)
            } else {
                assertEquals(10, row.hourlyDayPrecipMax)
                assertEquals("twelve 10% hours", 72, row.hourlyDayPrecipCombined)
                assertEquals("below 85% the max stands", 10, resolved.dayPrecip)
            }
            assertEquals("a drizzle-floor night keeps its max", 10, resolved.nightPrecip)
        }
    }
}

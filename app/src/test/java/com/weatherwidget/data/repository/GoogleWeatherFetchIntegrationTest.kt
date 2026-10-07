package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleWeatherApi
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.GoogleWeatherFixtures
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Real [ForecastRepository] → [ForecastFetchCoordinator] → [GoogleWeatherApi] (recorded responses
 * on a MockEngine) → Room. Proves where each part of a Google fetch lands, and that a forecast-only
 * source never files its own forecast as observations.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class GoogleWeatherFetchIntegrationTest {
    private lateinit var db: WeatherDatabase
    // Synchronized: the client issues its requests concurrently, and a plain list lost one.
    private val requests: MutableList<HttpRequestData> = java.util.Collections.synchronizedList(mutableListOf())
    private val lat = 37.422
    private val lon = -122.084
    private val source = WeatherSource.GOOGLE_WEATHER.id

    @Before
    fun setup() {
        db = TestDatabase.create()
    }

    @After
    fun tearDown() = db.close()

    private fun repository(): ForecastRepository {
        val google = GoogleWeatherApi(HttpClient(GoogleWeatherFixtures.engine(requests)), Json { ignoreUnknownKeys = true }) { "test-key" }
        val nwsApi = mockk<NwsApi>()
        coEvery { nwsApi.getGridPoint(any(), any()) } throws Exception("NWS not under test")
        val widgetStateManager = mockk<WidgetStateManager>(relaxed = true)
        every { widgetStateManager.isSourceVisible(any()) } answers { firstArg<WeatherSource>() == WeatherSource.GOOGLE_WEATHER }
        every { widgetStateManager.getVisibleSourcesOrder() } returns listOf(WeatherSource.GOOGLE_WEATHER)
        every { widgetStateManager.getPrimarySource() } returns WeatherSource.GOOGLE_WEATHER
        every { widgetStateManager.getActiveDisplaySourceIds() } returns setOf(source)
        return ForecastRepository(
            RuntimeEnvironment.getApplication(),
            db.forecastDao(),
            db.hourlyForecastDao(),
            db.hourlyForecastHistoryDao(),
            db.appLogDao(),
            nwsApi,
            mockk<OpenMeteoApi>(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            widgetStateManager,
            db.climateNormalDao(),
            db.observationDao(),
            db.dailyHistoryDao(),
            mockk(relaxed = true),
            null,
            null,
            mockk(relaxed = true),
            googleWeatherApi = google,
        )
    }

    private fun count(sql: String, vararg args: Any): Long =
        db.openHelper.readableDatabase.query(sql, args).use { c -> c.moveToFirst(); c.getLong(0) }

    @Test
    fun `a full fetch stores daily, future hourly and elapsed history, and no observations`() = runTest {
        repository().getWeatherData(lat, lon, forceRefresh = true)

        // current + days + 3 hour pages + history = the billed cost of one fetch.
        val googleRequests = requests.filter { it.url.host == "weather.googleapis.com" }
        assertEquals(6, googleRequests.size)

        val dailyDates = count("SELECT COUNT(DISTINCT targetDate) FROM forecasts WHERE source = ?", source)
        assertTrue("expected ~10 daily rows, got $dailyDates", dailyDates >= 9)

        val nowHour = Instant.now().truncatedTo(ChronoUnit.HOURS).toEpochMilli()
        val future = count("SELECT COUNT(*) FROM hourly_forecasts WHERE source = ?", source)
        assertTrue("72 forecast hours expected in hourly_forecasts, got $future", future in 70..74)
        val maxFuture = count("SELECT MAX(dateTime) FROM hourly_forecasts WHERE source = ?", source)
        assertEquals(nowHour + 71 * 3_600_000L, maxFuture)

        // Today's elapsed hours (from history/hours) are history-only material, never live rows.
        val elapsedLive = count("SELECT COUNT(*) FROM hourly_forecasts WHERE source = ? AND dateTime < ?", source, nowHour - 3_600_000L)
        assertEquals(0L, elapsedLive)
        val elapsedHistory = count(
            "SELECT COUNT(DISTINCT dateTime) FROM hourly_forecast_history WHERE source = ? AND dateTime < ?",
            source,
            nowHour,
        )
        assertTrue("elapsed hours should backfill history on a fresh site, got $elapsedHistory", elapsedHistory > 0)

        // Forecast-only: neither the forecast nor its history is ever re-filed as observations.
        assertEquals(0L, count("SELECT COUNT(*) FROM observations WHERE api = ?", source))
    }

    @Test
    fun `a second fetch at the same site skips the quota-limited history call`() = runTest {
        val repo = repository()
        repo.getWeatherData(lat, lon, forceRefresh = true)
        repo.getWeatherData(lat, lon, forceRefresh = true)

        val historyCalls = requests.count { it.url.encodedPath.endsWith("history/hours:lookup") }
        assertEquals("history only for the first fetch at a site", 1, historyCalls)
        // First fetch: current + days + 3 hour pages + history = 6. Second: page 1 matches the stored
        // hours, so pages 2–3 are skipped (GoogleHourPaging) = current + days + 1 hour page = 3.
        val hourPages = requests.count { it.url.encodedPath.endsWith("forecast/hours:lookup") }
        assertEquals("3 pages, then 1 for an unchanged first page", 4, hourPages)
        assertEquals(9, requests.count { it.url.host == "weather.googleapis.com" })
    }

    /** targetDate -> (day, night) precip on the newest batch of Google daily rows. */
    private fun storedPeriods(): Map<Long, Pair<Int?, Int?>> =
        db.openHelper.readableDatabase.query(
            "SELECT targetDate, daytimePrecipProbability, nighttimePrecipProbability FROM forecasts " +
                "WHERE source = ? AND batchFetchedAt = (SELECT MAX(batchFetchedAt) FROM forecasts WHERE source = ?)",
            arrayOf(source, source),
        ).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    put(c.getLong(0), (if (c.isNull(1)) null else c.getInt(1)) to (if (c.isNull(2)) null else c.getInt(2)))
                }
            }
        }

    /**
     * A one-page fetch returns 24 h. Day/night precip are resolved from the hourly rows as stored
     * (shared DailyPrecipPeriods), so days 2–3 keep the values the full fetch gave them instead of
     * dropping to the provider-only value or null.
     */
    @Test
    fun `a one-page fetch keeps days 2-3 day and night precip from the stored hours`() = runTest {
        val repo = repository()
        repo.getWeatherData(lat, lon, forceRefresh = true)
        val afterFull = storedPeriods()
        repo.getWeatherData(lat, lon, forceRefresh = true)
        assertEquals(
            "second fetch must be the one-page path",
            4,
            requests.count { it.url.encodedPath.endsWith("forecast/hours:lookup") },
        )
        val afterOnePage = storedPeriods()

        val hourlyCovered = afterFull.filterValues { (d, n) -> d != null || n != null }.keys
        assertTrue("fixture should give several days hourly-derived periods", hourlyCovered.size >= 3)
        hourlyCovered.forEach { date -> assertEquals("date=$date", afterFull[date], afterOnePage[date]) }
    }
}

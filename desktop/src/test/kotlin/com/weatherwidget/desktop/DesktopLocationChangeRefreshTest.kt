package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.LocationChangeRefreshPolicy
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * A picker move is refreshed by the daemon alone (plans/261121-location-change-honours-fresh-cache.md).
 * 2026-11-21 08:55:46: back to Mountain View, whose NWS forecast was 86 min old, and NWS was fetched
 * twice — the daemon judged a move against 15 min, and the window sent its own request beside it.
 *
 * Real SQLite DAO and a real [DesktopWeatherRepository] (its fetch stores rows and the per-site
 * marker), then the daemon's [locationChangeLaunchAction] on the cache it loads, and the
 * [DesktopLocationRefreshResult] round trip the window waits on.
 */
@Category(ShortDuration::class)
class DesktopLocationChangeRefreshTest {
    private val dbPath = Files.createTempFile("location-change-refresh", ".db")
    private val database = DesktopWeatherDatabase(dbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)

    private val nws = WeatherSource.NWS.id
    private val mountainView = 37.4166014 to -122.0888722
    private val scottsdale = 33.6451828 to -111.9112613

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(dbPath)
    }

    private fun config(site: Pair<Double, Double>) =
        DesktopConfig(site.first, site.second, "test", settings = DesktopSettings(weatherSource = nws))

    /** Today's daily row and every hour of today: a cache the window adopts. */
    private fun todayRaw(): RawFetch {
        val today = LocalDate.now()
        val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return RawFetch(
            daily = listOf(DailyForecast(today.toString(), 60f, 40f, "Cloudy"), DailyForecast(today.plusDays(1).toString(), 62f, 44f, "Cloudy")),
            hourly = (0 until 24).map { HourlyForecast(dateTime = start + it * 3_600_000L, temperature = 50f, condition = "Cloudy") },
        )
    }

    /** Fetches [site] through the real repository: rows plus the FORECAST_LONG_FETCH marker. */
    private suspend fun fetchedAt(site: Pair<Double, Double>): DesktopWeatherRepository {
        val raw = todayRaw()
        val client = mockk<WeatherApiClient>(relaxed = true).also {
            coEvery { it.fetchForecast() } returns raw
            coEvery { it.fetchForecast(any()) } returns raw
            coEvery { it.fetchForecast(any(), any()) } returns raw
        }
        return DesktopWeatherRepository(client, dao, site.first, site.second, nws).also { it.refresh(reason = "test") }
    }

    @Test
    fun `a move to a site fetched 86 minutes ago fetches no forecast`() = runBlocking {
        val repo = fetchedAt(mountainView)
        val cached = repo.loadCached()
        val now = System.currentTimeMillis()
        fun actionAt(ageMs: Long, highBattery: Boolean = true, lastObs: Long? = now + ageMs) =
            locationChangeLaunchAction(dao, config(mountainView), cached, lastObs, now + ageMs, highBattery)

        assertEquals("86 min: the cache stands", LaunchRefreshAction.NONE, actionAt(86 * 60_000L))
        assertEquals("observations still catch up", LaunchRefreshAction.OBSERVATIONS, actionAt(86 * 60_000L, lastObs = null))
        assertEquals("3 h on a charger: past 2 h", LaunchRefreshAction.FULL_FORECAST, actionAt(3 * 3_600_000L))
        assertEquals("3 h on low battery: inside 4 h", LaunchRefreshAction.NONE, actionAt(3 * 3_600_000L, highBattery = false))

        val logged = dao.getRecentLogsByTags(listOf(LocationChangeRefreshPolicy.TAG))
        logged.forEach { println(it.message) }
        assertEquals(4, logged.size)
    }

    @Test
    fun `a move to a site with nothing drawable fetches`() = runBlocking {
        fetchedAt(mountainView)
        val now = System.currentTimeMillis()
        assertEquals(
            "never fetched at Scottsdale",
            LaunchRefreshAction.FULL_FORECAST,
            locationChangeLaunchAction(dao, config(scottsdale), cached = null, lastObservationFetchMs = now, nowMs = now, isHighBattery = true),
        )
    }

    @Test
    fun `the window reads only this site's result recorded since its pick`() {
        val picked = System.currentTimeMillis()
        assertNull("nothing yet", DesktopLocationRefreshResult.since(dao, mountainView.first, mountainView.second, picked))

        DesktopLocationRefreshResult.record(dao, nws, scottsdale.first, scottsdale.second, LaunchRefreshAction.FULL_FORECAST, ok = true)
        assertNull("another site's result", DesktopLocationRefreshResult.since(dao, mountainView.first, mountainView.second, picked))

        DesktopLocationRefreshResult.record(dao, nws, mountainView.first, mountainView.second, LaunchRefreshAction.FULL_FORECAST, ok = false)
        assertEquals("failed", false, DesktopLocationRefreshResult.since(dao, mountainView.first, mountainView.second, picked))
        assertNull("an earlier pick's result", DesktopLocationRefreshResult.since(dao, mountainView.first, mountainView.second, System.currentTimeMillis() + 1))

        // GPS-jitter distance inside the quantized site is the same site.
        DesktopLocationRefreshResult.record(dao, nws, mountainView.first + 0.0001, mountainView.second, LaunchRefreshAction.FULL_FORECAST, ok = true)
        assertTrue(DesktopLocationRefreshResult.since(dao, mountainView.first, mountainView.second, picked) == true)
        dao.getRecentLogsByTags(listOf(DesktopLocationRefreshResult.TAG)).forEach { println(it.message) }
    }
}

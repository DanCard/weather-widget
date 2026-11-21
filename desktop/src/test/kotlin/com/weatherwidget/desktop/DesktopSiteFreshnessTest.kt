package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/**
 * Freshness is judged per site, as on Android. 2026-11-21: Google was fetched at Mountain View at
 * 07:38; the user moved to Scottsdale at 07:43, and Settings → Refresh data skipped Google as
 * "fresh (303s)" although its Scottsdale data was from 21:48 the night before. The launch check
 * after the move read the same site-blind row and chose OBSERVATIONS instead of a forecast.
 *
 * Real SQLite DAO, the real marker writer ([DesktopForecastHorizon], what the repository calls after
 * every answered fetch) plus the repository's site-less `REFRESH` row, and the real partition / launch
 * decision. Fails on the old site-blind lookup (Google skipped at Scottsdale).
 * plans/261121-desktop-location-change-freshness-and-config-clobber.md
 */
@Category(ShortDuration::class)
class DesktopSiteFreshnessTest {
    private val dbPath = Files.createTempFile("site-freshness", ".db")
    private val database = DesktopWeatherDatabase(dbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)

    private val google = WeatherSource.GOOGLE_WEATHER.id
    private val mountainView = 37.4166014 to -122.0888722
    private val scottsdale = 33.6451828 to -111.9112613

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(dbPath)
    }

    /** What an answered forecast fetch at [site] leaves behind: the per-site marker and the REFRESH row. */
    private suspend fun fetchedAt(source: String, site: Pair<Double, Double>) {
        DesktopForecastHorizon(dao, source, site.first, site.second).mark(ForecastHorizonCadence.Horizon.LONG)
        dao.log("REFRESH", "source=$source hourly=192 daily=10 obs=24 historyObs=0 extremes=0", "INFO")
    }

    private fun config(site: Pair<Double, Double>) = DesktopConfig(
        lat = site.first,
        lon = site.second,
        label = "test",
        settings = DesktopSettings(weatherSource = google, visibleSources = listOf("NWS", google)),
    )

    @Test
    fun `a fetch at one site is not freshness at another`() = runTest {
        fetchedAt(google, mountainView)

        assertNotNull(dao.getLastSuccessfulFetch(google, mountainView.first, mountainView.second))
        assertNull(
            "Scottsdale was never fetched, so it is stale",
            dao.getLastSuccessfulFetch(google, scottsdale.first, scottsdale.second),
        )
        // GPS jitter inside the quantized site is the same site.
        assertNotNull(dao.getLastSuccessfulFetch(google, mountainView.first + 0.0001, mountainView.second - 0.0001))
    }

    @Test
    fun `settings refresh after a move fetches sources stale at the new site`() = runTest {
        fetchedAt(google, mountainView)
        fetchedAt("NWS", mountainView)
        val now = System.currentTimeMillis()

        val atOldSite = desktopSettingsRefreshPartition(dao, config(mountainView), { null }, now)
        val atNewSite = desktopSettingsRefreshPartition(dao, config(scottsdale), { null }, now)
        println("old site: ${atOldSite.logLine()}")
        println("new site: ${atNewSite.logLine()}")

        assertTrue("just fetched here: nothing to do", atOldSite.allFresh)
        assertEquals(listOf(google, "NWS"), atNewSite.fetch)
    }

    @Test
    fun `launch check after a move fetches the forecast`() = runTest {
        fetchedAt(google, mountainView)
        val now = System.currentTimeMillis()
        fun actionAt(site: Pair<Double, Double>) = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = now,
            lastForecastFetchMs = dao.getLastSuccessfulFetch(google, site.first, site.second),
            nowMs = now,
        )

        assertEquals(LaunchRefreshAction.NONE, actionAt(mountainView))
        assertEquals(LaunchRefreshAction.FULL_FORECAST, actionAt(scottsdale))
    }
}

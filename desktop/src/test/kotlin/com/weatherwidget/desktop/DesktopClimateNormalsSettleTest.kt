package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Integration: `:shared` [ClimateNormalsRun.ensureForSite] through [DesktopWeatherRepository] and its
 * [DesktopClimateNormalsHost] over a real desktop SQLite database.
 *
 * Reproduces 2026-11-22: Scottsdale → Mountain View → Scottsdale with a fresh NWS forecast. Retention
 * keeps only the current site's normals, so the return re-caches them after the window was painted;
 * the repaint hook must fire, and what it reloads must carry the fallback day NWS does not reach
 * (today + 7). Before the fix the daemon published without notifying the UI process.
 */
@Category(ShortDuration::class)
class DesktopClimateNormalsSettleTest {
    private lateinit var dbPath: Path
    private lateinit var dao: DesktopWeatherDao
    private val today: LocalDate = LocalDate.now()

    private val archive = mockk<WeatherApiClient>(relaxed = true).also {
        coEvery { it.fetchHistoricalDailyTemps(any(), any()) } answers {
            // One row per month mid-month, warm highs: enough for ClimateNormals.monthlyMeans.
            (1..12).map { m -> DailyForecast("2025-%02d-15".format(m), 69f, 47f, "Clear") }
        }
    }

    @Before
    fun open() {
        dbPath = Files.createTempFile("climate-settle", ".db")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(dbPath).apply { initialize() })
    }

    @After
    fun close() {
        Files.deleteIfExists(dbPath)
    }

    private fun repo(lat: Double, lon: Double) = DesktopWeatherRepository(archive, dao, lat, lon, "NWS")

    /** NWS-shaped reach: today .. today + 6 (the 8th day comes only from the grid, absent here). */
    private fun seedNwsWeek(lat: Double, lon: Double) {
        dao.upsertForecasts(
            lat, lon, "NWS",
            (0..6).map { d -> DailyForecast(today.plusDays(d.toLong()).toString(), 72f, 49f, "Sunny") },
        )
    }

    private fun fallbackDays(snapshotDaily: List<DailyForecast>) =
        snapshotDaily.filter { it.isClimateNormal }.map { LocalDate.parse(it.date) }.toSet()

    @Test
    fun `returning to a site whose normals retention dropped repaints with the fallback day`() = runBlocking {
        val scottsdale = repo(SCOTTSDALE_LAT, SCOTTSDALE_LON)
        val mountainView = repo(MV_LAT, MV_LON)
        seedNwsWeek(SCOTTSDALE_LAT, SCOTTSDALE_LON)

        var repaints = 0
        var repainted: List<DailyForecast> = emptyList()
        val onCached: suspend () -> Unit = {
            repaints++
            repainted = scottsdale.loadCached()!!.raw.daily
        }

        // First visit caches Scottsdale's normals and repaints.
        assertTrue(scottsdale.ensureClimateNormals("launch:test", onCached))
        assertEquals(1, repaints)

        // Mountain View caches its own and (current location only) drops Scottsdale's.
        assertTrue(mountainView.ensureClimateNormals("launch:test") {})
        val beforeReturn = scottsdale.loadCached()!!.raw.daily
        println("before return: fallback days=${fallbackDays(beforeReturn).sorted()}")
        assertTrue("Scottsdale's normals were dropped, so no fallback", fallbackDays(beforeReturn).isEmpty())

        // The return: forecast fresh, no fetch, but normals re-cached → the repaint must fire.
        assertTrue(scottsdale.ensureClimateNormals("launch:test", onCached))
        assertEquals(2, repaints)
        val filled = fallbackDays(repainted)
        println("after return: fallback days=${filled.sorted()}")
        assertTrue("today + 7 (past NWS's reach) is a climate day", today.plusDays(7) in filled)
        assertFalse("days NWS covers stay NWS", (0..6).any { today.plusDays(it.toLong()) in filled })
        assertTrue(
            "CLIMATE_REPAINT logged with its trigger",
            dao.getRecentLogs(50).any { it.tag == "CLIMATE_REPAINT" && it.message.contains("reason=launch:test") },
        )
    }

    @Test
    fun `a settle at a site with cached normals neither fetches nor repaints`() = runBlocking {
        val scottsdale = repo(SCOTTSDALE_LAT, SCOTTSDALE_LON)
        seedNwsWeek(SCOTTSDALE_LAT, SCOTTSDALE_LON)
        assertTrue(scottsdale.ensureClimateNormals("launch:test") {})

        var repaints = 0
        assertFalse(scottsdale.ensureClimateNormals("launch:test") { repaints++ })
        assertEquals(0, repaints)
        coVerify(exactly = 1) { archive.fetchHistoricalDailyTemps(any(), any()) }
    }

    @Test
    fun `a failed launch settle retries on the next current-temp tick and repaints with today+7 fallback`() = runBlocking {
        var calls = 0
        val flakyArchive = mockk<WeatherApiClient>(relaxed = true).also {
            coEvery { it.fetchHistoricalDailyTemps(any(), any()) } answers {
                if (calls++ == 0) throw java.io.IOException("resume timeout")
                (1..12).map { m -> DailyForecast("2025-%02d-15".format(m), 69f, 47f, "Clear") }
            }
        }
        val scottsdale = DesktopWeatherRepository(flakyArchive, dao, SCOTTSDALE_LAT, SCOTTSDALE_LON, "NWS")
        seedNwsWeek(SCOTTSDALE_LAT, SCOTTSDALE_LON)

        var repaints = 0
        var repainted: List<DailyForecast> = emptyList()
        val onCached: suspend () -> Unit = {
            repaints++
            repainted = scottsdale.loadCached()!!.raw.daily
        }

        // Launch settle times out: no repaint, no fallback rows.
        assertFalse(scottsdale.ensureClimateNormals("launch:resume", onCached))
        assertEquals(0, repaints)

        // Next current-temp tick succeeds: one repaint, today + 7 filled.
        assertTrue(scottsdale.ensureClimateNormals("current_temp:charging_loop", onCached))
        assertEquals(1, repaints)
        assertTrue(today.plusDays(7) in fallbackDays(repainted))
        assertTrue(
            dao.getRecentLogs(50).any { it.tag == "CLIMATE_REPAINT" && it.message.contains("reason=current_temp:charging_loop") },
        )
    }

    @Test
    fun `climateNormalsCachedHook publishes and notifies only while stillDisplayed is true`() = runBlocking {
        var displayed = false
        var published = 0
        var notified = 0
        val snapshot = mockk<com.weatherwidget.data.model.ForecastSnapshot>(relaxed = true)
        val hook = climateNormalsCachedHook(
            stillDisplayed = { displayed },
            loadCached = { snapshot },
            publish = { published++ },
            notifyDataUpdated = { notified++ },
        )

        hook()
        assertEquals(0, published)
        assertEquals(0, notified)

        displayed = true
        hook()
        assertEquals(1, published)
        assertEquals(1, notified)
    }

    private companion object {
        const val SCOTTSDALE_LAT = 33.6451828
        const val SCOTTSDALE_LON = -111.9112613
        const val MV_LAT = 37.4220
        const val MV_LON = -122.0841
    }
}

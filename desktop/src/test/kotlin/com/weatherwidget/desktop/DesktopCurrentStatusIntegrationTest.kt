package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.CurrentStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.CurrentStatusPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

@Category(ShortDuration::class)
class DesktopCurrentStatusIntegrationTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private val testLat = 37.417
    private val testLon = -122.089

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("desktop-current-status-test", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
    }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `stale current_status in SQLite falls back to fresh snapshot on source switch`() {
        val nowMs = 1_793_212_119_000L // 11:28 AM

        // 1. Seed NWS as active source with fresh published status (11:28:00 AM, 19s ago)
        val nwsStatus = CurrentStatus(
            locationLat = testLat,
            locationLon = testLon,
            source = WeatherSource.NWS.id,
            displayTempF = 62.6f,
            appliedDeltaF = -1.36f,
            deltaFromYesterdayF = -0.4f,
            observedAtMs = nowMs - 60_000L,
            condition = "Clear",
            updatedAt = nowMs - 19_000L,
        )
        dao.upsertCurrentStatus(nwsStatus)

        // 2. Seed SILURIAN with stale status from startup (09:18:28 AM, > 2 hours ago: 53.0°F)
        val silurianStaleStatus = CurrentStatus(
            locationLat = testLat,
            locationLon = testLon,
            source = WeatherSource.SILURIAN.id,
            displayTempF = 53.0f,
            appliedDeltaF = -1.74f,
            deltaFromYesterdayF = -4.3f,
            observedAtMs = nowMs - 7_200_000L,
            condition = "Mostly Clear",
            updatedAt = nowMs - 7_200_000L,
        )
        dao.upsertCurrentStatus(silurianStaleStatus)

        // 3. For NWS: fresh published status is used
        val nwsRead = dao.getCurrentStatus(testLat, testLon, WeatherSource.NWS.id)
        assertNotNull(nwsRead)
        val nwsSnapshot = ForecastSnapshot(
            raw = RawFetch(),
            resolved = ResolvedView(currentTemp = 64.0f, deltaFromYesterday = 0.0f),
        )
        val nwsResolved = CurrentStatusPolicy.resolve(nwsRead, nwsSnapshot, nowMs)
        assertTrue(nwsResolved.isFromPublishedStatus)
        assertEquals(62.6f, nwsResolved.currentTemp!!, 0.01f)

        // 4. Source cycle: user switches to SILURIAN.
        // loadCached computes fresh in-memory resolve (63.19°), but SQLite holds the 2-hour-old 53.0° row.
        val silurianRead = dao.getCurrentStatus(testLat, testLon, WeatherSource.SILURIAN.id)
        assertNotNull("Row exists in SQLite", silurianRead)
        assertEquals(53.0f, silurianRead!!.displayTempF!!, 0.01f)

        val silurianSnapshot = ForecastSnapshot(
            raw = RawFetch(),
            resolved = ResolvedView(currentTemp = 63.19f, deltaFromYesterday = -0.47f, appliedDelta = 2.33f),
        )

        // CurrentStatusPolicy must reject the 2-hour-old row and fall back to the freshly loaded snapshot
        val silurianResolved = CurrentStatusPolicy.resolve(silurianRead, silurianSnapshot, nowMs)
        assertFalse("Must NOT use stale published status", silurianResolved.isFromPublishedStatus)
        assertEquals("Must display fresh snapshot temperature", 63.19f, silurianResolved.currentTemp!!, 0.01f)
        assertEquals(-0.47f, silurianResolved.deltaFromYesterday!!, 0.01f)
        assertEquals(2.33f, silurianResolved.appliedDelta!!, 0.01f)

        // 5. Daemon completes launch refresh and publishes updated status for SILURIAN
        val silurianUpdatedStatus = CurrentStatus(
            locationLat = testLat,
            locationLon = testLon,
            source = WeatherSource.SILURIAN.id,
            displayTempF = 63.19f,
            appliedDeltaF = 2.33f,
            deltaFromYesterdayF = -0.47f,
            observedAtMs = nowMs,
            condition = "Partly Cloudy",
            updatedAt = nowMs + 1000L,
        )
        dao.upsertCurrentStatus(silurianUpdatedStatus)

        val updatedRead = dao.getCurrentStatus(testLat, testLon, WeatherSource.SILURIAN.id)
        val updatedResolved = CurrentStatusPolicy.resolve(updatedRead, silurianSnapshot, nowMs + 1000L)
        assertTrue("Now uses fresh published status", updatedResolved.isFromPublishedStatus)
        assertEquals(63.19f, updatedResolved.currentTemp!!, 0.01f)
    }
}

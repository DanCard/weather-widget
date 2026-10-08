package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.ViewMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * Google quota work, 2026-10-08: desktop `api_usage_stats` (schema v28), and which launch
 * catch-ups are hourly-limited (daily and current refresh, the hourly forecast does not).
 */
@Category(ShortDuration::class)
class DesktopApiUsageAndRefreshGateTest {
    private lateinit var tempDir: Path

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-api-usage")
    }

    @After
    fun teardown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `api usage counts per endpoint with errors and quota refusals`() {
        val db = DesktopWeatherDatabase(tempDir.resolve("weather.db"))
        db.initialize()
        val dao = DesktopWeatherDao(db)
        val day = 1_791_417_600_000L
        dao.logApiCall(day, "GOOGLE_WEATHER", "forecast/hours", 200)
        dao.logApiCall(day, "GOOGLE_WEATHER", "forecast/hours", 200)
        dao.logApiCall(day, "GOOGLE_WEATHER", "forecast/hours", 429)
        dao.logApiCall(day, "GOOGLE_WEATHER", "currentConditions", 500)

        val rows = db.getConnection().use { conn ->
            conn.createStatement().executeQuery(
                "SELECT endpoint, callCount, errorCount, quotaRefusedCount FROM api_usage_stats ORDER BY endpoint",
            ).use { rs ->
                buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))) }
            }
        }
        assertEquals(listOf(listOf("currentConditions", 1, 1, 0), listOf("forecast/hours", 3, 1, 1)), rows)
    }

    @Test
    fun `a google request late in the Pacific evening is filed on the console's day from any zone`() {
        val db = DesktopWeatherDatabase(tempDir.resolve("weather.db"))
        db.initialize()
        val dao = DesktopWeatherDao(db)
        // 23:30 PDT Oct 8 = 09:30 Oct 9 in Kyiv.
        val now = java.time.Instant.parse("2026-10-09T06:30:00Z")
        val kyiv = java.time.ZoneId.of("Europe/Kyiv")
        for ((host, path) in listOf("weather.googleapis.com" to "/v1/forecast/hours:lookup", "api.weather.gov" to "/points/50.45,30.52")) {
            val key = com.weatherwidget.data.remote.ApiUsageClassifier.classify(host, path)!!
            dao.logApiCall(com.weatherwidget.data.remote.ApiUsageClassifier.usageDayMs(key.source, now, kyiv), key.source, key.endpoint, 200)
        }

        val rows = db.getConnection().use { conn ->
            conn.createStatement().executeQuery("SELECT apiSource, date FROM api_usage_stats ORDER BY apiSource").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to java.time.LocalDate.ofEpochDay(rs.getLong(2) / 86_400_000L).toString()) }
            }
        }
        assertEquals(listOf("GOOGLE_WEATHER" to "2026-10-08", "NWS" to "2026-10-09"), rows)
    }

    @Test
    fun `wake and network restore refresh daily but not hourly`() {
        assertTrue(launchHourlyLimited("network:restored", ViewMode.TEMPERATURE))
        assertTrue(launchHourlyLimited("resume:logind", ViewMode.DAILY))
    }

    @Test
    fun `wake and network restore refetch the forecast only when due by the cadence`() {
        val cadence = 4 * 3_600_000L
        val staleAfter = launchForecastStaleAfterMs("network:restored") { cadence }
        assertEquals(cadence, staleAfter)
        // 01:51 on 2026-10-08: a 100-minute-old forecast was refetched on network restore. Now only
        // the observations catch up.
        val action = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = 0L,
            lastForecastFetchMs = 0L,
            nowMs = 100 * 60_000L,
            forecastStaleAfterMs = staleAfter,
        )
        assertEquals(LaunchRefreshAction.OBSERVATIONS, action)
        assertEquals(Long.MAX_VALUE, launchForecastStaleAfterMs("resume:logind") { null })
        assertEquals(FORECAST_FRESHNESS_THRESHOLD_MS, launchForecastStaleAfterMs("startup") { cadence })
    }

    @Test
    fun `cycling the source refetches only past four hours`() {
        val staleAfter = launchForecastStaleAfterMs(SOURCE_CYCLE_REASON) { 1L }
        assertEquals(4 * 3_600_000L, staleAfter)
        fun actionAt(ageMs: Long) = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = 1_000_000_000L,
            lastForecastFetchMs = 1_000_000_000L - ageMs,
            nowMs = 1_000_000_000L,
            forecastStaleAfterMs = staleAfter,
        )
        assertEquals(LaunchRefreshAction.NONE, actionAt(40 * 60_000L))
        assertEquals(LaunchRefreshAction.FULL_FORECAST, actionAt(4 * 3_600_000L))
        // A source with nothing cached still fetches at once.
        assertEquals(
            LaunchRefreshAction.FULL_FORECAST,
            determineLaunchRefreshAction(false, null, null, 1_000_000_000L, staleAfter),
        )
    }

    @Test
    fun `source cycling limits hourly only in the daily view`() {
        assertTrue(launchHourlyLimited(SOURCE_CYCLE_REASON, ViewMode.DAILY))
        assertFalse(launchHourlyLimited(SOURCE_CYCLE_REASON, ViewMode.TEMPERATURE))
        assertFalse(launchHourlyLimited("startup", ViewMode.DAILY))
        assertFalse(launchHourlyLimited("source_or_location_change", ViewMode.DAILY))
    }

    @Test
    fun `a source switch alone is a source cycle, a location change is not`() {
        val before = DesktopConfig(lat = 37.4, lon = -122.0, label = "Mountain View")
        val switched = before.copy(settings = before.settings.copy(weatherSource = "GOOGLE_WEATHER"))
        assertEquals(SOURCE_CYCLE_REASON, daemonFetchRestartReason(before, switched))
        assertEquals("source_or_location_change", daemonFetchRestartReason(before, switched.copy(lat = 40.0)))
    }
}

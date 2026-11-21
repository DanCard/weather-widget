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
    fun `startup refetches the forecast only when due by the cadence`() {
        val cadence = 4 * 3_600_000L
        val staleAfter = launchForecastStaleAfterMs(STARTUP_REASON, isHighBattery = { false }) { cadence }
        assertEquals(cadence, staleAfter)
        assertEquals(Long.MAX_VALUE, launchForecastStaleAfterMs(STARTUP_REASON, isHighBattery = { false }) { null })

        fun atStartup(forecastAgeMin: Long?, cachePresent: Boolean = true) = determineLaunchRefreshAction(
            cachePresent = cachePresent,
            lastObservationFetchMs = 0L,
            lastForecastFetchMs = forecastAgeMin?.let { 1_000L * 60_000L - it * 60_000L },
            nowMs = 1_000L * 60_000L,
            forecastStaleAfterMs = staleAfter,
        )
        // 09:11 on 2026-10-08: a rebuild-and-restart refetched a 40-minute-old Google forecast.
        assertEquals(LaunchRefreshAction.OBSERVATIONS, atStartup(forecastAgeMin = 40))
        assertEquals(LaunchRefreshAction.FULL_FORECAST, atStartup(forecastAgeMin = 7 * 60))
        assertEquals(LaunchRefreshAction.FULL_FORECAST, atStartup(forecastAgeMin = 40, cachePresent = false))
        assertEquals(LaunchRefreshAction.FULL_FORECAST, atStartup(forecastAgeMin = null))
    }

    @Test
    fun `wake and network restore refetch the forecast only when due by the cadence`() {
        val cadence = 4 * 3_600_000L
        val staleAfter = launchForecastStaleAfterMs("network:restored", isHighBattery = { false }) { cadence }
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
        // A catch-up never leaves a forecast older than 8 h, even with the cadence off.
        assertEquals(CATCH_UP_FORECAST_MAX_AGE_MS, launchForecastStaleAfterMs("resume:logind", isHighBattery = { false }) { null })
        // A location the user just picked refetches past the source-toggle limit, not 15 min: an
        // 86-minute-old Mountain View forecast was refetched on 2026-11-21.
        assertEquals(4 * 3_600_000L, launchForecastStaleAfterMs(LOCATION_CHANGE_REASON, isHighBattery = { false }) { cadence })
        assertEquals(2 * 3_600_000L, launchForecastStaleAfterMs(LOCATION_CHANGE_REASON, isHighBattery = { true }) { cadence })
    }

    @Test
    fun `cycling the source off a charger refetches only past four hours`() {
        val staleAfter = launchForecastStaleAfterMs(SOURCE_CYCLE_REASON, isHighBattery = { false }) { 1L }
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

    /** 2026-11-01: a switch to Google at 3.09 h logged action=NONE with the forecast due every 2 h. */
    @Test
    fun `cycling the source on a charger refetches past two hours`() {
        val nowMs = 1_000_000_000L
        val staleAfter = launchForecastStaleAfterMs(SOURCE_CYCLE_REASON, isHighBattery = { true }) { 1L }
        fun actionAt(ageMs: Long) = determineLaunchRefreshAction(
            cachePresent = true,
            lastObservationFetchMs = nowMs,
            lastForecastFetchMs = nowMs - ageMs,
            nowMs = nowMs,
            forecastStaleAfterMs = staleAfter,
        )
        assertEquals(LaunchRefreshAction.NONE, actionAt(119 * 60_000L))
        assertEquals(LaunchRefreshAction.FULL_FORECAST, actionAt(11_114_571L))
    }

    @Test
    fun `a source switch alone is a source cycle, a location change is not`() {
        val before = DesktopConfig(lat = 37.4, lon = -122.0, label = "Mountain View")
        val switched = before.copy(settings = before.settings.copy(weatherSource = "GOOGLE_WEATHER"))
        assertEquals(SOURCE_CYCLE_REASON, daemonFetchRestartReason(before, switched))
        assertEquals("source_or_location_change", daemonFetchRestartReason(before, switched.copy(lat = 40.0)))
    }

    /** 2026-11-18: a Settings drag (reorder only) refetched Google's 37-min-old forecast, 8 hour pages. */
    @Test
    fun `adding, removing or reordering sources is a list edit, never a location change`() {
        val before = DesktopConfig(lat = 37.4, lon = -122.0, label = "Mountain View").let {
            it.copy(
                settings = it.settings.copy(
                    weatherSource = "GOOGLE_WEATHER",
                    visibleSources = listOf("NWS", "SILURIAN", "GOOGLE_WEATHER", "OPEN_METEO"),
                ),
            )
        }
        fun withSources(vararg ids: String) = before.copy(settings = before.settings.copy(visibleSources = ids.toList()))
        val cases = mapOf(
            "reorder" to withSources("NWS", "SILURIAN", "OPEN_METEO", "GOOGLE_WEATHER"),
            "add" to withSources("NWS", "SILURIAN", "GOOGLE_WEATHER", "OPEN_METEO", "TOMORROW_IO"),
            "remove" to withSources("NWS", "GOOGLE_WEATHER", "OPEN_METEO"),
            "remove the displayed source" to withSources("NWS", "SILURIAN", "OPEN_METEO"),
        )
        for ((name, after) in cases) {
            val reason = daemonFetchRestartReason(before, after)
            println("$name -> $reason (display ${before.displaySource} -> ${after.displaySource})")
            assertEquals(name, SOURCE_LIST_EDIT_REASON, reason)
            assertTrue(name, launchRefreshSurvivesRestart(reason!!))
        }
        assertEquals(
            "a location change still wins",
            "source_or_location_change",
            daemonFetchRestartReason(before, cases.getValue("add").copy(lon = -121.0)),
        )
        assertEquals(
            "an explicit source switch in the same save is a switch",
            SOURCE_CYCLE_REASON,
            daemonFetchRestartReason(before, cases.getValue("reorder").let { it.copy(settings = it.settings.copy(weatherSource = "NWS")) }),
        )
    }

    @Test
    fun `a list edit fetches only when the shown source has nothing to draw`() {
        assertEquals(LaunchRefreshAction.NONE, sourceListEditLaunchAction(cachePresent = true))
        assertEquals(LaunchRefreshAction.FULL_FORECAST, sourceListEditLaunchAction(cachePresent = false))
    }

    @Test
    fun `a notebook resumed on low battery refetches a forecast older than 8 h, not 24 h`() {
        val hour = 3_600_000L
        val lowBatteryCadence = 24 * hour
        for (reason in listOf("resume:logind", "network:restored")) {
            val staleAfter = launchForecastStaleAfterMs(reason, isHighBattery = { false }) { lowBatteryCadence }
            assertEquals(8 * hour, staleAfter)
            val action = determineLaunchRefreshAction(
                cachePresent = true,
                lastObservationFetchMs = 0L,
                lastForecastFetchMs = 0L,
                nowMs = 18 * hour,
                forecastStaleAfterMs = staleAfter,
            )
            assertEquals(LaunchRefreshAction.FULL_FORECAST, action)
        }
        // A shorter cadence still wins (high battery: 4 h).
        assertEquals(4 * hour, launchForecastStaleAfterMs("resume:logind", isHighBattery = { false }) { 4 * hour })
        // The daemon's own launch keeps the cadence: a restart is not the user arriving.
        assertEquals(lowBatteryCadence, launchForecastStaleAfterMs(STARTUP_REASON, isHighBattery = { false }) { lowBatteryCadence })
    }
}

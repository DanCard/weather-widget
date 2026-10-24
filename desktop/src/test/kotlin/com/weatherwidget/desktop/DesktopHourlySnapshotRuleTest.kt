package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.history.HourlySnapshotRun
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/**
 * The snapshot rule on desktop end to end — [DesktopHourlySnapshotHost] over the config, the 8-day
 * gate ([DesktopSourceFetchGate]) and the DAO, through [HourlySnapshotRun]
 * (performance/261024-hourly-snapshot-windows-by-view-and-battery.md). Desktop used to save every
 * source every 4 h.
 */
@Category(ShortDuration::class)
class DesktopHourlySnapshotRuleTest {
    private val path = Files.createTempFile("weather_snapshot_rule", ".db")
    private val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
    private val lat = 37.4166014
    private val lon = -122.0888722
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L

    // No source_view_tracking row: the gate's grace period keeps every usable source "viewed".
    private val config = DesktopConfig(
        lat = lat, lon = lon, label = "Mountain View",
        settings = DesktopSettings(visibleSources = listOf("NWS", "OPEN_METEO"), weatherSource = "NWS"),
    )

    @After
    fun delete() {
        Files.deleteIfExists(path)
    }

    private fun snapshotAt(source: String, label: Long) =
        dao.upsertHourlyForecastHistory(lat, lon, source, label, listOf(HourlyForecast(now + 24 * hour, 60f, "Cloudy")))

    private fun label(source: String) = runBlocking {
        HourlySnapshotRun.run(DesktopHourlySnapshotHost(dao, source, lat, lon, config, now), now).labelMs
    }

    @Test
    fun `the displayed source saves every fetch under its own time`() {
        snapshotAt("NWS", now - hour)
        assertEquals(now, label("NWS"))
    }

    /** The gap for this machine's power state: 11 h on a charger or ≥ 70 % (no battery reads as charging), else 22 h. */
    private val gap = PowerDetector.getPowerState().let {
        if (com.weatherwidget.data.remote.ForecastHorizonCadence.isHighBattery(it.isCharging, it.batteryLevel)) {
            HourlySnapshotRun.OTHER_GAP_HIGH_BATTERY_MS
        } else {
            HourlySnapshotRun.OTHER_GAP_LOW_BATTERY_MS
        }
    }

    @Test
    fun `another source skips while its last snapshot is within the gap`() {
        snapshotAt("OPEN_METEO", now - gap + hour)
        assertNull(label("OPEN_METEO"))
    }

    @Test
    fun `another source saves once its last snapshot is past the gap`() {
        snapshotAt("OPEN_METEO", now - gap - hour)
        assertEquals(now, label("OPEN_METEO"))
    }

    @Test
    fun `the decision is logged once per fetch`() {
        label("OPEN_METEO")
        assertEquals(1, dao.getRecentLogsByTags(listOf(HourlySnapshotRun.TAG), limit = 10).size)
    }
}

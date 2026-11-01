package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.history.HourlySnapshotRun
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/**
 * The snapshot rule on desktop end to end — [DesktopHourlySnapshotHost] over the DAO, through
 * [HourlySnapshotRun] (plans/261101-every-fetch-saves-an-hourly-snapshot.md).
 */
@Category(ShortDuration::class)
class DesktopHourlySnapshotRuleTest {
    private val path = Files.createTempFile("weather_snapshot_rule", ".db")
    private val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
    private val lat = 37.4166014
    private val lon = -122.0888722
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L

    @After
    fun delete() {
        Files.deleteIfExists(path)
    }

    private fun snapshotAt(source: String, label: Long) =
        dao.upsertHourlyForecastHistory(lat, lon, source, label, listOf(HourlyForecast(now + 24 * hour, 60f, "Cloudy")))

    private fun label(source: String) = runBlocking {
        HourlySnapshotRun.run(DesktopHourlySnapshotHost(dao, source, lat, lon), now).labelMs
    }

    @Test
    fun `a fetch soon after the last snapshot still saves, under its own time`() {
        snapshotAt("OPEN_METEO", now - hour)
        assertEquals(now, label("OPEN_METEO"))
    }

    @Test
    fun `a call within 5 minutes of the last snapshot reuses its label`() {
        val last = now - 60_000L
        snapshotAt("OPEN_METEO", last)
        assertEquals(last, label("OPEN_METEO"))
    }

    @Test
    fun `the decision is logged once per fetch`() {
        label("OPEN_METEO")
        assertEquals(1, dao.getRecentLogsByTags(listOf(HourlySnapshotRun.TAG), limit = 10).size)
    }
}

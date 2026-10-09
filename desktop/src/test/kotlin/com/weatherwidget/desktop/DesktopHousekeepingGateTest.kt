package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/**
 * The daily history-snapshot prune runs only on AC with the screen off (user, 2026-10-09): a due
 * prune otherwise waits for a later refresh, leaving no row that would push it back a day.
 */
@Category(ShortDuration::class)
class DesktopHousekeepingGateTest {
    private val tempDbPath = Files.createTempFile("weather-housekeeping-gate-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val lat = 37.4220
    private val lon = -122.0841
    private val now = System.currentTimeMillis()

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    private fun repo(allowed: () -> Boolean) = DesktopWeatherRepository(
        mockk(relaxed = true), dao, lat, lon, WeatherSource.OPEN_METEO.id,
        currentTimeMillis = { now },
        housekeepingAllowed = allowed,
    )

    private fun pruneRows() = dao.getRecentLogsByTags(listOf("HISTORY_PRUNE"), limit = 10)

    private fun seed() {
        dao.log("CHANCE_BACKFILL_DONE", "done")
        dao.log("FROZEN_DISPLAY_BACKFILL_DONE", "done")
        dao.upsertHourlyForecastHistory(
            lat, lon, WeatherSource.OPEN_METEO.id, now - 3_600_000L,
            listOf(HourlyForecast(now, 60f, "Clear", source = WeatherSource.OPEN_METEO.id)),
        )
    }

    @Test
    fun `a due prune waits while on battery or with the screen on`() {
        seed()
        var asked = 0
        repo { asked++; false }.pruneHistorySnapshotsIfDue(now)

        assertEquals(1, asked)
        assertEquals("nothing pruned, nothing logged", 0, pruneRows().size)
    }

    @Test
    fun `a due prune runs on AC with the screen off`() {
        seed()
        repo { true }.pruneHistorySnapshotsIfDue(now)

        assertEquals(1, pruneRows().size)
    }

    @Test
    fun `a prune that is not due never probes power or screen`() {
        seed()
        repo { true }.pruneHistorySnapshotsIfDue(now)
        var asked = 0
        repo { asked++; true }.pruneHistorySnapshotsIfDue(now + 3_600_000L)

        assertEquals(0, asked)
    }
}

package com.weatherwidget.desktop

import com.weatherwidget.data.local.HistoryPruneSchedule
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/**
 * The daily hourly prune waits for AC with the screen off (user, 2026-10-09), but only for so long,
 * and a blocked prune says so in the log: a silent gate stalled it for weeks.
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

    private fun repo(onPower: () -> Boolean = { true }, screenOff: () -> Boolean = { true }) = DesktopWeatherRepository(
        mockk(relaxed = true), dao, lat, lon, WeatherSource.OPEN_METEO.id,
        currentTimeMillis = { now },
        isCharging = onPower,
        screenOff = screenOff,
    )

    private fun pruneRows() = dao.getRecentLogsByTags(listOf("HISTORY_PRUNE"), limit = 10)

    private fun markers() = dao.getRecentLogsByTags(listOf(HistoryPruneSchedule.LAST_RUN_TAG), limit = 10)

    /** A completed prune [hours] ago; 0 = the marker is absent. */
    private fun lastRan(hours: Long) = dao.replaceLogMarker(HistoryPruneSchedule.LAST_RUN_TAG, "completed", now - hours * 3_600_000L)

    private fun seed() {
        dao.log("CHANCE_BACKFILL_DONE", "done")
        dao.log("FROZEN_DISPLAY_BACKFILL_DONE", "done")
        dao.upsertHourlyForecastHistory(
            lat, lon, WeatherSource.OPEN_METEO.id, now - 3_600_000L,
            listOf(HourlyForecast(now, 60f, "Clear", source = WeatherSource.OPEN_METEO.id)),
        )
    }

    @Test
    fun `a due prune waits with the screen on, and says so once`() = runBlocking {
        seed()
        lastRan(hours = 25)
        repo(screenOff = { false }).pruneHistorySnapshotsIfDue(now)
        repo(screenOff = { false }).pruneHistorySnapshotsIfDue(now + 900_000L)

        assertEquals("skipped=waiting_for_idle overdue=false", pruneRows().single().message)
        assertEquals("the marker did not move", now - 25 * 3_600_000L, markers().single().timestamp)
    }

    @Test
    fun `a due prune runs on AC with the screen off`() = runBlocking {
        seed()
        lastRan(hours = 25)
        repo().pruneHistorySnapshotsIfDue(now)

        assertEquals(now, markers().single().timestamp)
        assertTrue(pruneRows().single().message.startsWith("days="))
    }

    @Test
    fun `an overdue prune runs with the screen on`() = runBlocking {
        seed()
        lastRan(hours = 73)
        repo(screenOff = { false }).pruneHistorySnapshotsIfDue(now)

        assertEquals(now, markers().single().timestamp)
        assertTrue(pruneRows().any { it.message == "overdue=true" })
    }

    @Test
    fun `a prune that never ran is overdue`() = runBlocking {
        seed()
        repo(screenOff = { false }).pruneHistorySnapshotsIfDue(now)

        assertEquals(now, markers().single().timestamp)
    }

    @Test
    fun `an overdue prune on battery still waits, and says it is overdue`() = runBlocking {
        seed()
        lastRan(hours = 73)
        repo(onPower = { false }).pruneHistorySnapshotsIfDue(now)

        assertEquals("skipped=waiting_for_idle overdue=true", pruneRows().single().message)
        assertEquals(now - 73 * 3_600_000L, markers().single().timestamp)
    }

    @Test
    fun `a prune that is not due never probes power or screen`() = runBlocking {
        seed()
        repo().pruneHistorySnapshotsIfDue(now)
        var asked = 0
        repo(onPower = { asked++; true }, screenOff = { asked++; true }).pruneHistorySnapshotsIfDue(now + 3_600_000L)

        assertEquals(0, asked)
    }

    @Test
    fun `the housekeeping pass applies the 18-month age limit`() = runBlocking {
        seed()
        val old = now - (com.weatherwidget.data.local.RetentionPolicy.HOURLY_HISTORY_SETTLED_DAYS + 1) * 24 * 3_600_000L
        dao.upsertHourlyForecastHistory(
            lat, lon, WeatherSource.OPEN_METEO.id, old,
            listOf(HourlyForecast(old, 60f, "Clear", source = WeatherSource.OPEN_METEO.id, fetchedAt = old)),
        )
        val hoursBefore = dao.getAllHourlyDateTimesForTest()
        assertTrue(old in hoursBefore)

        repo().pruneHistorySnapshotsIfDue(now)

        assertFalse(old in dao.getAllHourlyDateTimesForTest())
    }

    private fun DesktopWeatherDao.getAllHourlyDateTimesForTest(): List<Long> =
        database.getConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT dateTime FROM hourly_forecasts").use { rs ->
                    buildList { while (rs.next()) add(rs.getLong(1)) }
                }
            }
        }
}

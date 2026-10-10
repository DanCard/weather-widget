package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.GoogleHistoryRefill
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files

/**
 * [DesktopWeatherRepository.refresh] + [GoogleHistoryRefill] + the real SQLite DAO + the stitched
 * hourly read: a manual refresh writes Google's `history/hours` values over the live hours whose
 * forecast went stale; an automatic refresh leaves them. The 2026-10-09 shape: every hour kept a
 * forecast fetched long before it began. plans/261010-google-history-refills-stale-elapsed-hours.md
 */
@Category(ShortDuration::class)
class DesktopGoogleHistoryRefillTest {
    private val tempDbPath = Files.createTempFile("weather-google-history-refill-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val service = mockk<WeatherApiClient>(relaxed = true)
    private val google = WeatherSource.GOOGLE_WEATHER.id
    private val lat = 37.4166014
    private val lon = -122.0888722
    private val hour = 3_600_000L
    private val zone = java.time.ZoneId.systemDefault()
    private fun at(text: String): Long = java.time.LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    /** The 2026-10-10 07:09 refresh, the morning after the quota ran out. */
    private val now = at("2026-10-10T07:09")
    private val yesterday = java.time.LocalDate.of(2026, 10, 9)
    private fun hours(from: String, toExclusive: String) =
        generateSequence(at(from)) { it + hour }.takeWhile { it < at(toExclusive) }.toList()

    /** Yesterday's hours inside the 24 h window, all kept from the 00:00 fetch. */
    private val staleYesterday = hours("2026-10-09T08:00", "2026-10-10T00:00")
    /** Today's elapsed hours, equally stale, but not the day being refreshed. */
    private val staleToday = hours("2026-10-10T00:00", "2026-10-10T06:00")
    private val staleHours = staleYesterday + staleToday
    /** Yesterday 23:00 re-seeded as fetched at 22:30: kept current, so never refilled. */
    private val freshHour = at("2026-10-09T23:00")

    private fun repo() = DesktopWeatherRepository(service, dao, lat, lon, google)

    /** Raw insert: the DAO's upsert stamps fetchedAt = now, and these rows must be old. */
    private fun insertLive(dateTime: Long, temperature: Float, fetchedAt: Long) {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, fetchedAt) " +
                    "VALUES (?, ?, ?, ?, 'Clear', ?, ?)",
            ).use { stmt ->
                stmt.setLong(1, dateTime)
                stmt.setDouble(2, LocationMatch.quantize(lat))
                stmt.setDouble(3, LocationMatch.quantize(lon))
                stmt.setFloat(4, temperature)
                stmt.setString(5, google)
                stmt.setLong(6, fetchedAt)
                stmt.executeUpdate()
            }
        }
    }

    private fun seed() {
        // The morning fetch that every later hour kept: 71.7 everywhere.
        staleHours.forEach { insertLive(it, 71.7f, fetchedAt = at("2026-10-09T00:00")) }
        insertLive(freshHour, 66f, fetchedAt = at("2026-10-09T22:30"))
        // The as-issued snapshot of one stale hour, which the refill must not touch.
        dao.upsertHourlyForecastHistory(
            lat, lon, google, at("2026-10-09T00:00"),
            listOf(HourlyForecast(staleYesterday.first(), 71.7f, "Clear", fetchedAt = at("2026-10-09T00:00"))),
        )
    }

    /** Google's estimate of what each elapsed hour was: 69.5 for every hour, stale or not. */
    private val fetchWithHistory = RawFetch(
        hourly = (0 until 72).map { HourlyForecast(at("2026-10-10T07:00") + it * hour, 64f, "Clear") },
        providerHistoryHourly = staleHours.map { HourlyForecast(it, 69.5f, "Clear") },
    )

    private fun live(dateTime: Long): HourlyForecast =
        dao.getHourlyForecasts(lat, lon, google, dateTime, dateTime).single()

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `refreshing yesterday replaces only yesterday's stale live hours`() = runTest {
        seed()
        coEvery { service.fetchForecast(false, false, yesterday) } returns fetchWithHistory

        repo().refresh(now = now, reason = "user_refresh:history", refillDay = yesterday)

        coVerify(exactly = 1) { service.fetchForecast(false, false, yesterday) }
        staleYesterday.filter { it != freshHour }.forEach { assertEquals("stale hour $it refilled", 69.5f, live(it).temperature) }
        assertEquals("a current hour keeps its forecast", 66f, live(freshHour).temperature)
        staleToday.forEach { assertEquals("today is not the refreshed day", 71.7f, live(it).temperature) }
        val snapshot = dao.getHourlyHistory(lat, lon, google, staleYesterday.first(), staleYesterday.first())
        assertEquals("the as-issued snapshot is untouched", 71.7f, snapshot.single().temperature)
        val log = dao.getRecentLogs(50).filter { it.tag == GoogleHistoryRefill.LOG_TAG }
        assertTrue(log.any { it.message.startsWith("day=$yesterday stale=${staleYesterday.size - 1} refilled=${staleYesterday.size - 1} ") })
    }

    @Test
    fun `the stitched hourly read draws the refilled values`() = runTest {
        seed()
        coEvery { service.fetchForecast(false, false, yesterday) } returns fetchWithHistory

        repo().refresh(now = now, reason = "user_refresh:history", refillDay = yesterday)

        val refilled = staleYesterday.filter { it != freshHour }
        val stitched = dao.getHourlyWithHistory(
            lat, lon, google, refilled.first(), refilled.last(), maxAgeMs = Long.MAX_VALUE / 2, nowMs = now,
        )
        assertEquals(refilled, stitched.map { it.dateTime })
        assertTrue(stitched.all { it.temperature == 69.5f })
    }

    @Test
    fun `a refresh without a refill day leaves stale hours alone`() = runTest {
        seed()
        coEvery { service.fetchForecast(false) } returns fetchWithHistory

        repo().refresh(now = now, reason = "user_refresh:settings")

        coVerify(exactly = 0) { service.fetchForecast(any(), any(), any<java.time.LocalDate>()) }
        staleHours.filter { it != freshHour }.forEach { assertEquals(71.7f, live(it).temperature) }
    }

    @Test
    fun `a second refresh of yesterday finds nothing stale`() = runTest {
        seed()
        coEvery { service.fetchForecast(false, false, yesterday) } returns fetchWithHistory
        repo().refresh(now = now, reason = "user_refresh:history", refillDay = yesterday)
        val refilledAt = live(staleYesterday.first()).fetchedAt

        coEvery { service.fetchForecast(false, false, yesterday) } returns fetchWithHistory.copy(
            providerHistoryHourly = staleHours.map { HourlyForecast(it, 50f, "Clear") },
        )
        repo().refresh(now = now, reason = "user_refresh:history", refillDay = yesterday)

        assertEquals(69.5f, live(staleYesterday.first()).temperature)
        assertEquals(refilledAt, live(staleYesterday.first()).fetchedAt)
    }
}

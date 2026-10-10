package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyHourlySummaries
import com.weatherwidget.shared.util.DailyNoonCloudCover
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * [DesktopWeatherRepository.refresh] + [DesktopWeatherDao.upsertForecasts] + the shared resolvers:
 * hourly is stored to 72 h, and the daily rows keep noon cloud and the rain maxima for every day —
 * never blanked by a fetch that does not reach a day, and drawn as the full hourly would have drawn
 * them. `performance/261010-daily-view-summaries-instead-of-far-hourly.md`
 */
@Category(ShortDuration::class)
class DesktopDailySummaryStorageTest {
    private val tempDbPath = Files.createTempFile("weather-daily-summary-test", ".db")
    private val database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
    private val dao = DesktopWeatherDao(database)
    private val service = mockk<WeatherApiClient>(relaxed = true)
    private val om = WeatherSource.OPEN_METEO.id
    private val lat = 37.4220
    private val lon = -122.0841
    private val hour = 3_600_000L
    private val zone = ZoneId.systemDefault()
    private val now = (System.currentTimeMillis() / hour) * hour + 10 * 60_000L
    private val today = LocalDate.now(zone)
    private val days = (0 until 16).map { today.plusDays(it.toLong()) }

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** Distinct per day so a carried value can be told from a recomputed one. */
    private fun download(seed: Int, hoursFromNow: Int = 16 * 24): List<HourlyForecast> {
        val first = at(today, 0)
        val last = (now / hour) * hour + hoursFromNow * hour
        return generateSequence(first) { it + hour }.takeWhile { it < last }.map { ms ->
            val dayIndex = ((ms - first) / (24 * hour)).toInt()
            val h = ((ms - first) / hour % 24).toInt()
            HourlyForecast(
                dateTime = ms,
                temperature = 60f,
                condition = "Cloudy",
                precipProbability = (seed + dayIndex * 3 + if (h >= 20 || h < 8) 1 else 0) % 100,
                cloudCover = (seed + dayIndex * 7 + h) % 100,
            )
        }.toList()
    }

    private val daily = days.map { DailyForecast(it.toString(), 70f, 50f, "Cloudy", precipProbability = 20) }

    private fun repo() = DesktopWeatherRepository(service, dao, lat, lon, om)

    private suspend fun refreshWith(hourly: List<HourlyForecast>) {
        coEvery { service.fetchForecast(false) } returns RawFetch(daily = daily, hourly = hourly)
        repo().refresh(now = now, reason = "test")
    }

    private fun rows() = dao.getDailyForecasts(lat, lon, om).associateBy { LocalDate.parse(it.date) }

    @After
    fun teardown() {
        database.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `a 16-day fetch stores hourly to 72 h and every day's summary on its row`() = runTest {
        refreshWith(download(seed = 5))

        val keepUntil = DailyHourlySummaries.keepUntilMs(now, 72)
        val live = dao.getHourlyForecasts(lat, lon, om, now - 48 * hour, now + 400 * hour)
        assertTrue("live hourly ends before now + 72 h", live.isNotEmpty() && live.maxOf { it.dateTime } < keepUntil)
        val snapshots = dao.getHourlyHistory(lat, lon, om, now, now + 400 * hour)
        assertTrue("snapshots end there too", snapshots.isNotEmpty() && snapshots.maxOf { it.dateTime } < keepUntil)

        val stored = rows()
        days.drop(1).dropLast(1).forEach { day ->
            val row = stored.getValue(day)
            assertNotNull("$day noon", row.noonCloudPercent)
            assertNotNull("$day day", row.hourlyDayPrecipMax)
            assertNotNull("$day night", row.hourlyNightPrecipMax)
            assertEquals("no provider period: stored chance is the hourly max", row.hourlyDayPrecipMax, row.daytimePrecipProbability)
            assertEquals(row.hourlyNightPrecipMax, row.nighttimePrecipProbability)
        }
    }

    @Test
    fun `a later fetch that reaches only 24 h keeps every later day's values`() = runTest {
        refreshWith(download(seed = 5))
        val before = rows()

        refreshWith(download(seed = 40, hoursFromNow = 24))
        val after = rows()

        days.drop(2).dropLast(1).forEach { day ->
            assertEquals("$day", before.getValue(day).hourlySummary, after.getValue(day).hourlySummary)
            assertEquals("$day day chance", before.getValue(day).daytimePrecipProbability, after.getValue(day).daytimePrecipProbability)
        }
    }

    @Test
    fun `a later full fetch replaces the values`() = runTest {
        refreshWith(download(seed = 5))
        val first = rows().getValue(today.plusDays(9))

        refreshWith(download(seed = 40))
        val second = rows().getValue(today.plusDays(9))

        assertTrue(first.noonCloudPercent != second.noonCloudPercent)
        assertTrue(first.hourlyDayPrecipMax != second.hourlyDayPrecipMax)
    }

    /**
     * The display reads the stored hourly first and the row second. For every future day, what it
     * resolves from the 72 h store + the rows must equal what it resolved from the full download.
     */
    @Test
    fun `the daily view resolves the same noon cloud and rain chances as from the full download`() = runTest {
        val full = download(seed = 17).map { it.copy(source = om, fetchedAt = now) }
        refreshWith(full)
        val stored = dao.getHourlyForecasts(lat, lon, om, now - 48 * hour, now + 400 * hour)
        val rows = rows()

        days.dropLast(1).forEach { day ->
            val row = rows.getValue(day)
            val noonBefore = DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(full, day, om)
            val noonAfter = row.noonCloudPercent ?: DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(stored, day, om)
            assertEquals("$day noon", noonBefore, noonAfter)

            fun rain(hourly: List<HourlyForecast>, summary: DailyHourlySummaries.Summary?) =
                DailyRainLabels.resolveDailyLabelPrecipAtSite(
                    isPast = false,
                    displaySourceId = om,
                    daytimePrecipProbability = null,
                    nighttimePrecipProbability = null,
                    precipProbability = 20,
                    hourly = hourly,
                    centerLat = lat,
                    centerLon = lon,
                    targetDate = day,
                    hourlySummary = summary,
                )
            assertEquals("$day rain", rain(full, null), rain(stored, row.hourlySummary))
        }
    }
}

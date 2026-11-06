package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Same-day high cutoff (plans/261004-same-day-high-low-cutoffs.md) on the desktop writer; today's
 * low is never frozen (plans/261106-remove-same-day-low-freeze.md).
 */
@Category(ShortDuration::class)
class DesktopWeatherDaoHindcastCutoffTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val todayStr = today.toString()
    private val tomorrowStr = today.plusDays(1).toString()
    private val lat = 37.42
    private val lon = -122.08
    private val source = "OPEN_METEO"

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("weather_hindcast", ".db")
        db = DesktopWeatherDatabase(tempDbPath)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(tempDbPath)
    }

    private fun at(h: Int, m: Int = 0): Long =
        LocalDateTime.of(today, java.time.LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `before 6 am both low and high are stored`() {
        dao.upsertForecasts(
            lat, lon, source,
            listOf(DailyForecast(todayStr, 80f, 55f, "Sunny")),
            nowMs = at(5, 30),
        )
        val row = dao.getDailyForecasts(lat, lon, source).single()
        assertEquals(80f, row.highTemp)
        assertEquals(55f, row.lowTemp)
    }

    @Test
    fun `after 6 am both the low and the high take the newest fetch`() {
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 80f, 55f, "Sunny")), nowMs = at(5, 30))
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 82f, 50f, "Sunny")), nowMs = at(7, 0))
        val row = dao.getDailyForecasts(lat, lon, source).single()
        assertEquals(82f, row.highTemp)
        assertEquals("low takes the 07:00 fetch, not the pre-6 am prediction", 50f, row.lowTemp)
    }

    @Test
    fun `after 4 pm the high is frozen to the prior forecast`() {
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 80f, 55f, "Sunny")), nowMs = at(5, 30))
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 82f, 50f, "Sunny")), nowMs = at(15, 0))
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 90f, 52f, "Sunny")), nowMs = at(16, 30))
        val row = dao.getDailyForecasts(lat, lon, source).single()
        assertEquals("high stays the pre-4 pm prediction", 82f, row.highTemp)
        assertEquals("low takes the 16:30 fetch", 52f, row.lowTemp)
    }

    @Test
    fun `first write after 4 pm stores the low but no high`() {
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 90f, 52f, "Sunny")), nowMs = at(17, 0))
        val row = dao.getDailyForecasts(lat, lon, source).single()
        assertNull("no hindcast high", row.highTemp)
        assertEquals(52f, row.lowTemp)
    }

    @Test
    fun `tomorrow is never gated`() {
        dao.upsertForecasts(
            lat, lon, source,
            listOf(DailyForecast(tomorrowStr, 80f, 55f, "Sunny")),
            nowMs = at(17, 0),
        )
        val row = dao.getDailyForecasts(lat, lon, source).single()
        assertEquals(80f, row.highTemp)
        assertEquals(55f, row.lowTemp)
    }
}

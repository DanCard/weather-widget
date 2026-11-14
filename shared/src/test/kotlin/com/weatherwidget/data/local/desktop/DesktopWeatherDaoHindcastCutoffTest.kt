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
 * Same-day low (06:00) and high (16:00) cutoffs on the desktop writer: a fresh pre-cutoff forecast
 * is held; a hindcast stands in only when that prior is missing or stale
 * (plans/261113-no-hindcasts-unless-prior-missing.md). Default guard age: 6 h.
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
    fun `after 6 am a fresh pre-6 am low is held while the high still updates`() {
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 80f, 55f, "Sunny")), nowMs = at(5, 30))
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 82f, 50f, "Sunny")), nowMs = at(7, 0))
        val row = dao.getDailyForecasts(lat, lon, source).single()
        assertEquals(82f, row.highTemp)
        assertEquals("low keeps the 05:30 prediction, not the 07:00 hindcast", 55f, row.lowTemp)
    }

    /** Emulator 2026-11-06: a ~50 h-old 57 low held against a fresh 46.6 — a stale prior counts as missing. */
    @Test
    fun `a stale pre-6 am low is replaced once by the first fetch after 6 am`() {
        val evening = LocalDateTime.of(today.minusDays(2), java.time.LocalTime.of(4, 0)).atZone(zone).toInstant().toEpochMilli()
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 74f, 57f, "Sunny")), nowMs = evening)
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 72f, 46.6f, "Sunny")), nowMs = at(8, 0))
        assertEquals(46.6f, dao.getDailyForecasts(lat, lon, source).single().lowTemp)
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 72f, 44f, "Sunny")), nowMs = at(11, 0))
        assertEquals("a later hindcast does not move it", 46.6f, dao.getDailyForecasts(lat, lon, source).single().lowTemp)
    }

    @Test
    fun `after 4 pm the high is frozen to the prior forecast`() {
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 80f, 55f, "Sunny")), nowMs = at(5, 30))
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 82f, 50f, "Sunny")), nowMs = at(15, 0))
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 90f, 52f, "Sunny")), nowMs = at(16, 30))
        val row = dao.getDailyForecasts(lat, lon, source).single()
        assertEquals("high stays the pre-4 pm prediction", 82f, row.highTemp)
        assertEquals("low stays the pre-6 am prediction", 55f, row.lowTemp)
    }

    @Test
    fun `first write after 4 pm with no prior forecast stores both`() {
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast(todayStr, 90f, 52f, "Sunny")), nowMs = at(17, 0))
        val row = dao.getDailyForecasts(lat, lon, source).single()
        assertEquals("no prior: the first fetch after the cutoff stands in", 90f, row.highTemp)
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

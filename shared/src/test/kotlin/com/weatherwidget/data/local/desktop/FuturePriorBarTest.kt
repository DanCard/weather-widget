package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.shared.daily.DailyAxisTemps
import com.weatherwidget.shared.daily.DailyColumnBuilder
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [DesktopWeatherDao] writes + reads + `:shared` [DailyColumnBuilder]: the opt-in "forecast from
 * 24 h ago" left bar on a future column (plans/261110-future-days-prior-forecast-bar.md).
 *
 * Three fetches for day +3 — 74/59 30 h ago, 70/58 20 h ago, 64/58 2 h ago (Open-Meteo Nov 13 2026
 * shape). The bar must show the 74 (the forecast as of a day ago), not the 70 the column already
 * carried as its newest older fetch.
 */
@Category(ShortDuration::class)
class FuturePriorBarTest {
    private val path = Files.createTempFile("future_prior_bar", ".db")
    private val db = DesktopWeatherDatabase(path).apply { initialize() }
    private val dao = DesktopWeatherDao(db)
    private val zone = ZoneId.systemDefault()
    private val now: LocalDateTime = LocalDate.now().atTime(12, 0)
    private val today = now.toLocalDate()
    private val day = today.plusDays(3)
    private val lat = 37.417
    private val lon = -122.089
    private val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    @After
    fun tearDown() {
        Files.deleteIfExists(path)
    }

    private fun write(hoursAgo: Long, high: Float, low: Float) =
        dao.upsertForecasts(lat, lon, "OPEN_METEO", listOf(DailyForecast(day.toString(), high, low, "Clear")), nowMs = nowMs - hoursAgo * hour)

    private fun column(show: Boolean, climateNormal: Boolean = false) = run {
        val epoch = day.toEpochDay() * 86_400_000L
        val shown = dao.getDailyForecasts(lat, lon, "OPEN_METEO").single { it.date == day.toString() }
            .let { if (climateNormal) it.copy(isClimateNormal = true) else it }
        val older = dao.getDailyForecastSnapshots(epoch, epoch, lat, lon, "OPEN_METEO")[day.toString()].orEmpty()
        println("FuturePriorBarTest: shown=${shown.highTemp}/${shown.lowTemp} older=${older.map { "${it.highTemp}/${it.lowTemp}@${(nowMs - it.fetchedAt) / hour}h" }}")
        DailyColumnBuilder.build(
            date = day, label = "Fri", today = today, now = now,
            forecast = shown, actual = null, snapshots = older, hourly = emptyList(), currentTemp = null,
            displaySourceId = "OPEN_METEO", centerLat = lat, centerLon = lon, showFuturePriorBar = show,
        )
    }

    @Test
    fun `setting on draws the forecast from a day ago, not the newest older fetch`() {
        write(30, 74f, 59f)
        write(20, 70f, 58f)
        write(2, 64f, 58f)

        val c = column(show = true)

        assertTrue(c.futurePriorBarDrawn)
        assertEquals(74f, c.snapshotHigh)
        assertEquals(59f, c.snapshotLow)
        assertFalse(c.snapshotIsStale)
        assertEquals(64f, c.solidHigh)
        // The axis reaches the taller prior bar.
        val axis = DailyAxisTemps.drawn(
            c.isPast, c.isToday, c.solidHigh, c.solidLow, c.forecastHigh, c.forecastLow, c.ghostHigh,
            c.snapshotHigh, c.snapshotLow, c.futurePriorBarDrawn,
        )
        assertEquals(74f, axis.max())
    }

    @Test
    fun `setting off draws no future left bar`() {
        write(30, 74f, 59f)
        write(2, 64f, 58f)

        val c = column(show = false)

        assertFalse(c.futurePriorBarDrawn)
        assertNull(c.snapshotIconName)
    }

    @Test
    fun `a day first fetched under a day ago has no left bar`() {
        write(20, 70f, 58f)
        write(2, 64f, 58f)

        assertFalse(column(show = true).futurePriorBarDrawn)
    }

    @Test
    fun `climate-normal filler never gets a left bar`() {
        write(30, 74f, 59f)
        write(2, 64f, 58f)

        assertFalse(column(show = true, climateNormal = true).futurePriorBarDrawn)
    }
}

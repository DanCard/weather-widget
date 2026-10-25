package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.shared.daily.DailyColumnBuilder
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

/**
 * [DesktopWeatherDao] writes + reads + `:shared` [DailyColumnBuilder]: a forecast for today unchanged
 * for three days is stored once and re-stamped (DailyForecastWrite), so it is the row shown, not an
 * older fetch — it must still stand as "yesterday's forecast", solid (it was confirmed at the cutoffs).
 * Skipping the shown row left today with no left bar (2026-10-24; the phone had the same gap).
 */
@Category(ShortDuration::class)
class StableForecastPriorBarTest {
    private val path = Files.createTempFile("stable_forecast", ".db")
    private val db = DesktopWeatherDatabase(path).apply { initialize() }
    private val dao = DesktopWeatherDao(db)
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val lat = 37.417
    private val lon = -122.089
    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    @After
    fun tearDown() {
        Files.deleteIfExists(path)
    }

    @Test
    fun `a forecast unchanged for three days still draws a solid yesterday's-forecast bar`() {
        // Fetched three times a day from three days ago to 02:00 today, 70/50 every time.
        for (back in 3L downTo 0L) for (h in listOf(2, 10, 18)) {
            val t = at(today.minusDays(back), h)
            if (t > at(today, 2)) continue
            dao.upsertForecasts(lat, lon, "OPEN_METEO", listOf(DailyForecast(today.toString(), 70f, 50f, "Clear")), nowMs = t)
        }
        val epoch = today.toEpochDay() * 86_400_000L
        val shown = dao.getDailyForecasts(lat, lon, "OPEN_METEO").single { it.date == today.toString() }
        val older = dao.getDailyForecastSnapshots(epoch, epoch, lat, lon, "OPEN_METEO")[today.toString()].orEmpty()
        assertEquals("stored once", 0, older.size)

        val column = DailyColumnBuilder.build(
            date = today, label = "Today", today = today, now = today.atTime(2, 30),
            forecast = shown, actual = null, snapshots = older, hourly = emptyList(), currentTemp = null,
            displaySourceId = "OPEN_METEO", centerLat = lat, centerLon = lon,
        )

        assertEquals(70f, column.snapshotHigh)
        assertEquals(50f, column.snapshotLow)
        assertFalse("confirmed after both cutoffs: solid, not a stand-in", column.snapshotIsStale)
    }
}

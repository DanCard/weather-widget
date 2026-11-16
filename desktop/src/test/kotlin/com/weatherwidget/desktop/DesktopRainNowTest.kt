package com.weatherwidget.desktop

import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.unit.Density
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.data.model.StationType
import com.weatherwidget.shared.observations.RadarNowSample
import com.weatherwidget.shared.observations.RainNow
import com.weatherwidget.shared.util.WeatherConditionResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.xml.sax.InputSource
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId

/** Desktop's rain-now header (plans/261115-header-rain-now-dots.md): the daemon's radar file and the snapshot's rows. */
@Category(ShortDuration::class)
class DesktopRainNowTest {
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 11, 15, 21, 0)
    private val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    private val lat = 37.417
    private val lon = -122.089

    private fun knuq(raw: String, ageMin: Int) = ObservationReading(
        stationId = "KNUQ", stationName = "Moffett", timestamp = nowMs - ageMin * 60_000L, temperature = 54f,
        condition = "", locationLat = lat, locationLon = lon, distanceKm = 3.8f, stationType = StationType.OFFICIAL,
        api = "NWS", rawMetar = raw, isMetar = true,
    )

    private fun snapshot(observations: List<ObservationReading>) = ForecastSnapshot(
        sourceId = "NWS",
        raw = RawFetch(
            hourly = (20..23).map { h ->
                HourlyForecast(
                    dateTime = now.withHour(h).atZone(zone).toInstant().toEpochMilli(), temperature = 55f, condition = "Clear",
                    cloudCover = 0, source = "NWS", fetchedAt = 1L, locationLat = lat, locationLon = lon, precipProbability = 0,
                )
            },
            daily = listOf(DailyForecast(date = now.toLocalDate().toString(), highTemp = 60f, lowTemp = 50f, condition = "Clear")),
            rawObservations = observations,
        ),
        resolved = ResolvedView(currentTemp = 54f, currentCondition = "Clear"),
    )

    private fun store(dir: File) = FileRadarNowStore(File(dir, "radar-now.txt"))

    @Test
    fun `a KNUQ -RA report turns the daily header into rain dots`() {
        val dir = Files.createTempDirectory("radar-now").toFile()
        val snap = snapshot(listOf(knuq("KNUQ 160455Z AUTO 33004KT 2SM -RA BR OVC008 12/12 A3008 RMK AO2", 5)))
        val level = desktopRainNow(snap, lat, lon, nowMs, store(dir))
        assertEquals(RainNow.Level.RAINING, level)
        val content = desktopHeaderContent(
            forecast = snap, displaySourceId = "NWS", viewMode = ViewMode.DAILY, currentTemp = 54f, deltaFromYesterday = null,
            now = now, centerTime = now, lat = lat, lon = lon, useCelsius = false, widthDp = 900, rainNow = level,
        )
        println("HEADER icon=${content.iconName}")
        assertEquals(WeatherConditionResolver.IC_RAIN_NOW, content.iconName)
    }

    @Test
    fun `the daemon's radar file turns the dots on when stations are dry`() {
        val dir = Files.createTempDirectory("radar-now").toFile()
        val dry = snapshot(listOf(knuq("KNUQ 160455Z AUTO 33004KT 10SM OVC008 12/11 A3008 RMK AO2", 5)))
        assertNull(desktopRainNow(dry, lat, lon, nowMs, store(dir)))

        // Written by one store (the daemon), read by another (the UI process).
        store(dir).write(RadarNowSample(lat, lon, nowMs - 3 * 60_000, 86, 149, 193, 255))
        assertEquals(RainNow.Level.SPRINKLING, desktopRainNow(dry, lat, lon, nowMs, store(dir)))
    }

    @Test
    fun `the three rain-now drawables load as Compose vectors`() {
        val names = listOf(
            WeatherConditionResolver.IC_RAIN_NOW_LIGHT,
            WeatherConditionResolver.IC_RAIN_NOW,
            WeatherConditionResolver.IC_RAIN_NOW_HEAVY,
        )
        for (name in names) {
            val stream = javaClass.classLoader.getResourceAsStream("drawable/$name.xml")
            assertTrue("drawable/$name.xml on the classpath", stream != null)
            val vector = stream!!.use { loadXmlImageVector(InputSource(it), Density(1f)) }
            println("$name viewport=${vector.viewportWidth}x${vector.viewportHeight}")
            assertEquals(24f, vector.viewportWidth)
            assertEquals(24f, vector.viewportHeight)
        }
    }
}

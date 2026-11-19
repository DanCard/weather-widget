package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.shared.settings.DisplaySettings
import com.weatherwidget.test.category.ShortDuration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Header rain lookahead setting (1–8h, default 6h) and [DesktopSettings] <-> [DisplaySettings]
 * mapping on desktop. Android twin: `HeaderRainLookaheadRoboTest`.
 */
@Category(ShortDuration::class)
class DesktopHeaderRainLookaheadTest {
    private val zone = ZoneId.systemDefault()

    @Test
    fun `default is 6 hours and toDisplaySettings mirrors DesktopSettings`() {
        val defaults = DesktopSettings()
        assertEquals(6, defaults.headerRainLookaheadHours)
        assertEquals(DisplaySettings.HEADER_RAIN_LOOKAHEAD.default, defaults.toDisplaySettings().headerRainLookaheadHours)

        val custom = defaults.copy(headerRainLookaheadHours = 8, showDailyZoomOutButton = true)
        val diff = custom.diffFrom(defaults)
        println("desktop diff=$diff")
        assertTrue(diff.contains("headerRainLookaheadHours: 6 -> 8"))
        assertTrue(diff.contains("showDailyZoomOutButton: false -> true"))

        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<DesktopSettings>(json.encodeToString(custom))
        assertEquals(8, decoded.headerRainLookaheadHours)
    }

    @Test
    fun `desktopHeaderContent applies rainLookaheadHours in daily and hourly views`() {
        val now = LocalDateTime.of(2026, 11, 18, 10, 0)
        fun hour(offset: Long, precip: Int) = HourlyForecast(
            dateTime = now.plusHours(offset).atZone(zone).toInstant().toEpochMilli(),
            temperature = 60f,
            precipProbability = precip,
            condition = "Clear",
            source = "NWS",
        )
        val raw = RawFetch(
            daily = emptyList(),
            hourly = listOf(
                hour(0, 0), hour(1, 0), hour(2, 0), hour(3, 35),
                hour(4, 0), hour(5, 0), hour(6, 0), hour(7, 80), hour(8, 0),
            ),
        )
        val snapshot = ForecastSnapshot(
            sourceId = "NWS",
            raw = raw,
            resolved = ResolvedView(currentTemp = 60f),
        )

        for (mode in listOf(ViewMode.DAILY, ViewMode.TEMPERATURE, ViewMode.PRECIPITATION, ViewMode.CLOUD_COVER)) {
            fun precipFor(hours: Int) = desktopHeaderContent(
                forecast = snapshot,
                displaySourceId = "NWS",
                viewMode = mode,
                currentTemp = 60f,
                deltaFromYesterday = null,
                now = now,
                centerTime = now,
                lat = 37.42,
                lon = -122.09,
                useCelsius = false,
                widthDp = 420,
                rainNow = null,
                rainLookaheadHours = hours,
            ).precipProbability

            assertNull("$mode 1h", precipFor(1))
            assertEquals("$mode 6h", 35, precipFor(6))
            assertEquals("$mode 8h", 80, precipFor(8))
        }
    }
}

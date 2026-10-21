package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_CLOUDY
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_RAIN
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The desktop header draws the displayed source's hour, not the nearest station's text: KNUQ's AUTO
 * "CLR" under a 99 % overcast drew a sun (2026-10-21).
 */
@Category(ShortDuration::class)
class DesktopHeaderIconTest {
    private val now = LocalDateTime.of(2026, 10, 21, 10, 3)
    private val lat = 37.42
    private val lon = -122.08

    private fun hour(h: Int, condition: String, cloud: Int) = HourlyForecast(
        dateTime = now.withHour(h).withMinute(0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        temperature = 62f,
        condition = condition,
        cloudCover = cloud,
        source = "GOOGLE_WEATHER",
        fetchedAt = 1L,
        locationLat = lat,
        locationLon = lon,
    )

    private fun content(snapshot: ForecastSnapshot, viewMode: ViewMode = ViewMode.DAILY, centerTime: LocalDateTime = now) =
        desktopHeaderContent(
            forecast = snapshot,
            displaySourceId = "GOOGLE_WEATHER",
            viewMode = viewMode,
            currentTemp = 62.7f,
            deltaFromYesterday = 2.9f,
            now = now,
            centerTime = centerTime,
            lat = lat,
            lon = lon,
            useCelsius = false,
            widthDp = 900,
        )

    @Test
    fun precipAndCloudViewsHaveNoDelta() {
        val s = snapshot(listOf(hour(10, "Cloudy", 99)))
        assertEquals("+2.9", content(s).deltaText)
        assertEquals("+2.9", content(s, ViewMode.TEMPERATURE).deltaText)
        assertNull(content(s, ViewMode.PRECIPITATION).deltaText)
        assertNull(content(s, ViewMode.CLOUD_COVER).deltaText)
    }

    @Test
    fun hourlyViewPannedToAnotherDayNamesIt() {
        val s = snapshot(listOf(hour(10, "Cloudy", 99)))
        assertEquals("Google", content(s, ViewMode.TEMPERATURE).sourceLabel.substringAfter("• "))
        assertTrue(content(s, ViewMode.TEMPERATURE, centerTime = now.plusDays(1)).sourceLabel.contains("•"))
    }

    private fun snapshot(hourly: List<HourlyForecast>, dailyCondition: String = "Sunny") = ForecastSnapshot(
        raw = RawFetch(
            hourly = hourly,
            daily = listOf(DailyForecast(date = now.toLocalDate().toString(), highTemp = 67f, lowTemp = 58f, condition = dailyCondition)),
        ),
        resolved = ResolvedView(currentTemp = 62f, currentCondition = "Clear"),
    )

    @Test
    fun overcastHourBeatsStationClear() {
        val content = content(snapshot(listOf(hour(10, "Cloudy", 99), hour(11, "Cloudy", 99))))
        assertEquals(IC_CLOUDY, content.iconName)
    }

    @Test
    fun noHourlyFallsBackToTodaysDailyCondition() {
        assertEquals(IC_RAIN, content(snapshot(emptyList(), dailyCondition = "Rain")).iconName)
    }
}

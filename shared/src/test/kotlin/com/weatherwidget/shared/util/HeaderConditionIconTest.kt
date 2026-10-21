package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_CLEAR
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_CLOUDY
import com.weatherwidget.shared.util.WeatherConditionResolver.IC_NIGHT
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/** The header icon rule both platforms share (`plans/261021-desktop-header-icon-from-shared-current-hour-rule.md`). */
@Category(ShortDuration::class)
class HeaderConditionIconTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val lat = 37.42
    private val lon = -122.08

    private fun row(at: LocalDateTime, condition: String, cloud: Int?, source: String = "GOOGLE_WEATHER", precip: Int? = null) =
        HourlyForecast(
            dateTime = at.atZone(zone).toInstant().toEpochMilli(),
            temperature = 62f,
            condition = condition,
            precipProbability = precip,
            cloudCover = cloud,
            source = source,
            fetchedAt = 1L,
            locationLat = lat,
            locationLon = lon,
        )

    private val morning = LocalDateTime.of(2026, 10, 21, 10, 3)

    @Test
    fun overcastHourIsCloudyWhateverAStationSays() {
        // 2026-10-21: KNUQ AUTO "CLR" drew a sun; Google said Cloudy 99 % for 10:00 and 11:00.
        val hourly = listOf(row(morning.withMinute(0), "Cloudy", 99), row(morning.withHour(11).withMinute(0), "Cloudy", 99))
        val picked = HeaderConditionIcon.pickRow(hourly, "GOOGLE_WEATHER", morning, preferNextHour = true, zone = zone)!!
        assertEquals(IC_CLOUDY, HeaderConditionIcon.iconName(picked, morning, lat, lon))
        // What the old desktop path drew from the station text.
        assertEquals(IC_CLEAR, WeatherConditionResolver.resolveIconName("Clear"))
    }

    @Test
    fun dailyHeaderPrefersTheComingHour() {
        val hourly = listOf(row(morning.withMinute(0), "Clear", 0), row(morning.withHour(11).withMinute(0), "Cloudy", 99))
        val picked = HeaderConditionIcon.pickRow(hourly, "GOOGLE_WEATHER", morning, preferNextHour = true, zone = zone)
        assertEquals("Cloudy", picked?.condition)
    }

    @Test
    fun hourlyHeaderTakesTheCurrentHour() {
        val hourly = listOf(row(morning.withMinute(0), "Clear", 0), row(morning.withHour(11).withMinute(0), "Cloudy", 99))
        val picked = HeaderConditionIcon.pickRow(hourly, "GOOGLE_WEATHER", morning, preferNextHour = false, zone = zone)
        assertEquals("Clear", picked?.condition)
    }

    @Test
    fun comingHourFallsBackToCurrentWhenMissing() {
        val hourly = listOf(row(morning.withMinute(0), "Partly Cloudy", 40))
        val picked = HeaderConditionIcon.pickRow(hourly, "GOOGLE_WEATHER", morning, preferNextHour = true, zone = zone)
        assertEquals("Partly Cloudy", picked?.condition)
    }

    @Test
    fun comingHourNeverCrossesMidnight() {
        val late = LocalDateTime.of(2026, 10, 21, 23, 10)
        val hourly = listOf(row(late.withMinute(0), "Clear", 0), row(late.plusHours(1).withMinute(0), "Cloudy", 99))
        val picked = HeaderConditionIcon.pickRow(hourly, "GOOGLE_WEATHER", late, preferNextHour = true, zone = zone)
        assertEquals("Clear", picked?.condition)
    }

    @Test
    fun otherSourcesRowsAreIgnored() {
        val hourly = listOf(row(morning.withMinute(0), "Clear", 0, source = "NWS"))
        assertNull(HeaderConditionIcon.pickRow(hourly, "GOOGLE_WEATHER", morning, preferNextHour = true, zone = zone))
    }

    @Test
    fun clearHourAtNightIsTheMoon() {
        val night = LocalDateTime.of(2026, 10, 21, 22, 0)
        assertEquals(IC_NIGHT, HeaderConditionIcon.iconName(row(night, "Clear", 0), night, lat, lon))
    }
}

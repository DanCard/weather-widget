package com.weatherwidget.shared.daily

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.WeatherConditionResolver as R
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * The shared column (Android widget and desktop) draws today's header-rain / current-hour-cloud icon
 * over a contradicting daily icon, and leaves other days alone. emulator-5556 Tomorrow.io 2026-11-20: daily code 1000
 * (Clear) at 94%, Rain 94% at 8am, overcast 6–10am, clear from noon.
 * (plans/261120-today-icon-hourly-rain-override.md)
 */
@Category(ShortDuration::class)
class DailyColumnTodayHourIconTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 20)
    private val tomorrow = today.plusDays(1)
    private val source = "TOMORROW_IO"
    private val lat = 37.417
    private val lon = -122.089

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private val hourly = generateSequence(at(today, 0)) { it + 3_600_000L }
        .takeWhile { it < at(tomorrow.plusDays(1), 0) }
        .map { t ->
            val h = java.time.Instant.ofEpochMilli(t).atZone(zone).hour
            val isToday = t < at(tomorrow, 0)
            val (condition, chance, cloud) = when {
                isToday && h == 8 -> Triple("Rain", 94, 100)
                isToday && h in 6..10 -> Triple("Cloudy", 0, 100)
                else -> Triple("Clear", 0, 3)
            }
            HourlyForecast(
                dateTime = t, temperature = 50f, condition = condition, precipProbability = chance,
                cloudCover = cloud, source = source, locationLat = lat, locationLon = lon,
            )
        }.toList()

    private fun row(date: LocalDate) = DailyForecast(
        date = date.toString(), highTemp = 56f, lowTemp = 42f, condition = "Clear", iconToken = "1000",
        precipProbability = 94, source = source, noonCloudPercent = 3,
        hourlyDayPrecipMax = if (date == today) 94 else 0, hourlyNightPrecipMax = 0,
    )

    private fun build(date: LocalDate, hour: Int, minute: Int = 30) = DailyColumnBuilder.build(
        date = date, label = "x", today = today, now = today.atTime(hour, minute), forecast = row(date),
        actual = null, snapshots = emptyList(), hourly = hourly, currentTemp = null,
        displaySourceId = source, centerLat = lat, centerLon = lon, use24HourClock = false,
        rainLookaheadHours = 1,
    ).also { println("column date=$date now=$hour:$minute icon=${it.iconName}") }

    @Test
    fun `today's icon follows the header rain and the current hour's cloud through the morning`() {
        // 1 h lookahead: 7am 0% → 8am 94% crosses 70% at 7:45, so the next hour holds it from 06:45.
        assertEquals(R.IC_CLOUDY, build(today, 6, 30).iconName) // header 45%; 6am 100% cloud
        assertEquals(R.IC_RAIN, build(today, 7, 30).iconName)
        assertEquals(R.IC_CLOUDY, build(today, 8, 30).iconName) // header 47%: cloud rule
        assertEquals(R.IC_CLEAR, build(today, 13).iconName)
    }

    @Test
    fun `tomorrow keeps its daily icon whatever hour it is`() {
        assertEquals(R.IC_CLEAR, build(tomorrow, 8).iconName)
    }
}

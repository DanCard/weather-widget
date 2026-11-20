package com.weatherwidget.widget.handlers

import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Android path for today's current-hour icon (plans/261120-today-icon-hourly-rain-override.md):
 * hourly rows stored and read back through the real Room `HourlyForecastDao`, then a `GraphDayRequest`
 * carrying the header rain lookahead → `DailyViewLogic.prepareGraphDays` → shared `DailyColumnBuilder`
 * → today's drawable.
 * emulator-5556 Tomorrow.io 2026-11-20: daily code 1000 (Clear) at 94%, Rain 94% at 8am, overcast 6–10am.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TodayHourIconRoomIntegrationTest {
    private lateinit var db: WeatherDatabase
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val tomorrow = today.plusDays(1)
    private val lat = 37.417
    private val lon = -122.089
    private val source = WeatherSource.TOMORROW_IO

    private fun ms(date: LocalDate, h: Int) = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun dayMs(date: LocalDate) = date.toEpochDay() * 86_400_000L

    @Before
    fun setUp() {
        db = TestDatabase.create()
        val fetchedAt = ms(today, 7)
        val rows = (0 until 48).map { i ->
            val t = ms(today, 0) + i * 3_600_000L
            val h = i % 24
            val isToday = i < 24
            val (condition, chance, cloud) = when {
                isToday && h == 8 -> Triple("Rain", 94, 100)
                isToday && h in 6..10 -> Triple("Cloudy", 0, 100)
                else -> Triple("Clear", 0, 3)
            }
            HourlyForecastEntity(
                dateTime = t, locationLat = lat, locationLon = lon, temperature = 50f, condition = condition,
                source = source.id, precipProbability = chance, cloudCover = cloud, fetchedAt = fetchedAt,
            )
        }
        runBlocking { db.hourlyForecastDao().insertAll(rows) }
    }

    @After
    fun tearDown() = db.close()

    private fun daily(date: LocalDate) = ForecastEntity(
        targetDate = dayMs(date), dateOfPrediction = dayMs(today), locationLat = lat, locationLon = lon,
        highTemp = 56f, lowTemp = 42f, condition = "Clear", nativeDailyIconToken = "1000",
        source = source.id, precipProbability = if (date == today) 94 else 0,
        fetchedAt = ms(today, 7), lastConfirmedAt = ms(today, 7),
    )

    private fun iconsAt(hour: Int, minute: Int = 30, lookahead: Int = 1): Map<LocalDate, Int?> {
        val hourly = runBlocking {
            db.hourlyForecastDao().getHourlyForecasts(ms(today, 0), ms(tomorrow.plusDays(1), 0), lat, lon)
        }
        val days = DailyViewLogic.prepareGraphDays(
            GraphDayRequest(
                now = today.atTime(hour, minute),
                centerDate = today,
                today = today,
                weatherByDate = mapOf(today to daily(today), tomorrow to daily(tomorrow)),
                forecastSnapshots = emptyMap(),
                numColumns = 3,
                displaySource = source,
                skipYesterday = true,
                skipHistory = true,
                hourlyForecasts = hourly,
                todayLabel = "Today",
                // Settings → Header rain lookahead (the emulators run 1 h).
                rainLookaheadHours = lookahead,
            ),
        )
        return days.associate { it.date to it.iconRes }
            .also { println("Room path hourly=${hourly.size} now=$hour:$minute lookahead=${lookahead}h icons=$it") }
    }

    @Test
    fun `today's drawable follows the header rain over the set lookahead, tomorrow keeps its daily icon`() {
        // 7am 0% → 8am 94%: with a 1 h lookahead the header reaches 70% from 06:45.
        val at730 = iconsAt(7)
        assertEquals(R.drawable.ic_weather_rain, at730[today])
        assertEquals(R.drawable.ic_weather_clear, at730[tomorrow])
        assertEquals(R.drawable.ic_weather_cloudy, iconsAt(6)[today]) // header 45%; 6am 100% cloud
        assertEquals(R.drawable.ic_weather_rain, iconsAt(6, lookahead = 3)[today]) // the setting reaches the rule
        assertEquals(R.drawable.ic_weather_cloudy, iconsAt(8)[today]) // header 47%: cloud rule
        assertEquals(R.drawable.ic_weather_clear, iconsAt(13)[today])
    }
}

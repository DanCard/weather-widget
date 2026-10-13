package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.TodayLowSettle
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.WidgetConstants
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Today's printed low (`bottomStackLow`) through the real chain: [DailyTodayResolver] →
 * [TodayLowSettle] → `DailyDayValueResolver.effectiveLowForLabel`. A warm night (observed 50 vs
 * forecast 47) prints the forecast until sunrise + 30 min, then the observed low — unless the
 * next 3 h are forecast below 50, which holds it until 09:00. Times are relative to the computed
 * cutoff because the resolver uses the system zone.
 */
@Category(ShortDuration::class)
class DailyTodayResolverLowSettleTest {
    private val zone = ZoneId.systemDefault() // the resolver's zone
    private val today = LocalDate.of(2026, 10, 13)
    private val lat = 37.417
    private val lon = -122.089
    private val source = WeatherSource.NWS
    private val cutoff = TodayLowSettle.sunriseCutoff(today, lat, lon, zone)!!

    private val todayRow = ForecastEntity(
        targetDate = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        dateOfPrediction = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        locationLat = lat,
        locationLon = lon,
        highTemp = 70f,
        lowTemp = 47f,
        condition = "Sunny",
        source = source.id,
        fetchedAt = 1L,
    )

    private val actual = DailyHistory(
        date = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        source = source.id,
        locationLat = lat,
        locationLon = lon,
        computedHighTemp = 55f,
        computedLowTemp = 50f,
        condition = "Clear",
        updatedAt = 1L,
    )

    private fun hour(t: LocalDateTime, temp: Float) = HourlyForecastEntity(
        t.atZone(zone).toInstant().toEpochMilli(), lat, lon, temp, "Sunny", source.id, fetchedAt = 1L,
    )

    private val risingDay = (0..23).map { hour(today.atTime(it, 0), if (it < 7) 51f else 52f + it) }

    private fun printedLow(now: LocalDateTime, hourly: List<HourlyForecastEntity> = risingDay) =
        DailyTodayResolver.resolveTodayValues(
            date = today,
            today = today,
            now = now,
            displaySource = source,
            weather = todayRow,
            dailyActuals = mapOf(today to actual),
            actual = actual,
            forecasts = listOf(todayRow),
            hourlyForecasts = hourly,
            currentTemp = 54f,
        ).bottomStackLow

    @Test
    fun `warm night prints the forecast low until sunrise plus 30`() {
        assumeTrue(cutoff.toLocalTime().isBefore(LocalTime.of(9, 0)))
        assertEquals(47f, printedLow(cutoff.minusMinutes(10)))
    }

    @Test
    fun `warm night prints the observed low after sunrise plus 30`() {
        assertEquals(50f, printedLow(cutoff.plusMinutes(10)))
    }

    private fun dipAt(t: LocalDateTime) =
        risingDay.map { if (it.dateTime == hour(t, 0f).dateTime) it.copy(temperature = 46f) else it }

    @Test
    fun `a dip in the next 3 h keeps the forecast low until 09_00`() {
        val now = cutoff.plusMinutes(10)
        assumeTrue(now.toLocalTime().isBefore(LocalTime.of(8, 0))) // a zone where sunrise + 30 is before 08:00
        val dip = dipAt(now.plusHours(2).truncatedTo(ChronoUnit.HOURS))
        assertEquals(47f, printedLow(now, dip))
        assertEquals(50f, printedLow(today.atTime(9, 0), dip))
    }

    @Test
    fun `an evening cold forecast does not hold the morning low`() {
        assertEquals(50f, printedLow(cutoff.plusMinutes(10), dipAt(today.atTime(22, 0))))
    }
}

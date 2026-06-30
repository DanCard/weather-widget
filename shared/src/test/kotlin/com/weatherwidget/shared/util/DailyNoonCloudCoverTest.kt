package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DailyNoonCloudCoverTest {

    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 6, 19)

    private fun hour(h: Int, cloud: Int?, source: String) = HourlyForecast(
        dateTime = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli(),
        temperature = 70f,
        condition = "Sunny",
        cloudCover = cloud,
        source = source,
    )

    private fun resolve(hourly: List<HourlyForecast>, displaySource: String, rowSource: String? = null) =
        DailyNoonCloudCover.resolveNoonCloudCoverPercent(hourly, date, displaySource, rowSource, zone)

    private fun resolveMeasured(hourly: List<HourlyForecast>, displaySource: String, rowSource: String? = null) =
        DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(hourly, date, displaySource, rowSource, zone)

    @Test
    fun returnsOnlyTheDisplayedSourcesCloud() {
        val hourly = listOf(
            hour(12, 20, "NWS"),
            hour(12, 80, "OPEN_METEO"),
        )
        assertEquals(20, resolve(hourly, "NWS"))
        assertEquals(80, resolve(hourly, "OPEN_METEO"))
    }

    @Test
    fun assumesZeroWhenDisplayedSourceHasNoNoonData() {
        val hourly = listOf(hour(12, 80, "OPEN_METEO"))
        assertEquals(0, resolve(hourly, "NWS"))
    }

    @Test
    fun measuredReturnsNullWhenDisplayedSourceHasNoNoonData() {
        val hourly = listOf(hour(12, 80, "OPEN_METEO"))
        assertEquals(null, resolveMeasured(hourly, "NWS"))
    }

    @Test
    fun usesExactNoonReadingNotNearestHour() {
        val hourly = listOf(
            hour(10, 90, "NWS"),
            hour(12, 22, "NWS"),
            hour(14, 5, "NWS"),
        )
        assertEquals(22, resolve(hourly, "NWS"))
    }

    @Test
    fun assumesZeroWhenNoonIsAbsentEvenIfOtherHoursExist() {
        val hourly = listOf(
            hour(7, 69, "NWS"),
            hour(11, 65, "NWS"),
            hour(13, 25, "NWS"),
        )
        assertEquals(0, resolve(hourly, "NWS"))
    }

    @Test
    fun assumesZeroWhenNoonRowHasNullCloudCover() {
        val hourly = listOf(hour(12, null, "NWS"))
        assertEquals(0, resolve(hourly, "NWS"))
    }

    @Test
    fun genericGapRowUsesGenericHourly() {
        val hourly = listOf(
            hour(12, 50, "NWS"),
            hour(12, 35, WeatherSource.GENERIC_GAP.id),
        )
        assertEquals(35, resolve(hourly, "NWS", rowSource = WeatherSource.GENERIC_GAP.id))
    }

    @Test
    fun assumesZeroForOtherDates() {
        val otherDay = date.plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val hourly = listOf(
            HourlyForecast(otherDay, 70f, "Sunny", cloudCover = 99, source = "NWS"),
        )
        assertEquals(0, resolve(hourly, "NWS"))
    }
}
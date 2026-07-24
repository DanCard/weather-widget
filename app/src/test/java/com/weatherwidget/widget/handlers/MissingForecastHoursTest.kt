package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class MissingForecastHoursTest {

    private val zoneId = ZoneId.of("America/Los_Angeles")
    private val start = LocalDateTime.of(2026, 7, 23, 5, 0)

    @Test
    fun `summarizes contiguous absent and wrong-source forecast anchors separately`() {
        val forecasts = mapOf(
            hour(0) to null,
            hour(1) to null,
            hour(2) to forecast(hour(2), WeatherSource.OPEN_METEO),
            hour(3) to forecast(hour(3), WeatherSource.NWS),
            hour(4) to null,
        )

        val result = summarizeMissingForecastHours(
            startHour = start,
            endHour = start.plusHours(5),
            zoneId = zoneId,
            forecastsByTime = forecasts,
            displaySource = WeatherSource.NWS,
        )

        assertEquals(4, result.missingCount)
        assertEquals(3, result.noSelectedForecastCount)
        assertEquals(1, result.wrongSourceCount)
        assertEquals(
            listOf(
                start to start.plusHours(3),
                start.plusHours(4) to start.plusHours(5),
            ),
            result.spans,
        )
        assertEquals(
            "missing=4 noSelected=3 wrongSource=1 spans=[07-23 05:00..07-23 08:00,07-23 09:00..07-23 10:00]",
            result.diagnosticText(),
        )
    }

    private fun hour(offset: Long): Long = start.plusHours(offset).atZone(zoneId).toInstant().toEpochMilli()

    private fun forecast(timeMs: Long, source: WeatherSource) =
        HourlyForecastEntity(
            dateTime = timeMs,
            locationLat = 37.416,
            locationLon = -122.089,
            temperature = 70f,
            condition = "Clear",
            source = source.id,
            fetchedAt = 1L,
        )
}

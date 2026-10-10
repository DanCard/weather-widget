package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [ActualsAggregator] records WHEN the blended line reached the day's high and low, through the real
 * series builder — the times a past day's forecast overlay is settled against
 * ([ForecastOverlaySettle]). See plans/261004-forecast-overlay-frozen-at-extreme-time.md.
 */
@Category(ShortDuration::class)
class ActualsAggregatorExtremeTimeTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 6, 21)

    @Before
    fun resetCache() {
        ActualsAggregator.blendCache.clear()
    }

    private fun epoch(hour: Int, day: LocalDate = this.day) =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** Cool night, low at 05:00, a flat afternoon whose first within-0.5 °F point is 15:00. */
    private val temps = mapOf(
        0 to 64f, 1 to 63f, 2 to 62f, 3 to 61f, 4 to 60f, 5 to 58f, 6 to 58.3f, 7 to 60f, 8 to 63f,
        9 to 67f, 10 to 72f, 11 to 77f, 12 to 82f, 13 to 86f, 14 to 89f, 15 to 91.6f, 16 to 92f,
        17 to 90f, 18 to 85f, 19 to 79f, 20 to 74f, 21 to 70f, 22 to 67f, 23 to 65f,
    )

    private fun observations(): List<ObservationReading> =
        (listOf(day.minusDays(1) to 23) + temps.keys.map { day to it } + listOf(day.plusDays(1) to 0))
            .map { (d, h) ->
                ObservationReading(
                    stationId = "KNUQ",
                    stationName = "KNUQ",
                    timestamp = epoch(h, d),
                    temperature = if (d == day) temps.getValue(h) else 64f,
                    condition = "observed",
                    locationLat = LAT,
                    locationLon = LON,
                    distanceKm = 2f,
                    api = SOURCE,
                    stationType = StationType.OFFICIAL,
                )
            }

    private fun forecasts(): List<HourlyForecast> {
        val start = LocalDateTime.of(day.minusDays(1), java.time.LocalTime.MIDNIGHT)
        return (0..72).map {
            HourlyForecast(
                dateTime = start.plusHours(it.toLong()).atZone(zone).toInstant().toEpochMilli(),
                temperature = 70f,
                condition = "Clear",
                source = SOURCE,
            )
        }
    }

    @Test
    fun `the aggregated row carries when the high and low were reached`() {
        val row = ActualsAggregator.aggregate(
            observations = observations(),
            hourlyForecasts = forecasts(),
            locationLat = LAT,
            locationLon = LON,
            zoneId = zone,
        ).single { it.source == SOURCE && it.date == day.toEpochDay() * 86_400_000L }

        assertEquals(92f, row.computedHighTemp!!, 0.01f)
        assertEquals(58f, row.computedLowTemp!!, 0.01f)
        // 91.6 at 15:00 is within 0.5 of the 92 peak at 16:00: the high was effectively reached at 15:00.
        assertEquals(epoch(15), row.computedHighAt)
        assertEquals(epoch(5), row.computedLowAt)
    }

    private companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
        val SOURCE: String = WeatherSource.NWS.id
    }
}

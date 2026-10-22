package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The precip graph's "Act" hours combine stations (nearest gauge) instead of adding them — the same
 * `ObservedPrecip` rule as `daily_history` and Android's graph
 * (plans/261021-observed-rain-combine-stations-not-sum.md).
 */
@Category(ShortDuration::class)
class DesktopRainHourStationsTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val hour = LocalDateTime.of(2026, 9, 1, 14, 0)
    private fun epoch(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    private fun station(id: String, distanceKm: Float, amountMm: Float) = ObservationReading(
        stationId = id,
        stationName = id,
        timestamp = epoch(hour.plusMinutes(50)),
        temperature = 60f,
        condition = "Rain",
        locationLat = 37.42,
        locationLon = -122.08,
        distanceKm = distanceKm,
        api = WeatherSource.NWS.id,
        precipAmountMm = amountMm,
    )

    @Test
    fun `three wet stations in one hour are one amount`() {
        val rows = buildRainHours(
            points = listOf(HourlyForecast(dateTime = epoch(hour), temperature = 60f, condition = "Rain", precipAmountMm = 4f, source = "NWS")),
            observations = listOf(station("KNUQ", 3.8f, 3f), station("KPAO", 6.1f, 3f), station("KSJC", 15.9f, 3.3f)),
            now = epoch(hour.plusHours(3)),
        )
        assertEquals(3f, rows.single().actualPrecipAmountMm!!, 0.001f) // the summing code: 9.3
    }
}

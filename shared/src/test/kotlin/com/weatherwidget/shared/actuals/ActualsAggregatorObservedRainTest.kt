package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Integration: [ActualsAggregator] files a wet day's rain through [ObservedPrecip], so three
 * airports that caught the same two showers give the showers' amount, not three times it
 * (plans/261021-observed-rain-combine-stations-not-sum.md).
 */
@Category(ShortDuration::class)
class ActualsAggregatorObservedRainTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.now(zone).minusDays(3)
    private val p0012Mm = 0.12f * 25.4f
    private val p0004Mm = 0.04f * 25.4f

    private fun station(id: String, distanceKm: Float, minute: Int): List<ObservationReading> =
        (0..23).map { h ->
            val p = when (h) {
                14 -> " P0012" // a daytime shower
                22 -> " P0004" // a night shower
                else -> ""
            }
            ObservationReading(
                stationId = id,
                stationName = id,
                timestamp = LocalDateTime.of(day, LocalTime.of(h, minute)).atZone(zone).toInstant().toEpochMilli(),
                temperature = 55f + h % 12,
                condition = if (p.isEmpty()) "Cloudy" else "Rain",
                locationLat = 37.42,
                locationLon = -122.08,
                distanceKm = distanceKm,
                api = WeatherSource.NWS.id,
                precipAmountMm = when (h) { 14 -> p0012Mm; 22 -> p0004Mm; else -> null },
                isMetar = true,
                rawMetar = "$id ${day.dayOfMonth}${"%02d".format(h)}${"%02d".format(minute)}Z AUTO 00000KT 10SM OVC020 15/12 A2990 RMK AO2$p",
            )
        }

    @Test
    fun `a three-station wet day files the showers once`() {
        val rows = station("KNUQ", 3.8f, 55) + station("KPAO", 6.1f, 47) + station("KSJC", 15.9f, 53)
        val nws = ActualsAggregator.aggregate(
            observations = rows,
            hourlyForecasts = emptyList(),
            locationLat = 37.42,
            locationLon = -122.08,
            zoneId = zone,
        ).single { it.source == WeatherSource.NWS.id && it.date == day.toEpochDay() * 86_400_000L }

        assertNotNull(nws.precipAmountMm)
        assertEquals(p0012Mm + p0004Mm, nws.precipAmountMm!!, 0.001f) // the old code: 3x = 12.19 mm
        assertEquals(p0012Mm, nws.precipDayMm!!, 0.001f)
        assertEquals(p0004Mm, nws.precipNightMm!!, 0.001f)
    }
}

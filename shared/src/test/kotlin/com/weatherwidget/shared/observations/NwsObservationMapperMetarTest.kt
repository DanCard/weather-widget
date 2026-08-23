package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NwsObservationMapperMetarTest {

    private val station = NwsApi.StationInfo(
        id = "KSJC",
        name = "San Jose International Airport",
        lat = 37.36,
        lon = -121.93,
        type = NwsApi.StationType.OFFICIAL,
    )

    @Test
    fun `toReading enriches temperature with T-group tenths precision`() {
        val rawMetar = "METAR KSJC 231653Z 00000KT 10SM SCT080 20/14 A2996 RMK AO2 SLP144 T02040144"
        val obs = NwsApi.Observation(
            timestamp = "2026-08-23T16:53:00+00:00",
            temperatureCelsius = 20.0f, // Integer-rounded from API
            textDescription = "Partly Cloudy",
            stationName = "San Jose",
            isMetar = true,
            rawMessage = rawMetar,
        )

        val reading = NwsObservationMapper.toReading(obs, station, 37.36, -121.93)

        // 20.4°C -> (20.4 * 1.8) + 32 = 68.72°F
        assertEquals(68.72f, reading.temperature, 0.01f)
        assertEquals(rawMetar, reading.rawMetar)
        assertTrue(reading.isMetar)
    }

    @Test
    fun `toReading enriches 24h extremes and precip from remarks when absent in json`() {
        val rawMetar = "METAR KSJC 231653Z 00000KT 10SM CLR 20/14 A2996 RMK AO2 T02000144 402560122 P0005"
        val obs = NwsApi.Observation(
            timestamp = "2026-08-23T16:53:00+00:00",
            temperatureCelsius = 20.0f,
            textDescription = "Clear",
            stationName = "San Jose",
            maxTempLast24hCelsius = null,
            minTempLast24hCelsius = null,
            precipLastHourMm = null,
            isMetar = true,
            rawMessage = rawMetar,
        )

        val reading = NwsObservationMapper.toReading(obs, station, 37.36, -121.93)

        // 25.6°C -> (25.6 * 1.8) + 32 = 78.08°F
        assertEquals(78.08f, reading.maxTempLast24h!!, 0.01f)
        // 12.2°C -> (12.2 * 1.8) + 32 = 53.96°F
        assertEquals(53.96f, reading.minTempLast24h!!, 0.01f)
        // P0005 -> 0.05 in = 1.27 mm
        assertEquals(1.27f, reading.precipAmountMm!!, 0.01f)
        assertEquals(rawMetar, reading.rawMetar)
    }

    @Test
    fun `toReading falls back to json temperature when T-group is absent`() {
        val rawMetar = "KNUQ 231655Z AUTO 34005KT 10SM CLR 19/15 A2996 RMK AO2"
        val obs = NwsApi.Observation(
            timestamp = "2026-08-23T16:55:00+00:00",
            temperatureCelsius = 19.0f,
            textDescription = "Clear",
            stationName = "Moffett",
            isMetar = true,
            rawMessage = rawMetar,
        )

        val reading = NwsObservationMapper.toReading(obs, station, 37.36, -121.93)

        // 19.0°C -> (19.0 * 1.8) + 32 = 66.2°F
        assertEquals(66.2f, reading.temperature, 0.01f)
        assertEquals(rawMetar, reading.rawMetar)
    }
}

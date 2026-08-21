package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The shared NWS observation mapping both platforms store through — Android
 * (`NwsObservationSource.toEntity`) and desktop (`DesktopWeatherService.toReading`).
 */
@Category(ShortDuration::class)
class NwsObservationMapperTest {

    private val station = NwsApi.StationInfo(
        id = "KPAO",
        name = "Palo Alto Airport",
        lat = 37.46,
        lon = -122.11,
        type = NwsApi.StationType.OFFICIAL,
    )

    private fun observation(
        celsius: Float = 10f,
        cloudLayers: List<NwsApi.CloudLayer> = emptyList(),
        timestamp: String = "2026-08-20T22:05:00+00:00",
        stationName: String = "",
    ) = NwsApi.Observation(
        timestamp = timestamp,
        temperatureCelsius = celsius,
        textDescription = "Clear",
        stationName = stationName,
        cloudLayers = cloudLayers,
    )

    @Test
    fun `celsius converts to fahrenheit and the station name falls back to the station record`() {
        val reading = NwsObservationMapper.toReading(
            observation(celsius = 10f, stationName = ""), station, 37.4, -122.08,
        )

        assertEquals(50f, reading.temperature)
        assertEquals("Palo Alto Airport", reading.stationName)
        assertEquals("KPAO", reading.stationId)
        assertEquals("OFFICIAL", reading.stationType)
    }

    @Test
    fun `metar sky condition files as the low layer with a null total`() {
        val layers = listOf(NwsApi.CloudLayer(amount = "SCT", baseMeters = 300.0))
        val reading = NwsObservationMapper.toReading(observation(cloudLayers = layers), station, 37.4, -122.08)

        assertNull(reading.cloudCover)
        assertEquals(MetarSkyCover.lowPercent(layers), reading.cloudCoverLow)
    }

    @Test
    fun `timestamp offset without a colon still parses`() {
        val reading = NwsObservationMapper.toReading(
            observation(timestamp = "2026-08-20T15:05:00-0700"), station, 37.4, -122.08,
        )
        val expected = java.time.ZonedDateTime.parse("2026-08-20T15:05:00-07:00")
            .toInstant().toEpochMilli()
        assertEquals(expected, reading.timestamp)
    }

    @Test
    fun `a malformed timestamp falls back to now instead of throwing`() {
        val before = System.currentTimeMillis()
        val reading = NwsObservationMapper.toReading(
            observation(timestamp = "not-a-timestamp"), station, 37.4, -122.08,
        )
        assertTrue(reading.timestamp >= before)
    }

    @Test
    fun `distance is the great-circle km between site and station`() {
        val reading = NwsObservationMapper.toReading(observation(), station, 37.4, -122.08)

        // ~0.06 deg lat + ~0.03 deg lon apart: a few kilometres, never zero, never huge.
        assertTrue(reading.distanceKm in 5f..9f)
    }
}

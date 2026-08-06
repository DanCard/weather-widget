package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class TodayColumnOverlayContentResolverTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val lat = 37.42
    private val lon = -122.08

    @Test
    fun `content uses dominant raw station temperature and Blend age without station text`() {
        val now = ms("2026-08-04T08:20:00")
        val observations =
            listOf(
                observation("DOM", "2026-08-03T08:20:00", 60f, 0.2f),
                observation("FAR", "2026-08-03T08:20:00", 70f, 20f),
                observation("DOM", "2026-08-04T08:20:00", 62.6f, 0.2f),
                observation("FAR", "2026-08-04T08:20:00", 70f, 20f),
            )

        val content = TodayColumnOverlayContentResolver.resolveLatest(
            observations = observations,
            hourlyForecasts = emptyList(),
            displaySourceId = WeatherSource.NWS.id,
            userLat = lat,
            userLon = lon,
            nowMs = now,
            personalStationWeight = 1.0,
            useCelsius = false,
            zoneId = zone,
        )

        assertNotNull(content)
        content!!
        assertEquals("62.6°", content.dominantTempText)
        assertEquals("0m", content.dominantAgeText)
        assertEquals("DOM", content.dominantContribution?.contribution?.stationId)
        assertEquals("yest", content.deltaCaptionText)
        assertTrue(content.deltaValueText?.startsWith("+") == true)
        assertTrue(listOfNotNull(content.deltaValueText, content.dominantTempText, content.dominantAgeText).none { "DOM" in it })
    }

    private fun ms(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun observation(station: String, local: String, temp: Float, distanceKm: Float) =
        ObservationReading(
            stationId = station,
            stationName = station,
            timestamp = ms(local),
            temperature = temp,
            condition = "observed",
            locationLat = lat,
            locationLon = lon,
            distanceKm = distanceKm,
            stationType = "OFFICIAL",
            api = WeatherSource.NWS.id,
            fetchedAt = ms(local),
        )
}

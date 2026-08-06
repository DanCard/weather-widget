package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            forecastDelta = 0.4f,
            zoneId = zone,
        )

        assertNotNull(content)
        content!!
        assertEquals("62.6°", content.dominantTempText)
        assertEquals("0m", content.dominantAgeText)
        assertEquals("DOM", content.dominantContribution?.contribution?.stationId)
        assertEquals("fcst", content.deltaCaptionText)
        assertEquals("+0.4", content.deltaValueText)
        assertTrue(listOfNotNull(content.deltaValueText, content.dominantTempText, content.dominantAgeText).none { "DOM" in it })
    }

    @Test
    fun `delta flag off suppresses only the delta text`() {
        val content = resolve(showForecastDelta = false)

        assertNotNull(content)
        content!!
        assertNull(content.deltaValueText)
        assertNull(content.deltaCaptionText)
        assertEquals("62.6°", content.dominantTempText)
        assertEquals("0m", content.dominantAgeText)
    }

    @Test
    fun `dominant temp flag off still shows reading age alone`() {
        val content = resolve(showDominantStationTemp = false)

        assertNotNull(content)
        content!!
        assertEquals("+0.4", content.deltaValueText)
        assertNull(content.dominantTempText)
        assertEquals("0m", content.dominantAgeText)
    }

    @Test
    fun `dominant age flag off still shows station temperature alone`() {
        val content = resolve(showDominantReadingAge = false)

        assertNotNull(content)
        content!!
        assertEquals("+0.4", content.deltaValueText)
        assertEquals("62.6°", content.dominantTempText)
        assertNull(content.dominantAgeText)
    }

    @Test
    fun `all flags off returns null content`() {
        val content =
            resolve(
                showForecastDelta = false,
                showDominantStationTemp = false,
                showDominantReadingAge = false,
            )

        assertNull(content)
    }

    private fun resolve(
        showForecastDelta: Boolean = true,
        showDominantStationTemp: Boolean = true,
        showDominantReadingAge: Boolean = true,
    ): TodayColumnOverlayContent? {
        val now = ms("2026-08-04T08:20:00")
        val observations =
            listOf(
                observation("DOM", "2026-08-03T08:20:00", 60f, 0.2f),
                observation("FAR", "2026-08-03T08:20:00", 70f, 20f),
                observation("DOM", "2026-08-04T08:20:00", 62.6f, 0.2f),
                observation("FAR", "2026-08-04T08:20:00", 70f, 20f),
            )
        return TodayColumnOverlayContentResolver.resolveLatest(
            observations = observations,
            hourlyForecasts = emptyList(),
            displaySourceId = WeatherSource.NWS.id,
            userLat = lat,
            userLon = lon,
            nowMs = now,
            personalStationWeight = 1.0,
            useCelsius = false,
            forecastDelta = 0.4f,
            showForecastDelta = showForecastDelta,
            showDominantStationTemp = showDominantStationTemp,
            showDominantReadingAge = showDominantReadingAge,
            zoneId = zone,
        )
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

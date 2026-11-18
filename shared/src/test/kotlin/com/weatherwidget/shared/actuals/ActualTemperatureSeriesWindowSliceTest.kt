package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Verifies that window-slicing inside [ActualTemperatureSeriesBuilder.blendObservationSeries]
 * produces bit-for-bit identical results when callers pass 30 days of observations vs only the
 * window-relevant slice (performance/261118-desktop-api-toggle-latency.md).
 */
@Category(ShortDuration::class)
class ActualTemperatureSeriesWindowSliceTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val lat = 37.4166
    private val lon = -122.0889

    @Test
    fun `blendObservationSeries with 30-day observations matches narrow slice bit-for-bit`() {
        val now = LocalDateTime.of(2026, 11, 18, 14, 0)
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val startMs = now.minusHours(12).atZone(zone).toInstant().toEpochMilli()
        val endMs = now.plusHours(3).atZone(zone).toInstant().toEpochMilli()

        // Generate 30 days of multi-station NWS observations + hourly forecasts.
        val observations = buildList {
            for (hoursAgo in 720 downTo 0) {
                val ts = nowMs - hoursAgo * 3_600_000L
                val baseTemp = 55f + (hoursAgo % 12) * 0.7f
                add(
                    ObservationReading(
                        stationId = "KNUQ",
                        stationName = "Moffett",
                        timestamp = ts,
                        temperature = baseTemp,
                        condition = "Clear",
                        distanceKm = 3.2f,
                        stationType = StationType.OFFICIAL,
                        locationLat = lat,
                        locationLon = lon,
                        api = "NWS",
                    ),
                )
                if (hoursAgo % 2 == 0) {
                    add(
                        ObservationReading(
                            stationId = "KPAO",
                            stationName = "Palo Alto",
                            timestamp = ts + 15 * 60_000L,
                            temperature = baseTemp + 1.1f,
                            condition = "Clear",
                            distanceKm = 5.4f,
                            stationType = StationType.OFFICIAL,
                            locationLat = lat,
                            locationLon = lon,
                            api = "NWS",
                        ),
                    )
                }
            }
        }
        val hourly = (-720..24).map { offset ->
            val ts = nowMs + offset * 3_600_000L
            HourlyForecast(
                dateTime = ts,
                temperature = 56f + (offset % 6) * 0.5f,
                condition = "Clear",
                source = "NWS",
                locationLat = lat,
                locationLon = lon,
            )
        }

        val wideResult = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = observations,
            hourlyForecasts = hourly,
            displaySourceId = "NWS",
            userLat = lat,
            userLon = lon,
            startMs = startMs,
            endMs = endMs,
            zoneId = zone,
            captureBreakdowns = 2,
            captureLatestDominantAtOrBeforeMs = nowMs,
        )
        val narrowObs = observations.filter { it.timestamp >= startMs - 24 * 3_600_000L && it.timestamp <= endMs + 24 * 3_600_000L }
        val narrowResult = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = narrowObs,
            hourlyForecasts = hourly,
            displaySourceId = "NWS",
            userLat = lat,
            userLon = lon,
            startMs = startMs,
            endMs = endMs,
            zoneId = zone,
            captureBreakdowns = 2,
            captureLatestDominantAtOrBeforeMs = nowMs,
        )

        assertEquals(narrowResult.observations, wideResult.observations)
        assertEquals(narrowResult.latestDominantContribution, wideResult.latestDominantContribution)
        assertEquals(narrowResult.breakdowns, wideResult.breakdowns)
        assertNotNull(wideResult.latestDominantContribution)
        println(
            "blendSliceCheck wideObs=${observations.size} narrowObs=${narrowObs.size} " +
                "emitted=${wideResult.observations.size} dominant=${wideResult.latestDominantContribution?.contribution?.stationId}",
        )

        // TodayColumnOverlayContentResolver.resolveLatest also matches bit-for-bit.
        val wideOverlay = TodayColumnOverlayContentResolver.resolveLatest(
            observations = observations,
            hourlyForecasts = hourly,
            displaySourceId = "NWS",
            userLat = lat,
            userLon = lon,
            nowMs = nowMs,
            personalStationWeight = 1.0,
            useCelsius = false,
            forecastDelta = -1.5f,
            zoneId = zone,
        )
        val narrowOverlay = TodayColumnOverlayContentResolver.resolveLatest(
            observations = narrowObs,
            hourlyForecasts = hourly,
            displaySourceId = "NWS",
            userLat = lat,
            userLon = lon,
            nowMs = nowMs,
            personalStationWeight = 1.0,
            useCelsius = false,
            forecastDelta = -1.5f,
            zoneId = zone,
        )
        assertEquals(narrowOverlay, wideOverlay)
        assertNotNull(wideOverlay)
    }
}

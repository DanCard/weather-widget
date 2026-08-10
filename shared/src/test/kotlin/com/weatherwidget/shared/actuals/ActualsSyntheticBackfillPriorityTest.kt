package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Guards the synthetic-backfill priority rule in
 * [ActualTemperatureSeriesBuilder.blendCandidateTemperature]: a `<SOURCE>_MAIN` historical-actuals
 * row is a slice of the source's hourly FORECAST re-filed as an observation at `distanceKm = 0`, so
 * it must never outrank a real station reading — but must still be usable when it is all there is.
 *
 * Reproduces the 2026-08-02 desktop field bug. At 15:26 an NWS fetch failed
 * (`SOURCE_FALLBACK: NWS unavailable, substituting Open-Meteo: Channel was closed`) and the fallback
 * minted `NWS_MAIN` rows carrying Open-Meteo forecast temperatures at `distanceKm = 0`. Those rows
 * won the near-zero-distance override outright, suppressing all five real stations, so the widget's
 * current temperature tracked the (≈8 °F hot) forecast for the next three hours — then snapped down
 * 4.6 °F the instant the backfill aged past `BLEND_MAX_AGE_MS` and dropped out of the blend.
 */
@Category(ShortDuration::class)
class ActualsSyntheticBackfillPriorityTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    /** The real stations reporting on 2026-08-02, with their field distances and readings. */
    private fun realStations(): List<ObservationReading> = listOf(
        observation("AW020", "2026-08-02T18:00:00", 79.0f, distanceKm = 2.22f, stationType = "PERSONAL"),
        observation("KNUQ", "2026-08-02T18:00:00", 77.0f, distanceKm = 3.82f),
        observation("KPAO", "2026-08-02T18:00:00", 73.4f, distanceKm = 6.06f),
    )

    /** The NWS->Open-Meteo fallback backfill: forecast temperatures filed at distance zero. */
    private fun nwsBackfill(): List<ObservationReading> = listOf(
        observation("NWS_MAIN", "2026-08-02T18:00:00", 83.2f, distanceKm = 0f),
    )

    @Test
    fun `NWS_MAIN backfill cannot outrank real stations at the same timestamp`() {
        val result = blend(realStations() + nwsBackfill(), WeatherSource.NWS.id)

        val point = result.observations.single { it.timestamp == epoch("2026-08-02T18:00:00") }
        assertTrue(
            "blend ${point.temperature} must come from the real stations (73.4..79.0), " +
                "not the 83.2 forecast backfill",
            point.temperature < 79.5f,
        )
        assertTrue("blend ${point.temperature} should stay within the real stations' range", point.temperature > 73f)
        assertEquals("the deprioritisation must be reported", 1, result.stats.syntheticDeprioritizedCount)
    }

    @Test
    fun `stale backfill cannot drive the blend while real stations keep reporting`() {
        // The field shape: the backfill's newest hour is 15:00 and it is carried forward by
        // extrapolation, while real stations report fresh at 18:00. Before the fix the 15:00 row
        // still won every candidate timestamp it reached.
        val staleBackfill = listOf(
            observation("NWS_MAIN", "2026-08-02T15:00:00", 83.2f, distanceKm = 0f),
            observation("NWS_MAIN", "2026-08-02T16:00:00", 84.0f, distanceKm = 0f),
        )
        val result = blend(realStations() + staleBackfill, WeatherSource.NWS.id)

        val point = result.observations.single { it.timestamp == epoch("2026-08-02T18:00:00") }
        assertTrue(
            "18:00 blend ${point.temperature} must follow the real stations, not the 3h-old backfill",
            point.temperature < 79.5f,
        )
    }

    @Test
    fun `forecast-only source still uses its backfill when it is the only candidate`() {
        // Open-Meteo has no stations of its own; OPEN_METEO_MAIN is all there is and must be used.
        val obs = listOf(
            observation(
                "OPEN_METEO_MAIN",
                "2026-08-02T18:00:00",
                83.2f,
                api = WeatherSource.OPEN_METEO.id,
                distanceKm = 0f,
            ),
        )
        val result = blend(obs, WeatherSource.OPEN_METEO.id, forecastSource = WeatherSource.OPEN_METEO.id)

        val point = result.observations.single { it.timestamp == epoch("2026-08-02T18:00:00") }
        assertEquals(83.2f, point.temperature, 0.001f)
        assertEquals("nothing to deprioritise against", 0, result.stats.syntheticDeprioritizedCount)
    }

    @Test
    fun `NWS falls back to its backfill during a total station outage`() {
        // No real station rows at all — the actual line must still render rather than going empty.
        val result = blend(nwsBackfill(), WeatherSource.NWS.id)

        val point = result.observations.single { it.timestamp == epoch("2026-08-02T18:00:00") }
        assertEquals(83.2f, point.temperature, 0.001f)
        assertEquals(0, result.stats.syntheticDeprioritizedCount)
    }

    // ---- isSynthetic on the captured dominant contribution ----
    //
    // The flag is what lets a surface that NAMES the station (the hourly graph's dominant-station
    // label) refuse to name one that is not a thermometer. Nothing else on the row reveals it: the
    // backfill carries stationType = OFFICIAL and resolves as "observed".

    @Test
    fun `dominant contribution is flagged synthetic for a forecast-only source`() {
        // The everyday case, not an edge case: Open-Meteo has no stations, so OPEN_METEO_MAIN is the
        // only candidate and is therefore ALWAYS the dominant one.
        val at = epoch("2026-08-02T18:00:00")
        val obs = listOf(
            observation(
                "OPEN_METEO_MAIN",
                "2026-08-02T18:00:00",
                83.2f,
                api = WeatherSource.OPEN_METEO.id,
                distanceKm = 0f,
            ),
        )
        val dominant =
            blend(
                obs,
                WeatherSource.OPEN_METEO.id,
                forecastSource = WeatherSource.OPEN_METEO.id,
                captureLatestDominantAtOrBeforeMs = at,
            ).latestDominantContribution!!

        assertEquals("OPEN_METEO_MAIN", dominant.contribution.stationId)
        assertTrue("the backfill row must be flagged synthetic", dominant.contribution.isSynthetic)
        // The fields that would otherwise pass it off as a station reading, pinned so the flag stays
        // the only way to tell.
        assertEquals("OFFICIAL", dominant.contribution.stationType)
        assertEquals("observed", dominant.contribution.sourceKind)
    }

    @Test
    fun `dominant contribution is not flagged synthetic when a real station wins`() {
        val at = epoch("2026-08-02T18:00:00")
        val dominant =
            blend(
                realStations() + nwsBackfill(),
                WeatherSource.NWS.id,
                captureLatestDominantAtOrBeforeMs = at,
            ).latestDominantContribution!!

        assertTrue(
            "a real station must win over the deprioritised backfill, got ${dominant.contribution.stationId}",
            dominant.contribution.stationId != "NWS_MAIN",
        )
        assertEquals(false, dominant.contribution.isSynthetic)
    }

    private fun blend(
        observations: List<ObservationReading>,
        displaySourceId: String,
        forecastSource: String = WeatherSource.NWS.id,
        captureLatestDominantAtOrBeforeMs: Long? = null,
    ) = ActualTemperatureSeriesBuilder.blendObservationSeries(
        observations = observations,
        hourlyForecasts = forecasts("2026-08-02T00:00:00", 24, forecastSource),
        displaySourceId = displaySourceId,
        userLat = LAT,
        userLon = LON,
        startMs = epoch("2026-08-02T00:00:00"),
        endMs = epoch("2026-08-03T00:00:00"),
        zoneId = zone,
        captureLatestDominantAtOrBeforeMs = captureLatestDominantAtOrBeforeMs,
    )

    private fun forecasts(start: String, count: Int, source: String): List<HourlyForecast> {
        val startTime = LocalDateTime.parse(start)
        return (0..count).map { index ->
            val time = startTime.plusHours(index.toLong())
            HourlyForecast(
                dateTime = time.atZone(zone).toInstant().toEpochMilli(),
                // Deliberately flat: extrapolation then carries a backfill row forward unchanged, so
                // any influence it has on the result is visible as its own raw value.
                temperature = 85f,
                condition = "Clear",
                source = source,
            )
        }
    }

    private fun observation(
        stationId: String,
        time: String,
        temperature: Float,
        api: String = WeatherSource.NWS.id,
        distanceKm: Float,
        stationType: String = "OFFICIAL",
    ): ObservationReading =
        ObservationReading(
            stationId = stationId,
            stationName = stationId,
            timestamp = epoch(time),
            temperature = temperature,
            condition = "observed",
            locationLat = LAT,
            locationLon = LON,
            distanceKm = distanceKm,
            api = api,
            stationType = stationType,
        )

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
    }
}

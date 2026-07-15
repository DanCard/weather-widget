package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Guards the observed series against LATE-READING dependence: a station's contribution to a PAST
 * timestamp must not change when that station's *next* reading finally arrives.
 *
 * Field case (2026-07-15, Samsung): the hourly graph alternated between two pixel-identical renders
 * minutes apart with no user action, high/low labels blinking in and out. KPAO's last reading was
 * 20:47, and the two renders' blended values diverged over exactly 20:50..23:45 — KPAO's 3h
 * forward-extrapolation reach (20:47 + MAX_EXTRAPOLATION_GAP_MS = 23:47) — by up to 1.2°F.
 *
 * Cause: [ActualTemperatureSeriesBuilder.resolveStationValueAt] picked its branch on whether an
 * `after` reading existed at all. With none, it extrapolated from `before` (gap measured
 * before->target: 63 min, passes). Once a later reading landed, the same target routed to the
 * interpolation branch, whose gap is measured before->after (20:47 -> 03:30 = 6h43m, fails) and
 * returned null — silently dropping the station from hours it had already been covering. IDW then
 * renormalised over the survivors, shifting the whole observed curve, tipping flat ties into extrema
 * and making the labels blink.
 *
 * The invariant pinned here: past blended values depend only on readings NEAR the target, never on
 * whether some distant future reading exists.
 */
class ActualsLateReadingIndependenceTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    // Dominant official station with dense coverage straight through the window, so the lone-station
    // guard never fires and NEAR is always the day's dominant station.
    private fun nearObs(): List<ObservationReading> =
        (0..40).map { i ->
            val t = LocalDateTime.parse("2026-07-14T18:00:00").plusMinutes(15L * i)
            observation("NEAR", t, 70f, distanceKm = 2.0f)
        }

    // Mirrors KPAO: coverage that stops at 20:47, warmer than NEAR so its presence in the blend is
    // detectable. Its 3h extrapolation reach covers 20:47..23:47.
    private fun stopsAt2047(): List<ObservationReading> =
        listOf(
            observation("LATE", LocalDateTime.parse("2026-07-14T20:17:00"), 76f, distanceKm = 3.0f),
            observation("LATE", LocalDateTime.parse("2026-07-14T20:47:00"), 76f, distanceKm = 3.0f),
        )

    // The straggler that finally reports 6h43m later — far beyond MAX_INTERPOLATION_GAP_MS from 20:47.
    private val lateArrival =
        observation("LATE", LocalDateTime.parse("2026-07-15T03:30:00"), 64f, distanceKm = 3.0f)

    private fun forecasts(): List<HourlyForecast> {
        // A falling overnight forecast, so forecast-delta extrapolation actually moves the value and a
        // broken extrapolation path cannot coincidentally match the interpolated one.
        val start = LocalDateTime.parse("2026-07-14T12:00:00")
        return (0..36).map { i ->
            HourlyForecast(
                dateTime = start.plusHours(i.toLong()).atZone(zone).toInstant().toEpochMilli(),
                temperature = 80f - i * 0.5f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
            )
        }
    }

    @Test
    fun `a station's later reading does not change already-blended past values`() {
        val forecasts = forecasts()
        val windowStart = epoch("2026-07-14T21:00:00")
        val windowEnd = epoch("2026-07-15T01:00:00")

        // Before LATE's straggler arrives: it extrapolates forward from 20:47 and contributes.
        val beforeArrival = blend(nearObs() + stopsAt2047(), forecasts, windowStart, windowEnd)
        // After it arrives: the SAME past hours must resolve identically.
        val afterArrival = blend(nearObs() + stopsAt2047() + lateArrival, forecasts, windowStart, windowEnd)

        assertTrue("blend must emit points in the window", beforeArrival.isNotEmpty())

        val common = beforeArrival.keys.intersect(afterArrival.keys)
        assertTrue("the two blends must cover the same past timestamps", common.isNotEmpty())

        val drifted = common.filter { abs(beforeArrival.getValue(it) - afterArrival.getValue(it)) > 0.05f }
        assertTrue(
            "a later reading must not shift past blended values, but ${drifted.size} of ${common.size} moved: " +
                drifted.take(5).joinToString { ts ->
                    val t = java.time.Instant.ofEpochMilli(ts).atZone(zone).toLocalTime()
                    "$t ${beforeArrival.getValue(ts)} -> ${afterArrival.getValue(ts)}"
                },
            drifted.isEmpty(),
        )
    }

    @Test
    fun `scenario is sensitive to LATE - dropping it entirely does move the past values`() {
        // Proves the test above is not vacuous: LATE genuinely influences the blend over its reach, so
        // "identical" in that test means the station was retained, not that it never mattered.
        val forecasts = forecasts()
        val windowStart = epoch("2026-07-14T21:00:00")
        val windowEnd = epoch("2026-07-15T01:00:00")

        val withLate = blend(nearObs() + stopsAt2047(), forecasts, windowStart, windowEnd)
        val withoutLate = blend(nearObs(), forecasts, windowStart, windowEnd)

        val common = withLate.keys.intersect(withoutLate.keys)
        val moved = common.filter { abs(withLate.getValue(it) - withoutLate.getValue(it)) > 0.05f }
        assertTrue(
            "LATE must measurably affect the blend inside its 3h reach, else the invariant test proves nothing",
            moved.isNotEmpty(),
        )
    }

    @Test
    fun `past values within reach still reflect the extrapolated station after the straggler lands`() {
        // The concrete field symptom: 21:00 sat inside KPAO's reach and must keep the warmer blended
        // value once the straggler arrives, rather than snapping to the NEAR-only value.
        val forecasts = forecasts()
        val windowStart = epoch("2026-07-14T21:00:00")
        val windowEnd = epoch("2026-07-15T01:00:00")

        val afterArrival = blend(nearObs() + stopsAt2047() + lateArrival, forecasts, windowStart, windowEnd)
        val nearOnly = blend(nearObs(), forecasts, windowStart, windowEnd)

        val at2100 = afterArrival.keys.filter { it in windowStart..epoch("2026-07-14T23:00:00") }
        assertTrue("expected blended points inside LATE's reach", at2100.isNotEmpty())

        val stillWarmer = at2100.any { ts ->
            val n = nearOnly[ts] ?: return@any false
            afterArrival.getValue(ts) > n + 0.05f
        }
        assertTrue(
            "inside LATE's 3h reach the blend must still include it after its straggler lands " +
                "(regression: it silently dropped to the NEAR-only value)",
            stillWarmer,
        )
    }

    private fun blend(
        obs: List<ObservationReading>,
        forecasts: List<HourlyForecast>,
        startMs: Long,
        endMs: Long,
    ): Map<Long, Float> =
        ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = obs,
            hourlyForecasts = forecasts,
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            startMs = startMs,
            endMs = endMs,
            zoneId = zone,
        ).observations.associate { it.timestamp to it.temperature }

    private fun observation(
        stationId: String,
        time: LocalDateTime,
        temperature: Float,
        distanceKm: Float,
    ): ObservationReading =
        ObservationReading(
            stationId = stationId,
            stationName = stationId,
            timestamp = time.atZone(zone).toInstant().toEpochMilli(),
            temperature = temperature,
            condition = "observed",
            locationLat = LAT,
            locationLon = LON,
            distanceKm = distanceKm,
            api = WeatherSource.NWS.id,
            stationType = "OFFICIAL",
        )

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
    }
}

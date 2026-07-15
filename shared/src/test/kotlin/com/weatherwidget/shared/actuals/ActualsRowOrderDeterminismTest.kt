package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Pins the blend against ROW-ORDER dependence: the same observations in a different order must produce
 * the same observed series.
 *
 * Field case (2026-07-15, Samsung): a parked hourly graph alternated between two pixel-identical
 * renders, high/low labels blinking, on a window whose hours had no new data. The inputs were provably
 * identical across the flip — rows=1660, stations=6, blendedPoints=1041 — yet the drawn curve changed
 * (visibleHash 1657342193 -> 1943784045). The tell was the station list reordering between renders:
 * `[KSJC,AW020,...]` then `[AW020,KSJC,...]`.
 *
 * Cause: `ObservationDao.getObservationsInRange` ordered only `BY timestamp`, which is not a total
 * order — several stations report on identical timestamps, and SQLite may return tied rows in any
 * order. `blendObservationSeries` then re-sorted with `sortedBy { it.timestamp }` (a STABLE sort, so
 * ties kept the caller's order), and the order leaked into `groupBy { stationId }` -> byStation
 * iteration order -> `dominantStationByDay`'s maxWith tie-break (gating the lone-station skip) and
 * `anchorStation` ("first station that resolves").
 *
 * Fixed by sorting with the (timestamp, stationId) primary key in the builder, so every caller is
 * deterministic regardless of its query. The DAO ordering was made total too, as defence in depth.
 */
class ActualsRowOrderDeterminismTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    // Several stations reporting on IDENTICAL timestamps is what makes `ORDER BY timestamp` ambiguous,
    // so every station here shares the same 15-minute grid. Distances are close enough that no single
    // station dominates the IDW blend outright.
    private fun observations(): List<ObservationReading> {
        val start = LocalDateTime.parse("2026-07-14T18:00:00")
        val stations = listOf(
            Triple("AW020", 2.0f, 70f),
            Triple("KSJC", 2.1f, 72f),
            Triple("KNUQ", 3.4f, 68f),
            Triple("LOAC1", 4.0f, 74f),
        )
        return (0..40).flatMap { i ->
            val t = start.plusMinutes(15L * i)
            stations.map { (id, dist, base) ->
                observation(id, t, base + (i % 5) * 0.4f, distanceKm = dist)
            }
        }
    }

    private fun forecasts(): List<HourlyForecast> {
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
    fun `blended series is identical regardless of input row order`() {
        val obs = observations()
        val forecasts = forecasts()
        val startMs = epoch("2026-07-14T21:00:00")
        val endMs = epoch("2026-07-15T01:00:00")

        val canonical = blend(obs, forecasts, startMs, endMs)
        assertTrue("scenario must emit blended points", canonical.isNotEmpty())

        // Every permutation the query plan could plausibly hand us must land on the same series.
        val rng = Random(20260715)
        repeat(12) { attempt ->
            val shuffled = blend(obs.shuffled(rng), forecasts, startMs, endMs)
            assertEquals(
                "shuffle #$attempt changed the emitted point count",
                canonical.size,
                shuffled.size,
            )
            canonical.forEach { (ts, temp) ->
                val other = shuffled[ts]
                assertEquals(
                    "shuffle #$attempt changed the blended value at ${
                        java.time.Instant.ofEpochMilli(ts).atZone(zone).toLocalTime()
                    }",
                    temp,
                    other ?: Float.NaN,
                    0.0001f,
                )
            }
        }
    }

    @Test
    fun `reversing input order does not change the series`() {
        // A targeted counterpart to the shuffle: reversal maximally inverts every tie group, which is
        // exactly what flipped dominantStationByDay's maxWith and anchorStation.
        val obs = observations()
        val forecasts = forecasts()
        val startMs = epoch("2026-07-14T21:00:00")
        val endMs = epoch("2026-07-15T01:00:00")

        val forward = blend(obs, forecasts, startMs, endMs)
        val reversed = blend(obs.reversed(), forecasts, startMs, endMs)

        assertEquals("reversal changed the emitted point count", forward.size, reversed.size)
        forward.forEach { (ts, temp) ->
            assertEquals(
                "reversal changed the blended value at ${
                    java.time.Instant.ofEpochMilli(ts).atZone(zone).toLocalTime()
                }",
                temp,
                reversed[ts] ?: Float.NaN,
                0.0001f,
            )
        }
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

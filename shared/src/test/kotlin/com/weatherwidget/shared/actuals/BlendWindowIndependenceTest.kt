package com.weatherwidget.shared.actuals

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the window behaviour of [ActualTemperatureSeriesBuilder.blendObservationSeries], which
 * documents itself as producing a series "independent of the query window". Several callers depend
 * on that: the fetch dot blends a 12h/3h context, the hourly graph blends 72h/60h, and the dot is
 * supposed to land exactly ON the graph's curve.
 *
 * The distinction these tests draw — and the reason the claim is easy to misread — is that
 * `startMs`/`endMs` only gate which computed points are EMITTED. They do not restrict the blend
 * input. What actually moves the numbers is which observation rows the CALLER queried, because a
 * station needs a reading at or before a target timestamp to resolve there at all.
 *
 * Fixture is real device data; see [DeviceBlendFixture].
 */
class BlendWindowIndependenceTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val lat = 37.417
    private val lon = -122.089
    private val pwsWeight = 0.05 // device: personal_station_discount = 95

    /** 2026-07-19 21:00 local — the aligned centre of the render this fixture was captured from. */
    private val alignedCenter: Long =
        LocalDateTime.of(2026, 7, 19, 21, 0).atZone(zone).toInstant().toEpochMilli()

    /** The candidate timestamp the fetch dot resolved to at that render (displayed 61.6°). */
    private val ts2050: Long =
        LocalDateTime.of(2026, 7, 19, 20, 50).atZone(zone).toInstant().toEpochMilli()

    private fun hours(n: Long) = n * 3_600_000L

    private fun blend(
        backHours: Long,
        forwardHours: Long,
        /** Mirror the caller's DB query: `false` hands the blender every row it could have seen. */
        restrictInputToWindow: Boolean = true,
    ): Map<Long, Float> {
        val startMs = alignedCenter - hours(backHours)
        val endMs = alignedCenter + hours(forwardHours)
        val obs = DeviceBlendFixture.observations
            .filter { !restrictInputToWindow || it.timestamp in startMs..endMs }
        val fcst = DeviceBlendFixture.hourlyForecasts
            .filter { !restrictInputToWindow || it.dateTime in startMs..endMs }
        return ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = obs,
            hourlyForecasts = fcst,
            displaySourceId = "NWS",
            userLat = lat,
            userLon = lon,
            startMs = startMs,
            endMs = endMs,
            personalStationWeight = pwsWeight,
            zoneId = zone,
            onBlendDebug = null,
        ).observations.associate { it.timestamp to it.temperature }
    }

    /**
     * The documented invariant, stated precisely: for a FIXED input set, narrowing the emission
     * window changes only which points come back, never their values.
     */
    @Test
    fun `emission window does not change any blended value`() {
        val wide = blend(72L, 60L, restrictInputToWindow = false)
        listOf(2L, 6L, 12L, 24L).forEach { back ->
            val narrow = blend(back, 3L, restrictInputToWindow = false)
            narrow.forEach { (ts, v) ->
                assertEquals(
                    "emission window ${back}h/3h changed the value at $ts",
                    wide.getValue(ts).toDouble(), v.toDouble(), 1e-6,
                )
            }
        }
    }

    /**
     * The fetch dot reads the LATEST point at or before now — always the trailing edge of its
     * context — so it agrees with the graph even though it queries only 12h of observations.
     * This is what keeps the dot sitting on the curve.
     */
    @Test
    fun `fetch dot value matches the graph curve at the same timestamp`() {
        val dot = blend(12L, 3L)
        val graph = blend(72L, 60L)
        assertEquals(
            "fetch dot and hourly graph disagree at the dot's own timestamp",
            graph.getValue(ts2050).toDouble(), dot.getValue(ts2050).toDouble(), 1e-6,
        )
        // Sanity-check against the value observed on the device (CURR_STALE_DEBUG temp=61.6).
        assertEquals(61.58, dot.getValue(ts2050).toDouble(), 0.02)
    }

    /**
     * Characterises the one real fragility: a caller that QUERIES a narrow observation range has no
     * readings before its range start, so stations cannot resolve there and the IDW renormalises
     * over whichever subset survives. Output within roughly the first hour of a narrow query is
     * therefore unreliable — measured up to ~5.5°F off the wide-context answer.
     *
     * No current caller is exposed (graph queries 72h; ActualsAggregator pads ±24h via
     * DAILY_BLEND_CONTEXT_MS; YesterdayDeltaCalculator is handed the full 72h list; the dot reads
     * only its trailing edge). This test exists so that stops being an accident.
     */
    @Test
    fun `narrow query is unreliable near its start but sound after one hour`() {
        val reference = blend(72L, 60L, restrictInputToWindow = false)
        val narrowStart = alignedCenter - hours(12L)
        val narrow = blend(12L, 3L)

        val (edge, interior) = narrow.keys.partition { it < narrowStart + hours(1L) }

        val worstEdge = edge.maxOfOrNull { abs(narrow.getValue(it) - reference.getValue(it)) } ?: 0f
        val worstInterior = interior.maxOfOrNull { abs(narrow.getValue(it) - reference.getValue(it)) } ?: 0f

        assertTrue(
            "expected the leading-edge artifact to be present (got ${"%.2f".format(worstEdge)}°F); " +
                "if this now passes cleanly the blender gained edge handling and this test should " +
                "become a strict equality check",
            worstEdge > 0.5f,
        )
        assertEquals(
            "beyond one hour past the query start a narrow query must match the wide context",
            0.0, worstInterior.toDouble(), 1e-6,
        )
    }
}

package com.weatherwidget.data.model

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import kotlin.math.abs

/**
 * Regression for the today-column `-13.7 fcst` delta
 * (plans/260806-today-column-stale-fragment-delta-opus.md).
 *
 * On a Samsung Fold that had not moved for days, GPS jitter had written EIGHT coordinate fragments
 * into `hourly_forecasts` for the same hour. Only one was still being refreshed; the other seven
 * were frozen long-range forecasts (Open-Meteo returns 14 days ahead, so a fetch on 2026-07-24 wrote
 * rows for 2026-08-06). `HourlyForecastLoader` collapsed them with
 * `associateBy { Pair(dateTime, source) }` — last-wins and `fetchedAt`-blind — and the DAO's
 * `ORDER BY dateTime ASC` breaks ties on `index_hourly_forecasts_locationLat_locationLon`, i.e.
 * ASCENDING LATITUDE. So the July-24 fragment at 37.419 deterministically overwrote the fresh row at
 * 37.417, and the current-temp delta became `65.3 - 79.0 = -13.7`.
 *
 * The fixture below is the real row set pulled off the device, in the real order SQLite returned it.
 *
 * **These are characterization tests, NOT the regression guard.** `HourlyForecastStitcher` was
 * already correct when the bug shipped — every test here except the `stitchBySource` pair would have
 * passed before the fix. The defect was *wiring*: `HourlyForecastLoader` never called the stitcher.
 * A test on a helper cannot detect a path that bypasses the helper, which is the recurring shape of
 * this whole bug family. The guard that actually fails pre-fix is
 * `HourlyLoaderStaleFragmentParityRoboTest` (drives the real loaders against a seeded DB), backed by
 * `architecture/HourlyCollapseChokepointTest` (fails the build on a new bypassing call site).
 * Keep these anyway: they pin the arithmetic in the exact terms the bug was reported in.
 */
@Category(ShortDuration::class)
class HourlyStaleFragmentCollapseTest {

    /** The raw configured centre the widget actually queries with — NOT pre-quantized. */
    private val centerLat = 37.41681671142578
    private val centerLon = -122.08899688720703

    private val hour1900 = 1_786_069_800_000L - 30 * 60 * 1000L // 2026-08-06 19:00 local
    private val hour2000 = hour1900 + 60 * 60 * 1000L

    private fun fetchedAt(iso: String): Long =
        java.time.LocalDateTime.parse(iso)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun row(lat: Double, lon: Double, time: Long, temp: Float, fetched: String) =
        HourlyForecast(
            dateTime = time,
            temperature = temp,
            condition = "Clear",
            source = "OPEN_METEO",
            fetchedAt = fetchedAt(fetched),
            locationLat = lat,
            locationLon = lon,
        )

    /**
     * All eight fragments for the 19:00 hour, in ASCENDING LATITUDE — the order the DAO returns them,
     * verified against the device DB. The fresh row (37.417) therefore arrives BEFORE the stale one
     * (37.419), which is precisely why a last-wins collapse picked the stale one.
     */
    private fun fragments1900(): List<HourlyForecast> = listOf(
        row(37.377, -122.075, hour1900, 80.6f, "2026-07-30T14:34:39"),
        row(37.417, -122.089, hour1900, 66.6f, "2026-08-06T19:23:07"), // <- the only live site
        row(37.419, -122.087, hour1900, 81.3f, "2026-07-24T20:03:02"), // <- the culprit
        row(37.420, -122.095, hour1900, 79.9f, "2026-07-29T12:53:09"),
        row(37.422, -122.087, hour1900, 81.4f, "2026-08-04T11:50:04"),
        row(37.422, -122.073, hour1900, 80.3f, "2026-08-03T12:45:16"),
        row(37.424, -122.088, hour1900, 81.5f, "2026-08-04T12:35:50"),
        row(37.481, -122.184, hour1900, 71.2f, "2026-07-27T18:22:21"),
    )

    private fun fragments2000(): List<HourlyForecast> = listOf(
        row(37.377, -122.075, hour2000, 76.8f, "2026-07-30T14:34:39"),
        row(37.417, -122.089, hour2000, 63.1f, "2026-08-06T19:23:07"),
        row(37.419, -122.087, hour2000, 76.7f, "2026-07-24T20:03:02"),
        row(37.420, -122.095, hour2000, 75.7f, "2026-07-29T12:53:09"),
        row(37.422, -122.087, hour2000, 76.6f, "2026-08-04T11:50:04"),
        row(37.422, -122.073, hour2000, 75.6f, "2026-08-03T12:45:16"),
        row(37.424, -122.088, hour2000, 76.7f, "2026-08-04T12:35:50"),
        row(37.481, -122.184, hour2000, 63.8f, "2026-07-27T18:22:21"),
    )

    private fun stitch(rows: List<HourlyForecast>) =
        HourlyForecastStitcher.stitch(
            current = rows,
            history = emptyList(),
            nowMs = hour1900,
            centerLat = centerLat,
            centerLon = centerLon,
        )

    // ---- Test 1: the freshest row wins -------------------------------------------------------

    @Test
    fun `collapse keeps the freshest fragment, not the one SQLite happens to return last`() {
        val result = stitch(fragments1900())

        assertEquals("expected exactly one row for the 19:00 hour", 1, result.size)
        val winner = result.single()
        // Diagnostic kept permanently: printing the winner's site AND fetch time is what made the
        // original failure legible — a bare temperature mismatch does not reveal that the losing row
        // was 13 days old.
        val diag = "winner=${winner.temperature} site=${winner.locationLat},${winner.locationLon} " +
            "fetchedAt=${java.time.Instant.ofEpochMilli(winner.fetchedAt)}"
        assertEquals("stale 2026-07-24 fragment won the collapse; $diag", 66.6f, winner.temperature, 0.001f)
        assertEquals("wrong site survived; $diag", 37.417, winner.locationLat!!, 1e-9)
    }

    // ---- Test 2: order independence (the oscillation invariant) --------------------------------

    @Test
    fun `collapse result does not depend on row order`() {
        val ascending = fragments1900()
        val descending = ascending.reversed()
        val shuffles = (1..25).map { seed -> ascending.shuffled(kotlin.random.Random(seed.toLong())) }

        val expected = stitch(ascending).single().temperature
        assertEquals("ascending-latitude order (what SQLite returns) must yield the fresh row", 66.6f, expected, 0.001f)

        val orders = listOf("descending" to descending) + shuffles.mapIndexed { i, s -> "shuffle$i" to s }
        orders.forEach { (label, rows) ->
            val got = stitch(rows).single().temperature
            assertEquals(
                "collapse is order-dependent ($label) — this is exactly why the widget alternated " +
                    "between -13.7 and +0.5 with no data change",
                expected,
                got,
                0.001f,
            )
        }
    }

    // ---- Test 3: the user-visible number -------------------------------------------------------

    @Test
    fun `forecast at the observation time is todays curve, giving a small delta not -13_7`() {
        // Observation: 65.3 degF at 19:30, exactly midway between the 19:00 and 20:00 forecast hours,
        // so the interpolated forecast is the mean of the two surviving rows.
        val observedTemp = 65.3f
        val at1900 = stitch(fragments1900()).single().temperature
        val at2000 = stitch(fragments2000()).single().temperature
        val forecastAtObs = (at1900 + at2000) / 2f
        val delta = observedTemp - forecastAtObs

        assertEquals("forecast at 19:30 should come from the 19:23 fetch", 64.85f, forecastAtObs, 0.01f)
        assertEquals("appliedDelta should be the small real bias", 0.45f, delta, 0.01f)
        assertTrue(
            "delta regressed to the stale-fragment value (forecastAtObs=$forecastAtObs delta=$delta); " +
                "the 2026-07-24 fragment interpolates to 79.0 and yields -13.7",
            abs(delta - (-13.7f)) > 1f,
        )
    }

    // ---- Test 4: multi-source collapse does not drop sources ------------------------------------

    @Test
    fun `stitchBySource keeps every source while still collapsing fragments within each`() {
        val meteo = fragments1900()
        val nws = meteo.map { it.copy(source = "NWS", temperature = it.temperature - 5f) }

        val result = HourlyForecastStitcher.stitchBySource(
            current = meteo + nws,
            history = emptyList(),
            nowMs = hour1900,
            centerLat = centerLat,
            centerLon = centerLon,
        )

        val bySource = result.associateBy { it.source }
        assertEquals("stitchBySource must not collapse across sources; got ${result.map { it.source }}", 2, result.size)
        assertEquals(66.6f, bySource["OPEN_METEO"]!!.temperature, 0.001f)
        assertEquals(61.6f, bySource["NWS"]!!.temperature, 0.001f)
    }

    @Test
    fun `single-source input still routes through the plain stitch path`() {
        val result = HourlyForecastStitcher.stitchBySource(
            current = fragments1900(),
            history = emptyList(),
            nowMs = hour1900,
            centerLat = centerLat,
            centerLon = centerLon,
        )
        assertEquals(1, result.size)
        assertEquals(66.6f, result.single().temperature, 0.001f)
    }

    // ---- Test 5: the sameSite boundary that started it all --------------------------------------

    /**
     * Documents the floating-point hair the old code turned on. Against the RAW centre the stale
     * fragment is 0.0021832886 away and excluded; against the QUANTIZED centre it is
     * 0.001999999999995339 away and admitted. `HourlyForecastLoader` re-centred on the quantized site
     * and so admitted it, while `GraphDataLoader` used the raw centre and did not — two loaders, one
     * database, two different answers.
     */
    @Test
    fun `sameSite disagrees between raw and quantized centre for the stale fragment`() {
        val staleLat = 37.419
        val staleLon = -122.087

        val againstRaw = LocationMatch.sameSite(centerLat, centerLon, staleLat, staleLon)
        val againstQuantized = LocationMatch.sameSite(
            LocationMatch.quantize(centerLat),
            LocationMatch.quantize(centerLon),
            staleLat,
            staleLon,
        )

        assertEquals(
            "raw centre delta is ${abs(staleLat - centerLat)} > ${LocationMatch.SAME_SITE_TOLERANCE_DEG}",
            false,
            againstRaw,
        )
        assertEquals(
            "quantized centre delta is ${abs(staleLat - LocationMatch.quantize(centerLat))} " +
                "<= ${LocationMatch.SAME_SITE_TOLERANCE_DEG} (floating point)",
            true,
            againstQuantized,
        )
    }

    /**
     * The property that actually matters: whichever way the boundary falls, the collapse must still
     * produce the fresh row. This is what makes the fix robust rather than merely re-tuning the
     * tolerance (which would only move the boundary somewhere else).
     */
    @Test
    fun `collapse yields the fresh row from either centre form`() {
        val fromRaw = stitch(fragments1900()).single()
        val fromQuantized = HourlyForecastStitcher.stitch(
            current = fragments1900(),
            history = emptyList(),
            nowMs = hour1900,
            centerLat = LocationMatch.quantize(centerLat),
            centerLon = LocationMatch.quantize(centerLon),
        ).single()

        assertNotNull(fromRaw)
        assertEquals(
            "collapse must not depend on whether the caller passes the raw or quantized centre",
            fromRaw.temperature,
            fromQuantized.temperature,
            0.001f,
        )
        assertEquals(66.6f, fromQuantized.temperature, 0.001f)
    }
}

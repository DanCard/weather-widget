package com.weatherwidget.data.local

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pins the distinction the 2026-09-03 `knuq 73.4° @ 5:55 pm` report needed and could not get from
 * the logs: when the observation pool is stale, was it the device-site merge that dropped the
 * fresher rows, or did the coarse box genuinely hold nothing newer?
 *
 * The coordinates and clock times below are that incident's real ones.
 */
@Category(ShortDuration::class)
class ObservationPoolDiagnosticsTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    private data class Row(
        val stationId: String,
        val timestamp: Long,
        val lat: Double,
        val lon: Double,
        val api: String = "NWS",
        val fetchedAt: Long = timestamp,
    )

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 9, 3, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val centreLat = 37.42356
    private val centreLon = -122.08657
    private val now = at(18, 38)

    private fun merge(rows: List<Row>): List<Row> =
        ObservationSiteMerge.merge(
            rows = rows,
            lat = centreLat,
            lon = centreLon,
            latOf = Row::lat,
            lonOf = Row::lon,
            stationOf = Row::stationId,
            timestampOf = Row::timestamp,
            apiOf = Row::api,
            fetchedAtOf = Row::fetchedAt,
        )

    private fun summarize(candidates: List<Row>) =
        ObservationPoolDiagnostics.summarize(
            candidates = candidates,
            merged = merge(candidates),
            latOf = Row::lat,
            lonOf = Row::lon,
            timestampOf = Row::timestamp,
        )

    /**
     * The incident itself. Every KNUQ fragment sat within 0.007° of the query centre — inside
     * [ObservationSiteMerge.MERGE_TOLERANCE_DEG] — so the merge kept the freshest row the box had.
     * The pool was still old, which exonerates the location plumbing and points at the fetch.
     */
    @Test
    fun staleButCompletePoolBlamesTheFetchNotTheMerge() {
        val candidates = listOf(
            Row("KNUQ", at(17, 55), 37.417, -122.087),
            Row("KNUQ", at(18, 15), 37.417, -122.089),
            Row("AW020", at(18, 10), 37.417, -122.087),
        )
        val summary = summarize(candidates)

        assertEquals(at(18, 15), summary.candidateNewestMs)
        assertEquals("merge kept the box's freshest row", at(18, 15), summary.mergedNewestMs)
        assertFalse(summary.mergeDroppedFresher)
        assertTrue("23 min behind is past the threshold", ObservationPoolDiagnostics.shouldLog(summary, now))

        val line = ObservationPoolDiagnostics.format(summary, now, at(15, 55), at(20, 0), centreLat, centreLon, zone)
        assertTrue(line, line.contains("verdict=box_had_nothing_newer"))
        assertTrue(line, line.contains("mergedNewestAgeMin=23"))
        assertTrue(line, line.contains("fresherSites=none"))
    }

    /**
     * The other branch: a fragment inside the coarse ±0.1° box but outside the merge tolerance
     * (0.033° away here) holds the newest rows, so the merge is what made the pool old. The site is
     * named with the coordinates a follow-up query needs.
     */
    @Test
    fun fresherRowsBehindTheMergeBoxAreNamed() {
        val candidates = listOf(
            Row("KNUQ", at(17, 55), 37.417, -122.087),
            Row("KNUQ", at(18, 35), 37.457, -122.087),
        )
        val summary = summarize(candidates)

        assertEquals(at(18, 35), summary.candidateNewestMs)
        assertEquals("the far fragment is not merged in", at(17, 55), summary.mergedNewestMs)
        assertTrue(summary.mergeDroppedFresher)
        assertTrue(ObservationPoolDiagnostics.shouldLog(summary, now))

        val line = ObservationPoolDiagnostics.format(summary, now, at(15, 55), at(20, 0), centreLat, centreLon, zone)
        assertTrue(line, line.contains("verdict=merge_dropped_fresher"))
        assertTrue(line, line.contains("37.457,-122.087@09-03 18:35(1)"))
    }

    /** The normal state, which must stay out of `app_logs` — this runs on every render. */
    @Test
    fun freshCompletePoolIsNotLogged() {
        val candidates = listOf(
            Row("KNUQ", at(18, 35), 37.417, -122.087),
            Row("AW020", at(18, 30), 37.424, -122.087),
        )
        assertFalse(ObservationPoolDiagnostics.shouldLog(summarize(candidates), now))
    }

    /** An empty box is a different failure (nothing cached at all) and has its own breadcrumbs. */
    @Test
    fun emptyCandidateSetIsNotLogged() {
        assertFalse(ObservationPoolDiagnostics.shouldLog(summarize(emptyList()), now))
    }
}

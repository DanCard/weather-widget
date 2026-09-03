package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.withQuantizedLocation
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure tests for the observation-backfill location resolution (plan 260721). These lock in the two
 * fixes that stopped NWS observations being written at Googleplex: SKIP when unanchored, and fetch
 * under the quantized authoritative location otherwise.
 */
@Category(ShortDuration::class)
class HourlyObservationBackfillLocationTest {

    @Test
    fun `null widget location is unanchored`() {
        val resolved = resolveBackfillLocation(null)
        assertEquals(
            BackfillLocation.Unanchored("unanchored_no_widget_location"),
            resolved,
        )
    }

    /**
     * The Googleplex-proximity guard is gone with the hard default itself: "no location" is now the
     * absence of coordinates. Non-finite coordinates are the write-side form of that absence and must
     * skip for the same reason -- a mis-keyed observation row is a permanent LocationMatch fragment.
     */
    @Test
    fun `non-finite coordinates are unanchored`() {
        assertEquals(
            BackfillLocation.Unanchored("unanchored_non_finite_location"),
            resolveBackfillLocation(Double.NaN to Double.NaN),
        )
        assertEquals(
            BackfillLocation.Unanchored("unanchored_non_finite_location"),
            resolveBackfillLocation(37.417 to Double.NaN),
        )
    }

    /**
     * The retired default coordinates are now just coordinates. Nothing in the steady-state pipeline
     * may treat proximity to them as "unset" -- installs still carrying them are cleared once by
     * LegacyDefaultLocationMigration instead.
     */
    @Test
    fun `coordinates near the retired default anchor like any other location`() {
        val resolved = resolveBackfillLocation(37.4220 to -122.0841)
        assertEquals(BackfillLocation.Anchored(37.422, -122.084), resolved)
    }

    @Test
    fun `a real location resolves anchored and quantized`() {
        // The real GPS fix from the emulator repro (37.4168/-122.089), far enough from HQ to be a
        // genuinely different site. It must anchor, and its coordinate must be snapped to 3 dp so the
        // fetched rows key the same site every source writes.
        val resolved = resolveBackfillLocation(37.416797637939453 to -122.08899688720703)
        assertEquals(
            BackfillLocation.Anchored(37.417, -122.089),
            resolved,
        )
    }

    @Test
    fun `withQuantizedLocation snaps raw double writes onto the shared grid`() {
        val raw = observationAt(37.416797637939453, -122.08899688720703)
        val snapped = raw.withQuantizedLocation()
        assertEquals(37.417, snapped.locationLat, 0.0)
        assertEquals(-122.089, snapped.locationLon, 0.0)
    }

    @Test
    fun `two nearby raw writes collapse to one key`() {
        // 37.41680 and 37.41684 are the same physical spot ~tens of metres apart; without quantization
        // they are two primary keys and INSERT-REPLACE accumulates fragments instead of overwriting.
        val a = observationAt(37.41680, -122.08900).withQuantizedLocation()
        val b = observationAt(37.41684, -122.08904).withQuantizedLocation()
        assertEquals(a.locationLat, b.locationLat, 0.0)
        assertEquals(a.locationLon, b.locationLon, 0.0)
    }

    @Test
    fun `WeatherAPI requests repair when visible yesterday is missing`() {
        val now = LocalDateTime.of(2026, 7, 28, 12, 0)
        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.WEATHER_API,
                graphStart = now.minusHours(72),
                graphEnd = now.plusHours(6),
                observations = emptyList(),
                now = now,
            )

        assertTrue(decision.shouldRequest)
        assertTrue(decision.reason.startsWith("weatherapi_history_sparse"))
    }

    @Test
    fun `WeatherAPI does not request repair when yesterday has twenty hours`() {
        val now = LocalDateTime.of(2026, 7, 28, 12, 0)
        val zone = ZoneId.systemDefault()
        val start = now.toLocalDate().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val observations =
            (0 until 20).map { index ->
                ObservationEntity(
                    stationId = "WEATHER_API_MAIN",
                    stationName = "WeatherAPI history",
                    timestamp = start + index * 3_600_000L,
                    temperature = 60f + index,
                    condition = "Clear",
                    locationLat = 37.417,
                    locationLon = -122.089,
                    api = com.weatherwidget.data.model.WeatherSource.WEATHER_API.id,
                )
            }

        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.WEATHER_API,
                graphStart = now.minusHours(72),
                graphEnd = now.plusHours(6),
                observations = observations,
                now = now,
            )

        assertEquals(false, decision.shouldRequest)
        assertTrue(decision.reason.startsWith("weatherapi_history_covered"))
    }

    @Test
    fun `NWS requests repair for a seven PM to seven AM history gap`() {
        val zone = ZoneId.systemDefault()
        val graphStart = LocalDateTime.of(2026, 7, 29, 19, 0)
        val now = LocalDateTime.of(2026, 7, 30, 7, 0)
        val observations =
            listOf(graphStart, now).map { dateTime ->
                nwsObservationAt(dateTime.atZone(zone).toInstant().toEpochMilli())
            }

        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
                graphStart = graphStart,
                graphEnd = now.plusHours(11),
                observations = observations,
                now = now,
            )

        assertTrue(decision.shouldRequest)
        assertEquals("max_gap_min=720", decision.reason)
    }

    @Test
    fun `NWS does not request repair for continuous overnight history`() {
        val zone = ZoneId.systemDefault()
        val graphStart = LocalDateTime.of(2026, 7, 29, 19, 0)
        val now = LocalDateTime.of(2026, 7, 30, 7, 0)
        val observations =
            generateSequence(graphStart) { it.plusMinutes(15) }
                .takeWhile { !it.isAfter(now) }
                .map { nwsObservationAt(it.atZone(zone).toInstant().toEpochMilli()) }
                .toList()

        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
                graphStart = graphStart,
                graphEnd = now.plusHours(11),
                observations = observations,
                now = now,
            )

        assertEquals(false, decision.shouldRequest)
        // `cloud=none` because these rows carry no sky condition at all — that is the sparsity
        // check's business, not the break check's, and the line says so rather than staying silent.
        assertEquals("coverage_ok latest_gap_min=0 max_gap_min=15 cloud=none", decision.reason)
    }

    // ---- METAR cloud coverage (plan 260820) ----
    //
    // Sky condition rides the same /observations payload as temperature, so a temperature-only
    // "coverage_ok" verdict cannot see a missing actual cloud curve. These lock the cloud-aware
    // branch of the same repair decision.

    private fun metarStationRows(
        graphStart: LocalDateTime,
        now: LocalDateTime,
        stationType: String,
        cloudCoverLow: Int?,
        isWebFallback: Boolean = false,
    ): List<ObservationEntity> {
        val zone = ZoneId.systemDefault()
        return generateSequence(graphStart) { it.plusMinutes(15) }
            .takeWhile { !it.isAfter(now) }
            .map {
                nwsObservationAt(it.atZone(zone).toInstant().toEpochMilli()).copy(
                    stationType = stationType,
                    cloudCoverLow = cloudCoverLow,
                    isWebFallback = isWebFallback,
                )
            }
            .toList()
    }

    @Test
    fun `NWS requests repair when temperature is covered but official stations carry no cloud`() {
        // The upgrade-window failure: every stored row predates cloud parsing, so cloudCoverLow is
        // null everywhere while temperature coverage looks fine. The same 72h re-fetch re-parses
        // the same payload and fills the curve.
        val graphStart = LocalDateTime.of(2026, 7, 29, 19, 0)
        val now = LocalDateTime.of(2026, 7, 30, 7, 0)
        val observations = metarStationRows(graphStart, now, "OFFICIAL", cloudCoverLow = null)

        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
                graphStart = graphStart,
                graphEnd = now.plusHours(11),
                observations = observations,
                now = now,
            )

        assertTrue(decision.shouldRequest)
        assertTrue(decision.reason.startsWith("metar_cloud_sparse"))
    }

    @Test
    fun `NWS does not request cloud repair when only personal stations exist`() {
        // PWS stations have no ceilometer and report cloudLayers: [] forever — they can never
        // satisfy the check, so they must not keep it (and its 30-minute re-fetch) firing.
        val graphStart = LocalDateTime.of(2026, 7, 29, 19, 0)
        val now = LocalDateTime.of(2026, 7, 30, 7, 0)
        val observations = metarStationRows(graphStart, now, "PERSONAL", cloudCoverLow = null)

        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
                graphStart = graphStart,
                graphEnd = now.plusHours(11),
                observations = observations,
                now = now,
            )

        assertFalse(decision.shouldRequest)
        assertTrue(decision.reason.startsWith("coverage_ok"))
    }

    @Test
    fun `NWS does not request cloud repair when official buckets carry cloud`() {
        val graphStart = LocalDateTime.of(2026, 7, 29, 19, 0)
        val now = LocalDateTime.of(2026, 7, 30, 7, 0)
        val observations = metarStationRows(graphStart, now, "OFFICIAL", cloudCoverLow = 75)

        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
                graphStart = graphStart,
                graphEnd = now.plusHours(11),
                observations = observations,
                now = now,
            )

        assertFalse(decision.shouldRequest)
        assertTrue(decision.reason.startsWith("coverage_ok"))
    }

    /**
     * Inverted 2026-08-21. This used to assert that web-fallback rows are ignored, on the stated
     * grounds that "Synoptic fallback rows are temperature-only by policy". They are not: Synoptic
     * returns the raw METAR and the parser ignored it. Excluding these rows meant a station that had
     * degraded to web fallback left the cloud basis silently instead of being reported as broken —
     * which is how KNUQ (3.8 km) went cloud-less while the check read healthy and the curve ran off
     * a station 15.9 km away.
     *
     * Now that [com.weatherwidget.data.remote.SynopticApi] parses sky condition, a cloud-less
     * official series is a real defect regardless of which path fetched it.
     */
    @Test
    fun `NWS cloud check counts web-fallback rows and reports them when cloud-less`() {
        val graphStart = LocalDateTime.of(2026, 7, 29, 19, 0)
        val now = LocalDateTime.of(2026, 7, 30, 7, 0)
        val observations = metarStationRows(
            graphStart, now, "OFFICIAL", cloudCoverLow = null, isWebFallback = true,
        )

        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
                graphStart = graphStart,
                graphEnd = now.plusHours(11),
                observations = observations,
                now = now,
            )

        assertTrue(decision.shouldRequest)
        assertTrue(decision.reason.startsWith("metar_cloud_sparse"))
    }

    /** The other half: web-fallback rows that DO carry cloud satisfy the check like any other. */
    @Test
    fun `NWS cloud check is satisfied by web-fallback rows carrying cloud`() {
        val graphStart = LocalDateTime.of(2026, 7, 29, 19, 0)
        val now = LocalDateTime.of(2026, 7, 30, 7, 0)
        val observations = metarStationRows(
            graphStart, now, "OFFICIAL", cloudCoverLow = 75, isWebFallback = true,
        )

        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
                graphStart = graphStart,
                graphEnd = now.plusHours(11),
                observations = observations,
                now = now,
            )

        assertFalse(decision.shouldRequest)
        assertTrue(decision.reason.startsWith("coverage_ok"))
    }

    @Test
    fun `withQuantizedLocation is idempotent`() {
        val once = observationAt(37.416797637939453, -122.08899688720703).withQuantizedLocation()
        val twice = once.withQuantizedLocation()
        assertEquals(once.locationLat, twice.locationLat, 0.0)
        assertEquals(once.locationLon, twice.locationLon, 0.0)
    }

    private fun observationAt(lat: Double, lon: Double) = ObservationEntity(
        stationId = "S",
        stationName = "S",
        timestamp = 0L,
        temperature = 70f,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        api = "NWS",
    )

    private fun nwsObservationAt(timestamp: Long) =
        observationAt(37.417, -122.089).copy(timestamp = timestamp)

    // ---- site-agreement guard (plan 260820) ----
    //
    // The coverage decision reads the observations the RENDERER loaded; the fetch goes to the
    // widget's stored location. When those are different places the decision says nothing about the
    // fetch site, the fetched rows land where the renderer will never read them, and the request
    // repeats on every paint. These lock the guard that breaks that loop.

    private fun anchored(lat: Double, lon: Double) =
        BackfillLocation.Anchored(LocationMatch.quantize(lat), LocationMatch.quantize(lon))

    @Test
    fun `same site is not a mismatch`() {
        assertNull(backfillSiteMismatchReason(anchored(37.417, -122.089), 37.417, -122.089))
    }

    @Test
    fun `a few blocks away is not a mismatch`() {
        // Inside the same box the observation query itself used, so the loaded rows could contain
        // the fetch site's observations and the decision is meaningful.
        assertNull(backfillSiteMismatchReason(anchored(37.4225, -122.0955), 37.4167, -122.089))
    }

    @Test
    fun `exactly at the tolerance boundary is not a mismatch`() {
        // ROOM_WHERE uses BETWEEN, which is inclusive; the guard must agree or it would reject rows
        // the query would have returned.
        val obsLat = 37.417
        val obsLon = -122.089
        val fetch = anchored(obsLat + LocationMatch.TOLERANCE_DEG, obsLon)
        assertNull(backfillSiteMismatchReason(fetch, obsLat, obsLon))
    }

    @Test
    fun `another state is a mismatch`() {
        // The emulator case: widget rendering Mountain View rows, backfill aimed at the Austin test
        // fixture. Fetching here filed 4,744 orphan rows and re-requested forever.
        val reason = backfillSiteMismatchReason(anchored(30.267, -97.743), 37.417, -122.089)
        assertNotNull(reason)
        assertTrue("reason should name the guard: $reason", reason!!.startsWith("location_mismatch"))
        assertTrue("reason should carry both sites: $reason", reason.contains("30.267"))
        assertTrue("reason should carry both sites: $reason", reason.contains("37.417"))
    }

    @Test
    fun `unknown observation location is a mismatch rather than an assumed match`() {
        assertEquals(
            "location_mismatch_obs_location_unknown",
            backfillSiteMismatchReason(anchored(37.417, -122.089), Double.NaN, -122.089),
        )
        assertEquals(
            "location_mismatch_obs_location_unknown",
            backfillSiteMismatchReason(anchored(37.417, -122.089), 37.417, Double.NaN),
        )
    }

    // --- day_start_uncovered: the truncated-START blind spot -------------------------------------
    //
    // Gap density cannot see a window that simply began late. Samsung 2026-08-22 logged
    // `coverage_ok latest_gap_min=19 max_gap_min=10` for hours while today's low was missing.

    private fun nwsObsAt(timestampMs: Long, temp: Float, station: String = "KNUQ") =
        ObservationEntity(
            stationId = station,
            stationName = "Moffett Field",
            timestamp = timestampMs,
            temperature = temp,
            condition = "Clear",
            locationLat = 37.417,
            locationLon = -122.089,
            stationType = "OFFICIAL",
            // Cloud present so metarCloudGapReason stays quiet and these cases isolate the
            // day-start check rather than tripping an unrelated repair reason.
            cloudCoverLow = 10,
            api = com.weatherwidget.data.model.WeatherSource.NWS.id,
        )

    /**
     * Evenly spaced NWS rows, two stations so the singleton check stays quiet.
     *
     * Both stations report on the SAME hour marks. They used to be offset by five minutes, which
     * made the merged series step 5, 55, 5, 55… — half the steps being the inter-station offset
     * rather than the reporting cadence. That is not "evenly spaced": `CloudActualSeries` reads
     * such a series as 5-minute-cadence with 55-minute holes and shatters the drawn line, so these
     * fixtures were quietly describing a broken curve while asserting a healthy one.
     */
    private fun evenlySpacedFrom(now: LocalDateTime, firstHour: Int): List<ObservationEntity> {
        val zone = ZoneId.systemDefault()
        val dayStart = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = mutableListOf<ObservationEntity>()
        var hour = firstHour
        while (hour <= now.hour) {
            val ts = dayStart + hour * 3_600_000L
            rows += nwsObsAt(ts, 60f + hour, "KNUQ")
            rows += nwsObsAt(ts, 60f + hour, "KPAO")
            hour++
        }
        return rows
    }

    @Test
    fun `a noon-onward window requests repair despite having no gaps`() {
        val now = LocalDateTime.of(2026, 8, 22, 19, 0)
        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
                graphStart = now.minusHours(72),
                graphEnd = now,
                observations = evenlySpacedFrom(now, firstHour = 12),
                now = now,
            )
        assertTrue("expected repair, got ${decision.reason}", decision.shouldRequest)
        assertTrue(
            "reason should name the truncated start, was ${decision.reason}",
            decision.reason.startsWith("day_start_uncovered"),
        )
    }

    @Test
    fun `a window covering the whole day does not request repair`() {
        val now = LocalDateTime.of(2026, 8, 22, 19, 0)
        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
                graphStart = now.minusHours(72),
                graphEnd = now,
                observations = evenlySpacedFrom(now, firstHour = 0),
                now = now,
            )
        assertFalse("expected no repair, got ${decision.reason}", decision.shouldRequest)
    }

    @Test
    fun `browsing history does not trigger repair when today was never loaded`() {
        // The window sits entirely in the past, so today's rows are absent by construction. Their
        // absence says nothing about coverage and must not be read as a truncated start.
        val now = LocalDateTime.of(2026, 8, 22, 19, 0)
        val zone = ZoneId.systemDefault()
        val pastDay = now.toLocalDate().minusDays(5)
        val pastStart = pastDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = (0 until 24).flatMap { h ->
            listOf(
                nwsObsAt(pastStart + h * 3_600_000L, 60f + h, "KNUQ"),
                nwsObsAt(pastStart + h * 3_600_000L + 300_000L, 60f + h, "KPAO"),
            )
        }
        val decision =
            evaluateHourlyBackfillNeed(
                displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
                graphStart = pastDay.atStartOfDay(),
                graphEnd = pastDay.plusDays(1).atStartOfDay(),
                observations = rows,
                now = now,
            )
        assertFalse(
            "a past-only window must not be judged on today's coverage, got ${decision.reason}",
            decision.reason.startsWith("day_start_uncovered"),
        )
    }
}

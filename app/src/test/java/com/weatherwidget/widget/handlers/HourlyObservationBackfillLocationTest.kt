package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.withQuantizedLocation
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
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
        assertEquals("coverage_ok latest_gap_min=0 max_gap_min=15", decision.reason)
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
}

package com.weatherwidget.shared.actuals

import com.weatherwidget.data.local.ObservationSiteMerge
import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * [ObservationSiteMerge] driven through the two blends that consume observation rows, on the scene
 * measured 2026-08-27: an ~800 m walk put every observation for 75 minutes under a second
 * device-site fragment, and both actual lines drew a hole over data that was in the database.
 *
 * The unit test pins the merge rule. This pins that both consumers survive the merge — in
 * particular that the temperature blend, which has no `(station, timestamp)` dedup of its own,
 * counts a station once rather than twice.
 */
@Category(ShortDuration::class)
class ObservationSiteMergeBlendIntegrationTest {

    private val hour = 1_787_600_000_000L / 3_600_000L * 3_600_000L
    private val homeLat = 37.417
    private val homeLon = -122.089
    private val courtLat = 37.424   // 783 m away
    private val courtLon = -122.088

    private fun row(
        station: String,
        minutes: Long,
        atCourt: Boolean,
        temperature: Float,
        cloudLow: Int?,
        distanceKm: Float,
    ) = ObservationReading(
        stationId = station,
        stationName = station,
        timestamp = hour + minutes * 60_000L,
        temperature = temperature,
        condition = "Cloudy",
        locationLat = if (atCourt) courtLat else homeLat,
        locationLon = if (atCourt) courtLon else homeLon,
        distanceKm = distanceKm,
        stationType = "OFFICIAL",
        api = WeatherSource.NWS.id,
        isMetar = true,
        rawMetar = "$station REPORT",
        cloudCoverLow = cloudLow,
        cloudVerticalKind = if (cloudLow != null) CloudVerticalKind.CUMULATIVE_LAYERS else CloudVerticalKind.NONE,
    )

    /**
     * Minutes 0-15 at home, 20-45 at the court (the excursion), 50-60 back home — the shape of the
     * measured outage, where the middle stretch exists only under the other fragment.
     */
    private fun scene(): List<ObservationReading> = listOf(
        row("KSJC", 0, atCourt = false, temperature = 80f, cloudLow = 44, distanceKm = 15.9f),
        row("KSJC", 15, atCourt = false, temperature = 80f, cloudLow = 44, distanceKm = 15.9f),
        row("KSJC", 20, atCourt = true, temperature = 81f, cloudLow = 44, distanceKm = 16.2f),
        row("KSJC", 30, atCourt = true, temperature = 81f, cloudLow = 44, distanceKm = 16.2f),
        row("KSJC", 45, atCourt = true, temperature = 81f, cloudLow = 44, distanceKm = 16.2f),
        row("KSJC", 60, atCourt = false, temperature = 80f, cloudLow = 44, distanceKm = 15.9f),
    )

    private fun merged(rows: List<ObservationReading>) = ObservationSiteMerge.merge(
        rows = rows, lat = homeLat, lon = homeLon,
        latOf = ObservationReading::locationLat, lonOf = ObservationReading::locationLon,
        stationOf = ObservationReading::stationId, timestampOf = ObservationReading::timestamp,
        apiOf = ObservationReading::api, fetchedAtOf = ObservationReading::fetchedAt,
    )

    @Test
    fun `the cloud actual series is continuous across the excursion`() = runBlocking {
        val result = MetarCloudBlender.fromSiteRows(
            startMs = hour,
            endMs = hour + 3_600_000L + 60_000L,
            sourceId = WeatherSource.NWS.id,
            readSiteRows = { _, _ -> merged(scene()) },
        )

        assertEquals(
            "every report must reach the curve, including the three filed at the court",
            6,
            result.hours.size,
        )
        val gaps = result.hours.keys.sorted().zipWithNext { a, b -> b - a }
        assertTrue(
            "no gap may exceed the 30-minute bridge that would split the line: $gaps",
            gaps.all { it <= 30 * 60_000L },
        )
    }

    /**
     * The step that makes the merge safe for temperature. `ActualTemperatureSeriesBuilder` has no
     * `(station, timestamp)` dedup of its own — it goes straight to `groupBy { stationId }` — so a
     * duplicate arriving from two fragments would be blended twice and double-weighted.
     */
    @Test
    fun `a station reported from both fragments is counted once`() {
        val duplicated = scene() + listOf(
            row("KSJC", 15, atCourt = true, temperature = 99f, cloudLow = 44, distanceKm = 16.2f),
            row("KSJC", 60, atCourt = true, temperature = 99f, cloudLow = 44, distanceKm = 16.2f),
        )

        val out = merged(duplicated)

        assertEquals("the duplicates must collapse", 6, out.size)
        assertEquals(
            "one row per timestamp",
            out.map { it.timestamp }.distinct().size,
            out.size,
        )
        assertTrue(
            "the home copy wins, so distanceKm stays in the accurate frame",
            out.filter { it.timestamp == hour + 15 * 60_000L }.all { it.distanceKm == 15.9f },
        )
    }

    /** A fragment from a genuinely different place must still be excluded. */
    @Test
    fun `a distant fragment does not join the series`() {
        val withNeighbouringTown = scene() + row(
            "KSFO", 30, atCourt = false, temperature = 60f, cloudLow = 100, distanceKm = 30f,
        ).copy(locationLat = 37.500, locationLon = -122.089)

        assertTrue(merged(withNeighbouringTown).none { it.stationId == "KSFO" })
    }
}

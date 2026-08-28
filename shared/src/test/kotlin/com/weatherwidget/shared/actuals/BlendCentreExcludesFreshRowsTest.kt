package com.weatherwidget.shared.actuals

import com.weatherwidget.data.local.ObservationSiteMerge
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.DominantStationLabel
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the *centre* of an observation read decides, end to end: merge → blend → the label the user
 * reads.
 *
 * The scene is the one measured 2026-08-28 on a Samsung Fold. The device moved ~6 km at 11:55; the
 * fetch pipeline followed and kept writing, so KNUQ had reported as recently as 13:35. The render
 * kept reading at the site the device had left, whose newest row was frozen at 11:10, and the graph
 * label said `knuq 66.2 @ 11:10 am` at 14:17.
 *
 * The two sites are 0.068° apart in longitude — well outside
 * [ObservationSiteMerge.MERGE_TOLERANCE_DEG] — which is why a stale centre does not merely
 * down-weight the fresh rows but removes them before the blend ever runs.
 *
 * **Every assertion here is on a timestamp, never on a temperature.** KNUQ read 66.2 °F at both
 * 11:10 and 13:35, so a value assertion passes under the bug — which is precisely why the original
 * report read as "plausible but stale" instead of as obviously broken.
 */
@Category(ShortDuration::class)
class BlendCentreExcludesFreshRowsTest {

    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val day: LocalDate = LocalDate.of(2026, 8, 28)

    /** Where the device was, and is configured to be, from 11:55 onward. */
    private val currentLat = 37.406
    private val currentLon = -122.021

    /** Where it was until 11:55. Still inside the coarse read box, frozen since 11:26. */
    private val leftLat = 37.417
    private val leftLon = -122.089

    private val now: LocalDateTime = day.atTime(14, 17)

    private fun at(hour: Int, minute: Int): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun knuq(
        hour: Int,
        minute: Int,
        temperature: Float,
        atCurrentSite: Boolean,
    ) = ObservationReading(
        stationId = "KNUQ",
        stationName = "Mountain View, Moffett Field",
        timestamp = at(hour, minute),
        temperature = temperature,
        condition = "Clear",
        locationLat = if (atCurrentSite) currentLat else leftLat,
        locationLon = if (atCurrentSite) currentLon else leftLon,
        // KNUQ is 2.4 km from where the device is now and ~5 km from where it was.
        distanceKm = if (atCurrentSite) 2.4f else 5.0f,
        stationType = "OFFICIAL",
        api = WeatherSource.NWS.id,
        fetchedAt = if (atCurrentSite) at(14, 5) else at(11, 26),
        isMetar = true,
    )

    /**
     * The frozen fragment runs to 11:10; the live one picks up at 11:35 and runs to 13:35. Both end
     * on 66.2 °F — the coincidence that made the stale label look like a real reading.
     */
    private fun scene(): List<ObservationReading> = listOf(
        knuq(9, 55, 66.2f, atCurrentSite = false),
        knuq(10, 35, 64.4f, atCurrentSite = false),
        knuq(10, 55, 66.2f, atCurrentSite = false),
        knuq(11, 10, 66.2f, atCurrentSite = false),
        knuq(11, 35, 66.2f, atCurrentSite = true),
        knuq(12, 15, 64.4f, atCurrentSite = true),
        knuq(12, 55, 64.4f, atCurrentSite = true),
        knuq(13, 15, 64.4f, atCurrentSite = true),
        knuq(13, 35, 66.2f, atCurrentSite = true),
    )

    /** Background scaffolding so the blend can extrapolate; null coords keep it site-agnostic. */
    private fun forecasts(): List<HourlyForecast> = (6..20).map { hour ->
        HourlyForecast(
            dateTime = at(hour, 0),
            temperature = 65f,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            fetchedAt = at(14, 5),
            locationLat = null,
            locationLon = null,
        )
    }

    private fun mergedAt(lat: Double, lon: Double): List<ObservationReading> =
        ObservationSiteMerge.merge(
            rows = scene(), lat = lat, lon = lon,
            latOf = ObservationReading::locationLat, lonOf = ObservationReading::locationLon,
            stationOf = ObservationReading::stationId, timestampOf = ObservationReading::timestamp,
            apiOf = ObservationReading::api, fetchedAtOf = ObservationReading::fetchedAt,
        )

    private fun dominantAt(lat: Double, lon: Double): DominantBlend? =
        ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts(),
            observations = mergedAt(lat, lon),
            centerTime = now,
            displaySourceId = WeatherSource.NWS.id,
            userLat = lat,
            userLon = lon,
            backHours = 12,
            forwardHours = 6,
            contextLookbackHours = 72,
            contextLookaheadHours = 60,
            now = now,
            zoneId = zone,
            captureLatestDominantAtOrBeforeMs = at(14, 17),
        ).latestDominantContribution

    /**
     * The fact the whole fix rests on: centring on the site the device left does not rank the fresh
     * rows lower, it deletes them. No amount of downstream blending can recover from this.
     */
    @Test
    fun `a stale centre excludes every fresh row rather than down-weighting it`() {
        val merged = mergedAt(leftLat, leftLon)

        assertTrue(
            "no row from the live site may survive a read centred on the abandoned one",
            merged.none { it.locationLat == currentLat },
        )
        assertEquals(
            "the newest row reachable from the stale centre is the frozen 11:10 reading",
            at(11, 10),
            merged.maxOf { it.timestamp },
        )
    }

    @Test
    fun `centring on the configured location reaches the 13-35 reading`() {
        val merged = mergedAt(currentLat, currentLon)

        assertEquals(
            "the live fragment must be readable in full",
            at(13, 35),
            merged.maxOf { it.timestamp },
        )
    }

    @Test
    fun `the dominant contribution is dated by the centre, not by what exists`() {
        val fromCurrentSite = dominantAt(currentLat, currentLon)
        val fromLeftSite = dominantAt(leftLat, leftLon)

        assertNotNull("the live centre must name a station", fromCurrentSite)
        assertNotNull("the stale centre still names one — that is what made this hard to spot", fromLeftSite)
        assertEquals("KNUQ", fromCurrentSite?.contribution?.stationId)

        assertEquals(
            "the configured centre must report KNUQ's newest reading",
            at(13, 35),
            fromCurrentSite?.contribution?.lastReadingMs,
        )
        assertEquals(
            "the abandoned centre is stuck on the frozen one",
            at(11, 10),
            fromLeftSite?.contribution?.lastReadingMs,
        )
    }

    /**
     * The user-visible end of the chain. Both labels name the same station at the same temperature;
     * only the clock time differs, so this is the only assertion that would have caught the bug on
     * screen.
     */
    @Test
    fun `the graph label the user reads differs by centre alone`() {
        val fresh = DominantStationLabel.formatLabelText(
            dominantAt(currentLat, currentLon)?.contribution, useCelsius = false, zoneId = zone,
        )
        val stale = DominantStationLabel.formatLabelText(
            dominantAt(leftLat, leftLon)?.contribution, useCelsius = false, zoneId = zone,
        )

        assertNotNull(fresh)
        assertNotNull(stale)
        assertTrue("expected the 1:35 pm reading, got ${fresh?.fullText}", fresh!!.fullText.contains("1:35"))
        assertTrue("expected the 11:10 am reading, got ${stale?.fullText}", stale!!.fullText.contains("11:10"))
        assertNotEquals(
            "the two centres must not be indistinguishable on screen",
            stale.fullText,
            fresh.fullText,
        )
    }
}

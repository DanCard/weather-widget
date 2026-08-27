package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.MetarCloudBlender
import com.weatherwidget.shared.graph.CloudActualSeries
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The gap this change exists to close, end to end: station rows in →
 * [MetarCloudBlender] → [CloudActualSeries.segments], which is what actually decides whether the
 * drawn line is one piece or two.
 *
 * Reproduces the measured 2026-08-27 scene. KNUQ reported every 20 minutes but omitted its sky
 * group at 12:55 and 13:15; KPAO reports hourly at :47. Cloud-carrying reports therefore ran
 * 12:47 → 13:35 — 48 minutes, past the 30-minute bridge, so the line split.
 */
@Category(ShortDuration::class)
class SkyStationSlotsGapIntegrationTest {

    private val noon = 1_787_601_600_000L / 3_600_000L * 3_600_000L
    private fun at(minutes: Long) = noon + minutes * 60_000L

    private fun reading(station: String, km: Float, minutes: Long, low: Int?, mid: Int? = null) =
        ObservationReading(
            stationId = station,
            stationName = station,
            timestamp = at(minutes),
            temperature = 75f,
            condition = "Cloudy",
            locationLat = 37.417,
            locationLon = -122.089,
            distanceKm = km,
            stationType = "OFFICIAL",
            api = WeatherSource.SYNOPTIC.id,
            isMetar = true,
            rawMetar = "$station REPORT",
            cloudCoverLow = low,
            cloudCoverMid = mid,
            cloudVerticalKind = CloudVerticalKind.CUMULATIVE_LAYERS,
        )

    /** KNUQ every 20 min but silent (no sky group) at 55 and 75; KPAO hourly at :47. */
    private fun twoStationScene() = listOf(
        reading("KNUQ", 3.8f, 35, low = 0),
        reading("KPAO", 6.1f, 47, low = null, mid = 75),
        reading("KNUQ", 3.8f, 95, low = 0),
        reading("KPAO", 6.1f, 107, low = null, mid = 75),
    )

    /** A third reporter on a 5-minute cadence, the kind the proximity cap was excluding. */
    private fun withThirdStation() = twoStationScene() + (40..110 step 5).map {
        reading("KSJC", 15.9f, it.toLong(), low = 44, mid = 75)
    }

    private fun segmentsOf(rows: List<ObservationReading>): List<List<Long>> = runBlocking {
        val blended = MetarCloudBlender.fromSiteRows(
            startMs = at(0),
            endMs = at(180),
            sourceId = WeatherSource.SYNOPTIC.id,
            readSiteRows = { _, _ -> rows },
        )
        CloudActualSeries.segments(
            CloudActualSeries.points(blended.hours, at(0), at(180)),
        ).map { seg -> seg.map { it.timeMs } }
    }

    @Test
    fun `two reporters leave a gap the bridge refuses to cross`() {
        val segments = segmentsOf(twoStationScene())

        assertTrue(
            "expected the line to split, as it did on the device: ${segments.size} segment(s)",
            segments.size > 1,
        )
    }

    @Test
    fun `a third sky-reporting station closes it`() {
        val segments = segmentsOf(withThirdStation())

        assertEquals(
            "one continuous line: ${segments.map { it.size }}",
            1,
            segments.size,
        )
    }

    /**
     * The claim underneath the whole change: the third station is admitted because it reports sky,
     * not because it is near. It is the FARTHEST of the three.
     */
    @Test
    fun `the station that closes the gap is the farthest one`() {
        val rows = withThirdStation()
        val closer = rows.filter { it.stationId != "KSJC" }.map { it.distanceKm }.max()

        assertTrue(
            "KSJC must be farther than both stations already kept",
            rows.first { it.stationId == "KSJC" }.distanceKm > closer,
        )
        assertEquals(1, segmentsOf(rows).size)
    }
}

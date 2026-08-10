package com.weatherwidget.shared.graph

import com.weatherwidget.shared.actuals.BlendContribution
import com.weatherwidget.test.category.ShortDuration
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The dominant-station annotation on the hourly graph: what it says, when it is allowed to say it, and
 * where it lands. The zoom gate (test [threeDayViewGetsNoLabel]) is the user-facing requirement — the
 * label is for near zooms only.
 */
@Category(ShortDuration::class)
class DominantStationLabelTest {

    private val plot = GraphRect(0f, 0f, 400f, 200f)
    private val metrics = GraphEmptySpaceFinder.Metrics(width = 60f, ascent = -10f, descent = 3f)

    /** A curve pinned low in the plot, leaving the top band free. */
    private val lowCurve: (Float) -> List<Float> = { listOf(190f) }

    private fun place(
        text: String? = "knuq 73.4°",
        spanHours: Long = 24L,
        plot: GraphRect = this.plot,
        drawnBounds: List<GraphRect> = emptyList(),
        curveYsAt: (Float) -> List<Float> = lowCurve,
        metrics: GraphEmptySpaceFinder.Metrics = this.metrics,
        padPx: Float = 4f,
    ) = DominantStationLabel.place(
        text = text,
        spanHours = spanHours,
        plot = plot,
        drawnBounds = drawnBounds,
        curveYsAt = curveYsAt,
        metrics = metrics,
        padPx = padPx,
    )

    // ---- format ----

    // A fixed zone + wall-clock instant, so the expected string cannot drift with the CI machine's
    // timezone (5:15 pm in New York is not 5:15 pm anywhere else).
    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private fun msAt(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 8, 9, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun formatLowercasesTheStationId() {
        assertEquals("knuq 73.4°", DominantStationLabel.format("KNUQ", 73.4f, useCelsius = false))
    }

    @Test
    fun formatAppendsTheReadingTime() {
        assertEquals(
            "knuq 73.4° @ 5:15 pm",
            DominantStationLabel.format("KNUQ", 73.4f, useCelsius = false, lastReadingMs = msAt(17, 15), zoneId = zone),
        )
    }

    @Test
    fun readingTimeIsTwelveHourWithNoLeadingZero() {
        assertEquals(
            "knuq 73.4° @ 9:05 am",
            DominantStationLabel.format("KNUQ", 73.4f, useCelsius = false, lastReadingMs = msAt(9, 5), zoneId = zone),
        )
        assertEquals(
            "knuq 73.4° @ 12:00 am",
            DominantStationLabel.format("KNUQ", 73.4f, useCelsius = false, lastReadingMs = msAt(0, 0), zoneId = zone),
        )
        assertEquals(
            "knuq 73.4° @ 12:30 pm",
            DominantStationLabel.format("KNUQ", 73.4f, useCelsius = false, lastReadingMs = msAt(12, 30), zoneId = zone),
        )
    }

    @Test
    fun readingTimeIsRenderedInTheSuppliedZone() {
        // Same instant, two zones — the label must read as the local wall clock, not UTC.
        val ms = msAt(17, 15)
        assertEquals(
            "knuq 73.4° @ 8:15 pm",
            DominantStationLabel.format("KNUQ", 73.4f, useCelsius = false, lastReadingMs = ms, zoneId = ZoneId.of("America/New_York")),
        )
    }

    @Test
    fun missingReadingTimeDropsOnlyTheAtClause() {
        // An undated temperature still beats no label at all.
        assertEquals(
            "knuq 73.4°",
            DominantStationLabel.format("KNUQ", 73.4f, useCelsius = false, lastReadingMs = null, zoneId = zone),
        )
        assertEquals(
            "knuq 73.4°",
            DominantStationLabel.format("KNUQ", 73.4f, useCelsius = false, lastReadingMs = 0L, zoneId = zone),
        )
    }

    @Test
    fun noStationOrTemperatureStillReturnsNullEvenWithAReadingTime() {
        assertNull(DominantStationLabel.format(null, 73.4f, useCelsius = false, lastReadingMs = msAt(17, 15), zoneId = zone))
        assertNull(DominantStationLabel.format("KNUQ", null, useCelsius = false, lastReadingMs = msAt(17, 15), zoneId = zone))
    }

    // ---- format(BlendContribution) ----

    private fun contribution(
        stationId: String = "KNUQ",
        rawTemp: Float = 73.4f,
        lastReadingMs: Long = msAt(17, 15),
        isSynthetic: Boolean = false,
    ) = BlendContribution(
        stationId = stationId,
        stationName = stationId,
        stationType = "OFFICIAL",
        distanceKm = 3.8f,
        lastReadingMs = lastReadingMs,
        rawTemp = rawTemp,
        resolvedTemp = rawTemp,
        sourceKind = "observed",
        ageMs = 0L,
        weight = 1.0,
        weightShare = 1.0,
        isSynthetic = isSynthetic,
    )

    @Test
    fun contributionOverloadCarriesEveryFieldThrough() {
        assertEquals(
            "knuq 73.4° @ 5:15 pm",
            DominantStationLabel.format(contribution(), useCelsius = false, zoneId = zone),
        )
    }

    @Test
    fun syntheticContributionGetsNoLabel() {
        // OPEN_METEO_MAIN and friends are the source's own hourly forecast re-filed as observations.
        // Naming one would print an internal id beside a number that is not a measurement — and since
        // forecast-only sources have no real stations, it is the dominant row every single time.
        assertNull(
            DominantStationLabel.format(
                contribution(stationId = "OPEN_METEO_MAIN", rawTemp = 71.2f, isSynthetic = true),
                useCelsius = false,
                zoneId = zone,
            ),
        )
        // Same row, flag off: proves the suppression is the flag's doing and not some other field.
        assertNotNull(
            DominantStationLabel.format(
                contribution(stationId = "OPEN_METEO_MAIN", rawTemp = 71.2f, isSynthetic = false),
                useCelsius = false,
                zoneId = zone,
            ),
        )
    }

    @Test
    fun noContributionGetsNoLabel() {
        assertNull(DominantStationLabel.format(null, useCelsius = false, zoneId = zone))
    }

    @Test
    fun formatDropsTheDecimalOnAWholeDegree() {
        assertEquals("knuq 73°", DominantStationLabel.format("KNUQ", 73.0f, useCelsius = false))
    }

    @Test
    fun formatConvertsToCelsius() {
        // 73.4°F == 23.0°C exactly, so the whole-degree branch applies in the display unit.
        assertEquals("knuq 23°", DominantStationLabel.format("KNUQ", 73.4f, useCelsius = true))
    }

    @Test
    fun formatReturnsNullWithoutAStationOrTemperature() {
        assertNull(DominantStationLabel.format(null, 73.4f, useCelsius = false))
        assertNull(DominantStationLabel.format("  ", 73.4f, useCelsius = false))
        assertNull(DominantStationLabel.format("KNUQ", null, useCelsius = false))
    }

    // ---- visibility ----

    @Test
    fun nothingToSayMeansNoLabel() {
        assertNull(place(text = null))
        assertNull(place(text = "   "))
    }

    @Test
    fun threeDayViewGetsNoLabel() {
        // THREE_DAY spans 48h back + 24h forward. "The dominant station right now" says nothing about
        // most of what is on screen there, so the label retires.
        assertNull(place(spanHours = 72L))
    }

    @Test
    fun wideAndNarrowViewsGetTheLabel() {
        assertNotNull(place(spanHours = 24L)) // WIDE
        assertNotNull(place(spanHours = 5L)) // NARROW default
    }

    @Test
    fun spanGateIsInclusiveAtTheBoundary() {
        assertNotNull(place(spanHours = DominantStationLabel.MAX_HOURS_SPAN))
        assertNull(place(spanHours = DominantStationLabel.MAX_HOURS_SPAN + 1))
    }

    // ---- "if no space found don't state it" ----

    @Test
    fun plotTooNarrowForTheTextMeansNoLabel() {
        assertNull(place(plot = GraphRect(0f, 0f, 60f, 200f), padPx = 4f))
    }

    @Test
    fun plotTooShortForTheTextMeansNoLabel() {
        assertNull(place(plot = GraphRect(0f, 0f, 400f, 10f)))
    }

    @Test
    fun obstaclesCoveringThePlotMeanNoLabel() {
        assertNull(place(drawnBounds = listOf(GraphRect(-10f, -10f, 410f, 210f))))
    }

    @Test
    fun curveThroughEveryCandidateBoxMeansNoLabel() {
        // A curve that tracks the middle of every candidate band leaves no clearance anywhere.
        var probeCount = 0
        val everywhereCurve: (Float) -> List<Float> = { probeCount++; listOf(100f) }
        // Plot height 21 == box height 13 + 2 * pad 4, so exactly one candidate box exists (90+4 ..
        // 90+17) and the curve runs straight through it. Any taller and the search would legitimately
        // find a band clear of the curve, testing nothing.
        assertNull(
            place(
                plot = GraphRect(0f, 90f, 400f, 111f),
                curveYsAt = everywhereCurve,
            ),
        )
        assertTrue("expected the curve to actually be sampled", probeCount > 0)
    }

    @Test
    fun clearanceShorterThanPadMeansNoLabel() {
        // Single candidate box at 4..17 (see above). The curve clears it — but by 2px, under the 4px
        // pad — so the label is dropped rather than drawn crowding the line.
        var probeCount = 0
        assertNull(place(plot = GraphRect(0f, 0f, 400f, 21f), curveYsAt = { probeCount++; listOf(19f) }, padPx = 4f))
        assertTrue("expected the curve to actually be sampled", probeCount > 0)
    }

    // ---- placement ----

    @Test
    fun prefersTheEdgeAnchorOverTheCenter() {
        // Mirror of ForecastDeltaLabel's center-first list: on an empty plot the two labels drift apart.
        val placement = requireNotNull(place())
        val expectedCx = (0.22f * 400f).coerceAtLeast(60f / 2f + 4f)
        assertEquals(expectedCx, placement.centerX, 0.01f)
        assertEquals(0.5f, ForecastDeltaLabel.X_FRACTIONS.first(), 0.001f)
        assertEquals(0.22f, DominantStationLabel.X_FRACTIONS.first(), 0.001f)
    }

    @Test
    fun blockedEdgeFallsThroughToTheNextAnchorRatherThanVanishing() {
        // Cover the whole left third; the 0.78 anchor is still open.
        val placement = requireNotNull(place(drawnBounds = listOf(GraphRect(0f, 0f, 140f, 200f))))
        assertTrue("expected the label to move right, got ${placement.centerX}", placement.centerX > 140f)
    }

    @Test
    fun placementNeverLeavesThePlotOrTouchesAnObstacle() {
        val obstacle = GraphRect(0f, 0f, 140f, 200f)
        val placement = requireNotNull(place(drawnBounds = listOf(obstacle)))
        assertTrue(placement.box.left >= plot.left)
        assertTrue(placement.box.right <= plot.right)
        assertTrue(placement.box.top >= plot.top)
        assertTrue(placement.box.bottom <= plot.bottom)
        assertTrue(!obstacle.intersects(placement.box))
    }

    @Test
    fun baselineSitsBelowTheBoxTopByTheAscent() {
        // The dual-convention contract: Android draws from the baseline, Compose from box.topLeft.
        val placement = requireNotNull(place())
        assertEquals(placement.box.top - metrics.ascent, placement.baselineY, 0.001f)
        assertEquals(metrics.height, placement.box.bottom - placement.box.top, 0.001f)
        assertEquals(metrics.width, placement.box.right - placement.box.left, 0.001f)
    }

    @Test
    fun placedTextIsWhatWasPassedIn() {
        assertEquals("knuq 73.4°", requireNotNull(place()).text)
    }
}

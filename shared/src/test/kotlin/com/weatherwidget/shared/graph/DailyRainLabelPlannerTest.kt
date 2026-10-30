package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyRainLabelPlannerTest {

    @Test
    fun `resolveDayRainAnchorTop prefers topmost element in screen coordinates`() {
        // When snapshot bar reaches higher (smaller Y, e.g. 20 vs 45)
        val anchor1 = DailyRainLabelPlanner.resolveDayRainAnchorTop(
            highLabelTop = 45f,
            snapshotBarTop = 20f,
        )
        assertEquals(20f, anchor1!!, 0.001f)

        // When headline high reaches higher (e.g. 15 vs 30)
        val anchor2 = DailyRainLabelPlanner.resolveDayRainAnchorTop(
            highLabelTop = 15f,
            snapshotBarTop = 30f,
        )
        assertEquals(15f, anchor2!!, 0.001f)

        // When snapshotBarTop is null
        val anchor3 = DailyRainLabelPlanner.resolveDayRainAnchorTop(
            highLabelTop = 50f,
            snapshotBarTop = null,
        )
        assertEquals(50f, anchor3!!, 0.001f)

        // When both are null, returns fallback
        val anchor4 = DailyRainLabelPlanner.resolveDayRainAnchorTop(
            highLabelTop = null,
            snapshotBarTop = null,
            fallbackTop = 12f,
        )
        assertEquals(12f, anchor4!!, 0.001f)

        // When all null, returns null
        val anchor5 = DailyRainLabelPlanner.resolveDayRainAnchorTop(
            highLabelTop = null,
            snapshotBarTop = null,
        )
        assertNull(anchor5)
    }

    @Test
    fun `resolveRainAboveAnchorPlacement places rain label above anchor with gap`() {
        // High top at 56, gap of 3, rain descent 4, ascent -14 (height 18)
        // baseline = 56 - 3 - 4 = 49
        // top = 49 - 14 = 35
        // bottom = 49 + 4 = 53
        val placement = DailyRainLabelPlanner.resolveRainAboveAnchorPlacement(
            anchorTop = 56f,
            ascent = -14f,
            descent = 4f,
            topMargin = 8f,
            gap = 3f,
        )

        assertTrue(placement.fits)
        assertEquals(49f, placement.baseline, 0.01f)
        assertEquals(35f, placement.top, 0.01f)
        assertEquals(53f, placement.bottom, 0.01f)
        assertEquals(56f, placement.anchorTop, 0.01f)
    }

    @Test
    fun `resolveRainAboveAnchorPlacement detects when label exceeds top margin`() {
        val placement = DailyRainLabelPlanner.resolveRainAboveAnchorPlacement(
            anchorTop = 15f,
            ascent = -14f,
            descent = 4f,
            topMargin = 8f,
            gap = 3f,
        )

        assertFalse(placement.fits)
        // baseline = 15 - 3 - 4 = 8
        // top = 8 - 14 = -6 (violates topMargin = 8)
        assertEquals(-6f, placement.top, 0.01f)
    }

    @Test
    fun `resolveRainAboveHighPlacement uses snapshotBarTop when taller than high label`() {
        // highBaseline = 80, ascent = -24 => highLabelTop = 56
        // snapshotBarTop = 30 (taller / smaller Y)
        val placement = DailyRainLabelPlanner.resolveRainAboveHighPlacement(
            highBaseline = 80f,
            highMetrics = DailyRainLabelPlanner.TextMetrics(ascent = -24f, descent = 6f),
            rainMetrics = DailyRainLabelPlanner.TextMetrics(ascent = -14f, descent = 4f),
            topMargin = 8f,
            gap = 3f,
            snapshotBarTop = 30f,
        )

        assertEquals(30f, placement.anchorTop, 0.01f)
        // baseline = 30 - 3 - 4 = 23
        assertEquals(23f, placement.baseline, 0.01f)
        assertEquals(27f, placement.bottom, 0.01f)
        assertEquals(9f, placement.top, 0.01f)
        assertTrue(placement.fits)
    }

    @Test
    fun `resolveRainAboveAnchorTop calculates correct top for bounding boxes`() {
        val top = DailyRainLabelPlanner.resolveRainAboveAnchorTop(
            anchorTop = 50f,
            rainHeight = 15f,
            gapPx = -3f, // negative gap = tuck 3px
            floorY = 5f,
        )
        // 50 - (-3) - 15 = 38
        assertEquals(38f, top, 0.01f)

        // Clamping to floor
        val clampedTop = DailyRainLabelPlanner.resolveRainAboveAnchorTop(
            anchorTop = 10f,
            rainHeight = 15f,
            gapPx = 0f,
            floorY = 5f,
        )
        // 10 - 0 - 15 = -5 => clamped to 5
        assertEquals(5f, clampedTop, 0.01f)
    }

    @Test
    fun `calculateNightTuck computes correct fractions`() {
        // Roomy column (roomBelowDp = 25 >= max 22)
        val roomy = DailyRainLabelPlanner.calculateNightTuck(roomBelowDp = 25f)
        assertEquals(0f, roomy.tightFraction, 0.001f)
        assertEquals(1f, roomy.roomFraction, 0.001f)
        assertEquals(2.5f, roomy.roomyDownDp, 0.001f)
        assertEquals(0f, roomy.dynamicOverlapDp, 0.001f)

        // Cramped column (roomBelowDp = 5 <= min 10)
        val cramped = DailyRainLabelPlanner.calculateNightTuck(roomBelowDp = 5f)
        assertEquals(1f, cramped.tightFraction, 0.001f)
        assertEquals(0f, cramped.roomFraction, 0.001f)
        assertEquals(0f, cramped.roomyDownDp, 0.001f)
        assertEquals(5.0f, cramped.dynamicOverlapDp, 0.001f)
    }

    // ── placeNightLabel ─────────────────────────────────────────────────────────────────────────
    // Density 1 (1 px = 1 dp) unless stated. Label 20×8 at home centre 100, top 50.

    private fun place(
        obstacles: List<DailyRainLabelPlanner.Box>,
        maxBottom: Float = 200f,
        maxShift: Float = 15f,
        density: Float = 1f,
        homeCenterX: Float = 100f,
        width: Float = 20f,
        height: Float = 8f,
        homeTop: Float = 50f,
    ) = DailyRainLabelPlanner.placeNightLabel(
        homeCenterX = homeCenterX,
        homeTop = homeTop,
        width = width,
        height = height,
        obstacles = obstacles,
        maxShiftPx = maxShift,
        maxBottom = maxBottom,
        minLeft = 0f,
        maxRight = 1000f,
        density = density,
    )

    @Test
    fun `nothing in the way - label stays exactly at the gap midpoint`() {
        val p = place(emptyList())
        assertEquals("home", p.placement)
        assertEquals(100f, p.centerX, 0.001f)
        assertEquals(50f, p.top, 0.001f)
    }

    @Test
    fun `right neighbour's low label intruding moves the label left, not down`() {
        // Label spans 90..110; the right day's "51°" starts at 104 (6 px in), same height.
        val p = place(listOf(DailyRainLabelPlanner.Box(104f, 45f, 150f, 62f)))
        assertEquals("left", p.placement)
        assertEquals(50f, p.top, 0.001f)
        assertTrue("moved left", p.centerX < 100f)
        // 6 px intrusion; a 3 × 8 px sliver is 15 % of the 160 px² label (slight), so 3 px clears.
        assertEquals(97f, p.centerX, 0.001f)
    }

    @Test
    fun `left day's low label and degree sign intruding moves the label right`() {
        val p = place(listOf(DailyRainLabelPlanner.Box(40f, 45f, 96f, 62f)))
        assertEquals("right", p.placement)
        assertTrue(p.centerX > 100f)
        assertEquals(50f, p.top, 0.001f)
    }

    @Test
    fun `both sides blocked - label moves down`() {
        val p = place(
            listOf(
                DailyRainLabelPlanner.Box(40f, 45f, 96f, 60f),
                DailyRainLabelPlanner.Box(104f, 45f, 160f, 60f),
            ),
        )
        assertEquals("down", p.placement)
        assertEquals(100f, p.centerX, 0.001f)
        // Each side intrudes 4 px wide; once the slivers are slight (≤ 15 % of the label area,
        // ~4–6 px down) it stops — well short of clearing the labels' full height (10 px).
        assertTrue("moved down a little, was ${p.top}", p.top > 52f && p.top <= 56f)
    }

    @Test
    fun `a slight touch is accepted at home`() {
        // 1 px into the label's right edge: within the 1.5 dp allowance.
        val p = place(listOf(DailyRainLabelPlanner.Box(109f, 45f, 150f, 62f)))
        assertEquals("home", p.placement)
        assertTrue(p.overlapPx > 0f)
    }

    @Test
    fun `a corner overlap under 15 percent of the label area is accepted`() {
        // 4×3 = 12 px² of a 160 px² label (7.5 %), deeper than 1.5 px on both axes.
        val p = place(listOf(DailyRainLabelPlanner.Box(106f, 55f, 150f, 80f)))
        assertEquals("home", p.placement)
    }

    @Test
    fun `never goes below maxBottom or further sideways than maxShift`() {
        // A wall covering everything reachable: the least-overlap candidate is kept inside the limits.
        val p = place(listOf(DailyRainLabelPlanner.Box(0f, 0f, 1000f, 1000f)), maxBottom = 62f, maxShift = 10f)
        assertTrue(p.top + 8f <= 62f + 0.001f)
        assertTrue(kotlin.math.abs(p.centerX - 100f) <= 10f + 0.001f)
    }

    @Test
    fun `never moves up`() {
        val p = place(listOf(DailyRainLabelPlanner.Box(0f, 0f, 1000f, 56f)))
        assertTrue(p.top >= 50f)
    }

    @Test
    fun `Fold 4 Mon-Tue 20 percent no longer lands right of the gap midpoint`() {
        // Bitmap geometry from the 2026-10-17 Fold 4 logs (density ≈ 2.63 px/dp): Mon/Tue gap midpoint
        // 288.3, "20%" ≈ 15×8 px at top 350.6, Tue's "51°" low label ≈ 290..332 × 347..365, Mon's
        // "54°" ≈ 244..286 × 330..348, Tue's icon ≈ 297..325 × 316..344. The old code drew it at 293.5.
        val p = place(
            obstacles = listOf(
                DailyRainLabelPlanner.Box(290f, 347f, 332f, 365f),
                DailyRainLabelPlanner.Box(244f, 330f, 286f, 348f),
                DailyRainLabelPlanner.Box(297f, 316f, 325f, 344f),
            ),
            homeCenterX = 288.3f,
            width = 15f,
            height = 8f,
            density = 2.63f,
            maxShift = 46f * 0.3f,
            maxBottom = 395f,
            homeTop = 350.6f,
        )
        assertTrue("at or left of the midpoint, was ${p.centerX}", p.centerX <= 288.3f + 0.001f)
    }

    @Test
    fun `lowLabelObstacles splits the degree sign to the top of the box`() {
        val boxes = DailyRainLabelPlanner.lowLabelObstacles("52°", left = 60f, top = 30f, right = 100f, bottom = 50f, degreeWidth = 10f)
        assertEquals(
            listOf(
                DailyRainLabelPlanner.Box(60f, 30f, 90f, 50f),
                DailyRainLabelPlanner.Box(90f, 30f, 100f, 40f, isDegreeSign = true),
            ),
            boxes,
        )
        // No ° (or nothing measured): one full box.
        assertEquals(1, DailyRainLabelPlanner.lowLabelObstacles("52", 60f, 30f, 100f, 50f, 10f).size)
        assertEquals(1, DailyRainLabelPlanner.lowLabelObstacles("52°", 60f, 30f, 100f, 50f, 0f).size)
    }

    @Test
    fun `night label under the blank pocket below a degree sign stays home`() {
        // 2026-10-17 Fold 4, Silurian: Tue's "52°" ends 6 px into the label's left side; the label
        // sits in the lower half, under the °. As one box this pushed "20%" right under Wed's number.
        val lowLabel = DailyRainLabelPlanner.lowLabelObstacles("52°", left = 50f, top = 36f, right = 96f, bottom = 60f, degreeWidth = 10f)
        val p = place(lowLabel)
        assertEquals("home", p.placement)
        assertEquals(100f, p.centerX, 0.001f)
        // The same label against the old single box moves.
        assertTrue(place(listOf(DailyRainLabelPlanner.Box(50f, 36f, 96f, 60f))).placement != "home")
    }

    @Test
    fun `night label hitting the degree sign itself still moves`() {
        // Label top 50 reaches into the ° (36..46 would not; push the ° down so it overlaps).
        val lowLabel = DailyRainLabelPlanner.lowLabelObstacles("52°", left = 50f, top = 44f, right = 96f, bottom = 70f, degreeWidth = 10f)
        val p = place(lowLabel)
        assertTrue("moved off the °, was ${p.placement}", p.placement != "home")
    }

    private val degreeSign = DailyRainLabelPlanner.Box(85f, 40f, 103f, 54f, isDegreeSign = true)

    @Test
    fun `degree sign overlap slight only by area moves when it can`() {
        // Label 80..120 × 50..70: 18×4 px into the °, deeper than 1.5 dp but under 15 % of its area.
        val p = place(listOf(degreeSign), width = 40f, height = 20f)
        assertTrue("moved off the °, was ${p.placement}", p.placement != "home")
    }

    @Test
    fun `degree sign overlap stays when no room elsewhere`() {
        val p = place(listOf(degreeSign), width = 40f, height = 20f, maxBottom = 70f, maxShift = 0f)
        assertEquals("home", p.placement)
    }

    @Test
    fun `sideways cost above one prefers down over a shorter sideways move`() {
        val obstacle = DailyRainLabelPlanner.Box(80f, 40f, 95f, 58f)
        val base = place(listOf(obstacle))
        assertEquals("right", base.placement)
        val roomy = DailyRainLabelPlanner.placeNightLabel(
            homeCenterX = 100f, homeTop = 50f, width = 20f, height = 8f, obstacles = listOf(obstacle),
            maxShiftPx = 15f, maxBottom = 200f, minLeft = 0f, maxRight = 1000f, density = 1f, sidewaysCost = 3f,
        )
        assertEquals("down", roomy.placement)
    }
}

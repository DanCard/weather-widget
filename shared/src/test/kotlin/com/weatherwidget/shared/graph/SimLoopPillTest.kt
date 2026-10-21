package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The "Sim loop" pill goes in the precipitation graph's empty space, wherever that is — and its tap
 * target covers it without reaching the header buttons. See
 * `plans/261020-sim-loop-pill-free-space-placement-and-review-fixes.md`.
 */
@Category(ShortDuration::class)
class SimLoopPillTest {
    private val density = 2f
    private val plot = GraphRect(0f, 88f, 800f, 400f)
    private val pillWidth = SimLoopPill.pillWidthPx(labelWidthPx = 100f, density = density)
    private val pad = SimLoopPill.CLEARANCE_DP * density

    private fun curve(vararg ys: Float): List<Pair<Float, Float>> =
        ys.mapIndexed { i, y -> (plot.width * i / (ys.size - 1)) to y }

    private fun place(
        points: List<Pair<Float, Float>>,
        drawn: List<GraphRect> = emptyList(),
        veto: List<GraphRect> = emptyList(),
        plot: GraphRect = this.plot,
    ) = SimLoopPill.place(plot, points, drawn, veto, pillWidth, density)?.box

    private fun assertClearOf(pill: GraphRect, others: List<GraphRect>) =
        others.forEach { assertFalse("pill $pill overlaps $it", it.intersects(pill)) }

    @Test
    fun `dry day - pill goes right, above the flat line`() {
        val pill = place(curve(400f, 400f, 400f, 400f, 400f))
        assertNotNull(pill)
        assertTrue("prefers the right half: $pill", pill!!.left > plot.width / 2f)
        assertTrue(pill.bottom <= 400f - pad)
        assertEquals(SimLoopPill.HEIGHT_DP * density, pill.height, 0.01f)
    }

    @Test
    fun `rain peaking at the right edge - pill never sits in the fill under the curve`() {
        // Curve climbs from the floor to near the plot top on the right.
        val points = curve(400f, 400f, 390f, 200f, 100f)
        val pill = place(points)
        assertNotNull(pill)
        SimLoopPill.fillColumns(points, plot.bottom).forEach {
            assertFalse("pill $pill in fill $it", it.intersects(pill!!))
        }
        val atLeft = SimLoopPill.curveYAt(points, pill!!.left)!!
        val atRight = SimLoopPill.curveYAt(points, pill.right)!!
        assertTrue("pill is above the curve", pill.bottom < minOf(atLeft, atRight))
    }

    @Test
    fun `labels and the NOW veto are avoided`() {
        val labels = listOf(GraphRect(600f, 88f, 800f, 200f), GraphRect(0f, 88f, 120f, 130f))
        val now = listOf(GraphRect(398f, 120f, 402f, 380f))
        val pill = place(curve(400f, 400f, 400f, 400f, 400f), drawn = labels, veto = now)
        assertNotNull(pill)
        assertClearOf(pill!!, labels + now)
    }

    @Test
    fun `spiky chances with valleys narrower than the pill - pill goes over the curve, still off labels`() {
        // 0/20% alternating every 40px: no valley fits a 236px pill.
        val points = (0..20).map { i -> (i * 40f) to (if (i % 2 == 0) 400f else 100f) }
        val labels = (0..20).filter { it % 2 == 1 }.map { i -> GraphRect(i * 40f - 15f, 80f, i * 40f + 15f, 96f) }
        val placement = SimLoopPill.place(plot, points, labels, emptyList(), pillWidth, density)
        assertNotNull(placement)
        assertTrue(placement!!.overCurve)
        assertClearOf(placement.box, labels)
    }

    @Test
    fun `room above the curve is preferred over the curve`() {
        val placement = SimLoopPill.place(plot, curve(400f, 400f, 400f), emptyList(), emptyList(), pillWidth, density)
        assertFalse(placement!!.overCurve)
    }

    @Test
    fun `no room - no pill`() {
        val full = listOf(GraphRect(0f, 88f, 800f, 400f))
        assertNull(place(curve(400f, 400f, 400f), drawn = full))
    }

    @Test
    fun `plot narrower than the pill - no pill`() {
        val narrow = GraphRect(0f, 88f, pillWidth, 400f)
        assertNull(place(listOf(0f to 400f, pillWidth to 400f), plot = narrow))
    }

    @Test
    fun `touch target is at least 48dp tall and contains the pill`() {
        val pill = GraphRect(500f, 200f, 500f + pillWidth, 244f)
        val touch = SimLoopPill.touchBounds(pill, density, container = GraphRect(0f, 0f, 800f, 400f))
        assertTrue(touch.height >= SimLoopPill.MIN_TOUCH_HEIGHT_DP * density - 0.01f)
        assertTrue(touch.left <= pill.left && touch.right >= pill.right)
        assertTrue(touch.top <= pill.top && touch.bottom >= pill.bottom)
    }

    @Test
    fun `touch target is trimmed off the header buttons but still covers the pill`() {
        // Header buttons end at y = 80; the pill sits just under them.
        val header = GraphRect(600f, -20f, 800f, 80f)
        val pill = GraphRect(650f, 92f, 650f + pillWidth, 136f)
        val touch = SimLoopPill.touchBounds(pill, density, GraphRect(0f, 0f, 800f, 400f), avoid = listOf(header))
        assertFalse("touch $touch reaches the header", touch.intersects(header))
        assertTrue(touch.top <= pill.top && touch.bottom >= pill.bottom)
        assertTrue(touch.left <= pill.left && touch.right >= pill.right)
    }

    @Test
    fun `touch target stays inside the container`() {
        val pill = GraphRect(780f - pillWidth, 2f, 780f, 46f)
        val container = GraphRect(0f, 0f, 800f, 400f)
        val touch = SimLoopPill.touchBounds(pill, density, container)
        assertTrue(touch.top >= 0f && touch.right <= 800f)
    }

    @Test
    fun `radar and sim loop - both placed, never overlapping, radar takes the preferred right slot`() {
        val pills = SimLoopPill.placeRadarAndSimLoop(
            plot, curve(400f, 400f, 400f, 400f, 400f), emptyList(), emptyList(),
            radarWidthPx = pillWidth, simLoopWidthPx = pillWidth, density = density,
        )
        val radar = pills.radar!!.box
        val sim = pills.simLoop!!.box
        assertFalse("radar $radar overlaps sim loop $sim", radar.intersects(sim))
        assertEquals(SimLoopPill.place(plot, curve(400f, 400f, 400f, 400f, 400f), emptyList(), emptyList(), pillWidth, density)!!.box, radar)
    }

    @Test
    fun `room for one pill only - radar is kept, sim loop dropped`() {
        // A plot just big enough for one pill: no room beside it or above it.
        val narrow = GraphRect(0f, 88f, pillWidth + 4 * pad, 88f + 1.8f * SimLoopPill.HEIGHT_DP * density)
        val points = listOf(0f to narrow.bottom, narrow.right to narrow.bottom)
        val pills = SimLoopPill.placeRadarAndSimLoop(narrow, points, emptyList(), emptyList(), pillWidth, pillWidth, density)
        assertNotNull(pills.radar)
        assertNull(pills.simLoop)
    }

    @Test
    fun `sim loop not offered - radar alone, same slot sim loop used to get`() {
        val points = curve(400f, 400f, 400f, 400f, 400f)
        val pills = SimLoopPill.placeRadarAndSimLoop(plot, points, emptyList(), emptyList(), pillWidth, null, density)
        assertNull(pills.simLoop)
        assertEquals(place(points), pills.radar!!.box)
    }
}

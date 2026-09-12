package com.weatherwidget.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.BlendBreakdown
import com.weatherwidget.shared.actuals.BlendContribution
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NowDotStationsPopupTest {

    private val targetMs = 1_780_000_000_000L

    private fun contribution(stationId: String, weightShare: Double) = BlendContribution(
        stationId = stationId,
        stationName = stationId,
        stationType = "OFFICIAL",
        distanceKm = 3f,
        lastReadingMs = targetMs,
        rawTemp = 68f,
        resolvedTemp = 68f,
        sourceKind = "observed",
        ageMs = 0L,
        weight = weightShare,
        weightShare = weightShare,
    )

    private fun breakdown(vararg contributions: BlendContribution) = BlendBreakdown(
        targetMs = targetMs,
        blendedTemp = 68.4f,
        sourceKind = "observed",
        contributions = contributions.toList(),
    )

    private fun obs(
        stationId: String,
        distanceKm: Float = 1f,
        stationType: String = "OFFICIAL",
        timestamp: Long = targetMs,
        temperature: Float = 60f,
        api: String = WeatherSource.NWS.id,
    ) = ObservationReading(
        stationId = stationId,
        stationName = stationId,
        timestamp = timestamp,
        temperature = temperature,
        condition = "Clear",
        locationLat = 37.42,
        locationLon = -122.08,
        distanceKm = distanceKm,
        stationType = stationType,
        api = api,
        fetchedAt = timestamp,
    )

    private fun target(x: Float?, y: Float?, r: Float = 4.5f) = NowDotTarget().apply {
        if (x != null && y != null) set(x, y, r)
    }

    private fun cards(
        observations: List<ObservationReading>,
        breakdowns: List<BlendBreakdown> = listOf(breakdown(contribution("KNUQ", 1.0))),
        maxRows: Int = MAX_POPUP_ROWS,
    ) = nowDotStationCards(breakdowns, observations, WeatherSource.NWS, maxRows = maxRows)

    // ---- hit test ----

    @Test
    fun `pointer inside the dot hits`() {
        val t = target(100f, 50f)
        assertTrue("dead centre", nowDotHitTest(t, Offset(100f, 50f)))
        // The painted dot is only ~4.5px; the slop is what makes it mouse-reachable at all.
        assertTrue("edge of painted dot", nowDotHitTest(t, Offset(104.5f, 50f)))
        assertTrue("within slop", nowDotHitTest(t, Offset(100f, 50f + 4.5f + NOW_DOT_HOVER_SLOP_PX - 0.5f)))
    }

    @Test
    fun `pointer outside the dot misses`() {
        val t = target(100f, 50f)
        assertFalse(nowDotHitTest(t, Offset(100f, 50f + 4.5f + NOW_DOT_HOVER_SLOP_PX + 1f)))
        assertFalse(nowDotHitTest(t, Offset(300f, 200f)))
    }

    /** Panned off-window, or nothing drawn yet: hovering empty space must never open the overlay. */
    @Test
    fun `a target with no centre never hits`() {
        assertFalse(nowDotHitTest(target(null, null), Offset(0f, 0f)))
        assertFalse(nowDotHitTest(NowDotTarget(), Offset(100f, 50f)))
    }

    // ---- cards ----

    /**
     * The overlay is the Observations tab's default view, so its rows must be the tab's rows: the
     * same [visibleStationRows] selection (newest per station, nearest first, synthetic rows out).
     * If the two ever used different filters they could list different stations for the same dot.
     */
    @Test
    fun `cards are the Observations tab's own row selection`() {
        val input = listOf(
            obs("KSJC", distanceKm = 20f),
            obs("KPAO", distanceKm = 6f, timestamp = targetMs - 60_000, temperature = 55f),
            obs("KPAO", distanceKm = 6f, timestamp = targetMs, temperature = 56f),
            obs("NWS_BLEND", distanceKm = 0f),
            obs("KNUQ", distanceKm = 4f),
        )
        val c = cards(input, maxRows = 10)!!
        assertEquals(visibleStationRows(input, WeatherSource.NWS), c.shown)
        assertEquals(listOf("KNUQ", "KPAO", "KSJC"), c.shown.map { it.stationId })
        assertEquals(56f, c.shown[1].temperature, 0.001f)
    }

    /** Personal stations are the majority near a city; the glance shows the official ones. */
    @Test
    fun `personal stations are dropped when an official station is present`() {
        val c = cards(
            listOf(
                obs("AW020", distanceKm = 2f, stationType = "PERSONAL"),
                obs("KNUQ", distanceKm = 4f),
                obs("LOAC1", distanceKm = 8f, stationType = "PERSONAL"),
                obs("KSJC", distanceKm = 16f),
            ),
            breakdowns = listOf(
                breakdown(
                    contribution("AW020", 0.1), contribution("KNUQ", 0.6),
                    contribution("LOAC1", 0.05), contribution("KSJC", 0.25),
                ),
            ),
            maxRows = 10,
        )!!
        assertTrue(c.officialOnly)
        assertEquals(listOf("KNUQ", "KSJC"), c.shown.map { it.stationId })
    }

    /** PWS-only country: showing every row beats showing nothing. */
    @Test
    fun `falls back to all stations when none is official`() {
        val c = cards(
            listOf(
                obs("AW020", distanceKm = 2f, stationType = "PERSONAL"),
                obs("LOAC1", distanceKm = 8f, stationType = "PERSONAL"),
            ),
            maxRows = 10,
        )!!
        assertFalse(c.officialOnly)
        assertEquals(listOf("AW020", "LOAC1"), c.shown.map { it.stationId })
    }

    /** The glance is the single nearest official station. */
    @Test
    fun `rows are capped to the nearest one and the remainder is reported`() {
        val many = (1..10).map { obs("K$it", distanceKm = it.toFloat()) }.shuffled()
        val c = cards(many)!!
        assertEquals(1, MAX_POPUP_ROWS)
        assertEquals(listOf("K1"), c.shown.map { it.stationId })
        assertEquals(9, c.remaining)
        assertEquals(0, cards(many.take(1))!!.remaining)
    }

    /** Nothing to say means draw nothing — not an empty frame. */
    @Test
    fun `no breakdown, no contributions or no stations yields no cards`() {
        assertNull(cards(listOf(obs("KNUQ")), breakdowns = emptyList()))
        assertNull(cards(listOf(obs("KNUQ")), breakdowns = listOf(breakdown())))
        assertNull(cards(emptyList()))
        // Only synthetic rows: nothing a card could name.
        assertNull(cards(listOf(obs("NWS_BLEND"))))
    }

    // ---- positioner ----

    private val graph = IntSize(1127, 680)
    private val popup = IntSize(500, 450)
    private fun anchor(cx: Int, cy: Int, r: Int = 10) = IntRect(cx - r, cy - r, cx + r, cy + r)

    private fun IntOffset.rect(size: IntSize) = IntRect(x, y, x + size.width, y + size.height)

    @Test
    fun `sits to the right of the dot when it fits`() {
        val a = anchor(300, 200)
        val pos = NowDotPopupPositioner.calculate(a, graph, popup)
        assertEquals(a.right + 12, pos.x)
        assertEquals(a.top - 10, pos.y)
    }

    @Test
    fun `flips to the left when the right edge would overflow`() {
        val a = anchor(800, 200)
        val pos = NowDotPopupPositioner.calculate(a, graph, popup)
        assertEquals(a.left - 12 - popup.width, pos.x)
    }

    @Test
    fun `is pulled up to stay inside the graph and never above the top`() {
        val low = NowDotPopupPositioner.calculate(anchor(300, 650), graph, popup)
        assertEquals(graph.height - popup.height, low.y)
        assertEquals(0, NowDotPopupPositioner.calculate(anchor(300, 3), graph, popup).y)
        // Taller than the graph: pin to the top rather than go negative.
        assertEquals(0, NowDotPopupPositioner.calculate(anchor(300, 400), graph, IntSize(500, 900)).y)
    }

    /**
     * At default zoom the dot sits near the centre, leaving under half the graph on either side. The
     * preferred width is capped to the roomier side so the overlay can always sit beside the dot;
     * a wide graph or an off-centre dot gets the full preferred width.
     */
    @Test
    fun `preferred width is capped to the roomier side of the dot`() {
        val centred = anchor(590, 400)
        // Left room 580 - 12 = 568 beats right room 1127 - 600 - 12 = 515.
        assertEquals(568, NowDotPopupPositioner.fitWidth(centred, graph.width, 813))
        assertEquals(400, NowDotPopupPositioner.fitWidth(centred, graph.width, 400))
        // Dot near the left edge: the right side has the room, and all of the preferred width fits.
        assertEquals(813, NowDotPopupPositioner.fitWidth(anchor(100, 400), graph.width, 813))
        // Dot near the right edge: the left side is the roomier one.
        assertEquals(1000 - 12, NowDotPopupPositioner.fitWidth(anchor(1010, 400), graph.width, 2000))
    }

    /**
     * The overlay closes when the pointer leaves the dot, so it must never be placed under the
     * pointer: a popup covering the dot would dismiss itself the moment it appeared. Measured at
     * [NowDotPopupPositioner.fitWidth], as the real layout does, it fits beside the dot everywhere.
     */
    @Test
    fun `never covers the dot`() {
        for (cx in listOf(20, 300, 563, 590, 800, 1110)) {
            for (cy in listOf(3, 200, 400, 670)) {
                val a = anchor(cx, cy)
                val size = IntSize(NowDotPopupPositioner.fitWidth(a, graph.width, 813), 450)
                val r = NowDotPopupPositioner.calculate(a, graph, size).rect(size)
                assertFalse("anchor $a inside popup $r", r.overlaps(a))
                assertTrue("popup $r inside graph", r.left >= 0 && r.right <= graph.width)
            }
        }
    }
}

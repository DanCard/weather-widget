package com.weatherwidget.desktop

import androidx.compose.ui.geometry.Offset
import com.weatherwidget.shared.actuals.BlendBreakdown
import com.weatherwidget.shared.actuals.BlendContribution
import com.weatherwidget.shared.actuals.BlendTableFormatter
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZoneId

@Category(ShortDuration::class)
class NowDotStationsPopupTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val targetMs = 1_780_000_000_000L

    private fun contribution(
        stationId: String,
        weightShare: Double,
        temperature: Float = 68f,
    ) = BlendContribution(
        stationId = stationId,
        stationName = stationId,
        stationType = "OFFICIAL",
        distanceKm = 3f,
        lastReadingMs = targetMs,
        rawTemp = temperature,
        resolvedTemp = temperature,
        sourceKind = "observed",
        ageMs = 0L,
        weight = weightShare,
        weightShare = weightShare,
    )

    private fun breakdown(vararg contributions: BlendContribution) = BlendBreakdown(
        targetMs = targetMs,
        blendedTemp = 68f,
        sourceKind = "observed",
        contributions = contributions.toList(),
    )

    private fun target(x: Float?, y: Float?, r: Float = 4.5f) = NowDotTarget().apply {
        if (x != null && y != null) set(x, y, r, 800f)
    }

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

    /**
     * The overlay must never derive its own numbers — it is a view over [BlendTableFormatter], the
     * same formatter behind the Stations window's Blend tab. If these drift the two surfaces can
     * disagree about what the blend did, which is the one thing both exist to be trusted about.
     */
    @Test
    fun `rows come from the shared formatter, not a local derivation`() {
        val b = breakdown(contribution("KNUQ", 0.6), contribution("KPAO", 0.4))
        val table = nowDotStationsTable(listOf(b), useCelsius = false, zoneId = zone)

        assertNotNull(table)
        assertEquals(BlendTableFormatter.format(b, false, zone).rows, table!!.rows)
        assertEquals(2, table.stationCount)
    }

    @Test
    fun `the newest breakdown wins when several are captured`() {
        val newest = breakdown(contribution("NEWEST", 1.0))
        val older = breakdown(contribution("OLDER", 1.0))
        val table = nowDotStationsTable(listOf(newest, older), useCelsius = false, zoneId = zone)

        assertEquals(listOf("NEWEST"), table!!.rows.map { it.station })
    }

    @Test
    fun `contributor list is capped so the overlay stays a glance`() {
        val many = (1..20).map { contribution("ST$it", 1.0 / 20) }
        val table = nowDotStationsTable(listOf(breakdown(*many.toTypedArray())), useCelsius = false, zoneId = zone)

        assertEquals(MAX_POPUP_ROWS, table!!.rows.size)
        // The count still reports the truth, even though the rows are trimmed.
        assertEquals(20, table.stationCount)
    }

    /** Nothing to say means draw nothing — not an empty frame. */
    @Test
    fun `no breakdown or no contributions yields no table`() {
        assertNull(nowDotStationsTable(emptyList(), useCelsius = false, zoneId = zone))
        assertNull(nowDotStationsTable(listOf(breakdown()), useCelsius = false, zoneId = zone))
    }

    /**
     * Why the overlay carries BOTH temperature columns. A stale station is carried forward by the
     * forecast's change over the gap, so what it measured and what the blend used are different
     * numbers — and the gap is pure forecast. Showing only the fed value would let the overlay imply
     * a thermometer read something it never did.
     */
    @Test
    fun `raw and fed-to-blend are distinct for an extrapolated station`() {
        val extrapolated = BlendContribution(
            stationId = "KNUQ",
            stationName = "KNUQ",
            stationType = "OFFICIAL",
            distanceKm = 3f,
            lastReadingMs = targetMs - 40 * 60_000L,
            rawTemp = 64f,
            resolvedTemp = 68.4f,
            sourceKind = "forecast_extrapolated",
            ageMs = 40 * 60_000L,
            weight = 1.0,
            weightShare = 1.0,
        )
        val row = nowDotStationsTable(
            listOf(breakdown(extrapolated)), useCelsius = false, zoneId = zone,
        )!!.rows.single()

        assertEquals("64.0", row.raw)
        assertTrue("fed value carries the kind code", row.valueFedToBlend.startsWith("68.40"))
        assertTrue("must be flagged so the overlay can tint it", row.isExtrapolated)
    }

    /** An ordinary measured station shows the same number in both columns, untinted. */
    @Test
    fun `raw and fed-to-blend agree for an observed station`() {
        val row = nowDotStationsTable(
            listOf(breakdown(contribution("KNUQ", 1.0, temperature = 68f))),
            useCelsius = false,
            zoneId = zone,
        )!!.rows.single()

        assertEquals("68.0", row.raw)
        assertTrue(row.valueFedToBlend.startsWith("68.00"))
        assertFalse(row.isExtrapolated)
    }
}

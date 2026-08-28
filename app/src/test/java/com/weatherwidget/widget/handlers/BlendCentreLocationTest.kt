package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ObservationSiteMerge
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The rung order in [BlendCentre.resolve], and the divergence signal built on it.
 *
 * Coordinates throughout are the two device sites from the 2026-08-28 Samsung Fold report: the
 * configured (Sunnyvale) site the device had moved to, and the (Mountain View) fragment it had left,
 * frozen three hours earlier. They are 0.011° apart in latitude and 0.068° in longitude — far outside
 * [ObservationSiteMerge.MERGE_TOLERANCE_DEG], which is what made centring on the wrong one exclude
 * every fresh observation instead of merely down-weighting it.
 */
@Category(ShortDuration::class)
class BlendCentreLocationTest {

    private val configured = 37.4064254 to -122.0206146
    private val staleFragment = 37.417 to -122.089

    @Test
    fun `moved device blends at the configured location, not the row it happens to hold`() {
        val centre = BlendCentre.resolve(configured, staleFragment)

        assertEquals(BlendCentre.Source.CONFIGURED, centre.source)
        assertEquals(configured.first, centre.lat, 0.0)
        assertEquals(configured.second, centre.lon, 0.0)
    }

    @Test
    fun `data row is the fallback when there is no configured location`() {
        val centre = BlendCentre.resolve(null, staleFragment)

        assertEquals(BlendCentre.Source.DATA, centre.source)
        assertEquals(staleFragment.first, centre.lat, 0.0)
        assertEquals(staleFragment.second, centre.lon, 0.0)
    }

    @Test
    fun `no location anywhere degrades to NaN, never to a stand-in coordinate`() {
        val centre = BlendCentre.resolve(null, null)

        assertEquals(BlendCentre.Source.NONE, centre.source)
        assertTrue("lat must be NaN, not a coordinate", centre.lat.isNaN())
        assertTrue("lon must be NaN, not a coordinate", centre.lon.isNaN())
    }

    @Test
    fun `agreeing locations are unchanged`() {
        val quantizedSameSite = 37.406 to -122.021

        val centre = BlendCentre.resolve(configured, quantizedSameSite)

        assertEquals(BlendCentre.Source.CONFIGURED, centre.source)
        assertEquals(configured.first, centre.lat, 0.0)
    }

    @Test
    fun `a non-finite configured location counts as absent`() {
        val centre = BlendCentre.resolve(Double.NaN to Double.NaN, staleFragment)

        assertEquals(BlendCentre.Source.DATA, centre.source)
        assertEquals(staleFragment.first, centre.lat, 0.0)
    }

    @Test
    fun `divergence fires when the rows were fetched outside the merge box`() {
        assertTrue(BlendCentre.divergesBeyondMergeTolerance(configured, staleFragment))
    }

    @Test
    fun `divergence stays quiet for quantization jitter at the same site`() {
        assertFalse(BlendCentre.divergesBeyondMergeTolerance(configured, 37.406 to -122.021))
    }

    @Test
    fun `divergence needs both coordinates to be known`() {
        assertFalse(BlendCentre.divergesBeyondMergeTolerance(null, staleFragment))
        assertFalse(BlendCentre.divergesBeyondMergeTolerance(configured, null))
    }

    /**
     * Guards the premise the whole fix rests on: the two sites are outside the merge box, so the
     * centre — not the tolerance — is the knob. If [ObservationSiteMerge.MERGE_TOLERANCE_DEG] were
     * ever widened past this gap, centring on the stale fragment would stop excluding fresh rows and
     * this plan's reasoning would need revisiting.
     */
    @Test
    fun `the two sites really are outside the merge box`() {
        val deltaLon = kotlin.math.abs(configured.second - staleFragment.second)

        assertTrue(
            "sites are $deltaLon apart; merge box is ${ObservationSiteMerge.MERGE_TOLERANCE_DEG}",
            deltaLon > ObservationSiteMerge.MERGE_TOLERANCE_DEG,
        )
    }
}

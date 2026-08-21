package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MetarSkyCoverTest {

    private fun layer(amount: String, baseMeters: Double? = null) =
        NwsApi.CloudLayer(amount = amount, baseMeters = baseMeters)

    @Test
    fun `every recognised amount maps to its WMO okta midpoint`() {
        val expected = mapOf(
            "CLR" to 0, "SKC" to 0, "NCD" to 0, "CAVOK" to 0,
            "FEW" to 19, "SCT" to 44, "BKN" to 75, "OVC" to 100,
            "VV" to 100,
        )
        for ((amount, pct) in expected) {
            assertEquals("amount=$amount", pct, MetarSkyCover.totalPercent(listOf(layer(amount, 1000.0))))
        }
    }

    @Test
    fun `empty layer list means not reported, never clear`() {
        assertNull(MetarSkyCover.totalPercent(emptyList()))
        assertNull(MetarSkyCover.lowPercent(emptyList()))
    }

    @Test
    fun `CLR at the ceilometer limit reads zero for both total and low`() {
        // KSJC 2026-08-20: the 3810 m base on a CLR report is the sensor's detection ceiling,
        // not a cloud. Keying percent on the base would read this clear sky as cloud at 3810 m,
        // and excluding the layer from the low read would leave it "unknown".
        val layers = listOf(layer("CLR", baseMeters = 3810.0))
        assertEquals(0, MetarSkyCover.totalPercent(layers))
        assertEquals(0, MetarSkyCover.lowPercent(layers))
    }

    @Test
    fun `CLR with a null base reads zero for both`() {
        // KNUQ 2026-08-20: "base": {"unitCode": "wmoUnit:m", "value": null}, "amount": "CLR".
        val layers = listOf(layer("CLR", baseMeters = null))
        assertEquals(0, MetarSkyCover.totalPercent(layers))
        assertEquals(0, MetarSkyCover.lowPercent(layers))
    }

    @Test
    fun `amounts are cumulative so the report is the maximum, not a sum`() {
        // FEW010 SCT020 BKN040 — all below 2000 m, so both reads are BKN.
        val layers = listOf(
            layer("FEW", baseMeters = 304.8),
            layer("SCT", baseMeters = 609.6),
            layer("BKN", baseMeters = 1219.2),
        )
        assertEquals(75, MetarSkyCover.totalPercent(layers))
        assertEquals(75, MetarSkyCover.lowPercent(layers))
    }

    @Test
    fun `a layer above the low ceiling counts in the total but not the low`() {
        val layers = listOf(layer("SCT", baseMeters = 3_000.0))
        assertEquals(44, MetarSkyCover.totalPercent(layers))
        assertNull(MetarSkyCover.lowPercent(layers))
    }

    @Test
    fun `low takes the maximum among below-ceiling layers while total takes all layers`() {
        val layers = listOf(
            layer("BKN", baseMeters = 500.0),
            layer("SCT", baseMeters = 3_000.0),
        )
        assertEquals(75, MetarSkyCover.totalPercent(layers))
        assertEquals(75, MetarSkyCover.lowPercent(layers))
    }

    @Test
    fun `a layer with unknown base joins the low layer rather than hiding possible low cloud`() {
        val layers = listOf(layer("BKN", baseMeters = null))
        assertEquals(75, MetarSkyCover.totalPercent(layers))
        assertEquals(75, MetarSkyCover.lowPercent(layers))
    }

    @Test
    fun `an unrecognised amount nulls the whole report instead of reading as clear`() {
        assertNull(MetarSkyCover.totalPercent(listOf(layer("XYZ"))))
        assertNull(MetarSkyCover.lowPercent(listOf(layer("XYZ"))))
        // Even alongside a recognised layer: dropping just the unknown layer could understate an
        // overcast, so the report as a whole is filed as unknown.
        val mixed = listOf(layer("FEW", baseMeters = 500.0), layer("XYZ", baseMeters = 500.0))
        assertNull(MetarSkyCover.totalPercent(mixed))
        assertNull(MetarSkyCover.lowPercent(mixed))
    }

    @Test
    fun `VV means sky obscured and reads as full cover`() {
        assertEquals(100, MetarSkyCover.totalPercent(listOf(layer("VV", baseMeters = 100.0))))
        assertEquals(100, MetarSkyCover.lowPercent(listOf(layer("VV", baseMeters = 100.0))))
    }
}

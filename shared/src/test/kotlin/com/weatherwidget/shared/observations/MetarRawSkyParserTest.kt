package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class MetarRawSkyParserTest {

    // Real KNUQ reports captured from Synoptic on 2026-08-21 — the station whose sky condition was
    // being discarded.
    private val knuqOvercast =
        "KNUQ 211435Z AUTO 35003KT 10SM OVC012 16/13 A3005 RMK AO2 SLP176 T01610128"
    private val knuqBroken =
        "KNUQ 211715Z AUTO 36008KT 10SM BKN015 18/13 A3007 RMK AO2"

    @Test
    fun `single overcast layer carries amount and height in metres`() {
        val layers = MetarRawSkyParser.layersFrom(knuqOvercast)
        assertEquals(1, layers.size)
        assertEquals("OVC", layers[0].amount)
        // 1,200 ft — matches Synoptic's own ceiling_set_1 of 365.76 m for this report.
        assertEquals(365.76, layers[0].baseMeters!!, 0.01)
    }

    @Test
    fun `broken layer parses and reads as BKN through MetarSkyCover`() {
        val layers = MetarRawSkyParser.layersFrom(knuqBroken)
        assertEquals(listOf("BKN"), layers.map { it.amount })
        assertEquals(457.2, layers[0].baseMeters!!, 0.01)
        assertEquals(75, MetarSkyCover.lowPercent(layers))
    }

    @Test
    fun `stacked layers keep report order and their own heights`() {
        val layers = MetarRawSkyParser.layersFrom("KSJC 211453Z 09003KT 10SM FEW010 SCT020 BKN040 19/12 A3006")
        assertEquals(listOf("FEW", "SCT", "BKN"), layers.map { it.amount })
        assertEquals(304.8, layers[0].baseMeters!!, 0.01)
        assertEquals(609.6, layers[1].baseMeters!!, 0.01)
        assertEquals(1219.2, layers[2].baseMeters!!, 0.01)
        // METAR amounts are cumulative, so the report reads as its maximum.
        assertEquals(75, MetarSkyCover.totalPercent(layers))
    }

    /**
     * The remarks section is free-form and routinely contains sky-like tokens. Without the ` RMK `
     * cut this report parses as two layers and invents cloud at 99,900 ft that nobody observed.
     */
    @Test
    fun `remarks are not read as layers`() {
        val layers = MetarRawSkyParser.layersFrom(
            "KNUQ 211435Z AUTO 35003KT 10SM OVC012 16/13 A3005 RMK AO2 SLP176 BKN999",
        )
        assertEquals("remarks must not contribute layers", 1, layers.size)
        assertEquals("OVC", layers[0].amount)
    }

    @Test
    fun `clear codes yield one height-less clear layer`() {
        for (code in listOf("CLR", "SKC", "NCD", "CAVOK")) {
            val layers = MetarRawSkyParser.layersFrom("KPAO 211453Z 00000KT 10SM $code 18/12 A3006")
            assertEquals("code=$code", listOf(code), layers.map { it.amount })
            assertNull("code=$code base", layers[0].baseMeters)
            assertEquals("code=$code percent", 0, MetarSkyCover.totalPercent(layers))
        }
    }

    @Test
    fun `vertical visibility is a layer at its own height`() {
        val layers = MetarRawSkyParser.layersFrom("KSJC 211453Z 09003KT 1/4SM FG VV002 12/12 A3006")
        assertEquals(listOf("VV"), layers.map { it.amount })
        assertEquals(60.96, layers[0].baseMeters!!, 0.01)
        // Sky obscured reads as fully covered.
        assertEquals(100, MetarSkyCover.totalPercent(layers))
    }

    /**
     * An unreadable height keeps the layer with an unknown base. Dropping it would hide real low
     * cloud, which is the trap [MetarSkyCover.lowPercent] documents on its own side of the boundary.
     */
    @Test
    fun `unreadable height keeps the layer with a null base`() {
        val layers = MetarRawSkyParser.layersFrom("KNUQ 211435Z AUTO 35003KT 10SM BKN/// 16/13 A3005")
        assertEquals(listOf("BKN"), layers.map { it.amount })
        assertNull(layers[0].baseMeters)
        // Unknown height still enters the low read.
        assertEquals(75, MetarSkyCover.lowPercent(layers))
    }

    @Test
    fun `missing and blank reports are not reported rather than clear`() {
        // "not reported" must stay distinguishable from "clear" — the whole point of the null
        // return in MetarSkyCover.
        for (raw in listOf(null, "", "   ", "M")) {
            val layers = MetarRawSkyParser.layersFrom(raw)
            assertTrue("raw=$raw", layers.isEmpty())
            assertNull("raw=$raw percent", MetarSkyCover.totalPercent(layers))
        }
    }

    @Test
    fun `a report with no sky group at all yields no layers`() {
        // Mesonet-style report body with temperature and wind but no sky condition.
        val layers = MetarRawSkyParser.layersFrom("AW020 211435Z AUTO 35003KT 16/13 A3005")
        assertTrue(layers.isEmpty())
    }

    @Test
    fun `a layer group beats a clear code in the same report`() {
        // CAVOK alongside a real layer would be contradictory; the measured layer wins.
        val layers = MetarRawSkyParser.layersFrom("KNUQ 211435Z AUTO 35003KT CAVOK OVC012 16/13")
        assertEquals(listOf("OVC"), layers.map { it.amount })
    }

    @Test
    fun `tokens that merely contain a sky code are not layers`() {
        // Guards the word boundaries: no bare FEW/SCT/BKN/OVC substring should match.
        val layers = MetarRawSkyParser.layersFrom("KNUQ 211435Z AUTO XOVC012Y 16/13 A3005")
        assertTrue(layers.isEmpty())
    }
}

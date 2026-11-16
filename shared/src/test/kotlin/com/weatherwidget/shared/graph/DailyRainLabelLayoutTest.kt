package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyRainLabelLayoutTest {

    /** Every label measures 10 px per character × its font scale, 14 px tall, baseline 11 px down. */
    private val host = DailyRainLabelLayout.Host { text, _, fontScale ->
        DailyRainLabelLayout.TextSize(width = text.length * 10f * fontScale, height = 14f, baseline = 11f)
    }

    private fun day(
        highLabelTop: Float?,
        ceiling: Float = 0f,
        peakText: String? = null,
        rawHighLabelTop: Float? = highLabelTop,
        probability: Int = 40,
        daysFromToday: Int = 0,
        peakCeiling: Float = ceiling,
        rainText: String = "$probability%",
    ) = DailyRainLabelLayout.layoutDay(
        host,
        DailyRainLabelLayout.DayInput(
            date = "d", rainText = rainText, peakText = peakText, probability = probability, isPast = false,
            daysFromToday = daysFromToday, centerX = 50f, highLabelTop = highLabelTop, snapshotBarTop = null,
            rawHighLabelTop = rawHighLabelTop, ceiling = ceiling, peakCeiling = peakCeiling, pxPerDp = 1f,
        ),
        "test",
    )

    @Test
    fun `a day amount is laid out 15 percent smaller than a chance`() {
        // emulator-5556, 2026-11-15: Wed ".91in" and Thu ".29in" ran together at the chance's size.
        val chance = day(highLabelTop = 100f, probability = 100)!!
        val amount = day(highLabelTop = 100f, probability = 100, rainText = ".91in")!!
        println("day chance scale=${chance.rain.fontScale} amount scale=${amount.rain.fontScale}")
        assertEquals(DailyRainLabels.DAY_AMOUNT_FONT_SCALE, amount.rain.fontScale / chance.rain.fontScale, 0.001f)
    }

    @Test
    fun `day label tucks onto the high label`() {
        val labels = day(highLabelTop = 100f)!!
        // bottom = high top − RAIN_HIGH_TEMP_GAP_DP (−3 dp → 3 px into the high label).
        assertEquals(100f - DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP, labels.rain.bottom, 0.01f)
        assertEquals(labels.rain.top, labels.rawTop, 0.01f)
    }

    @Test
    fun `day label clamps to the ceiling and reports its unclamped top`() {
        val labels = day(highLabelTop = 10f, ceiling = 5f)!!
        assertEquals(5f, labels.rain.top, 0.01f)
        assertTrue("rawTop=${labels.rawTop}", labels.rawTop < 5f)
    }

    @Test
    fun `raw top follows the unclamped high label`() {
        val labels = day(highLabelTop = 20f, rawHighLabelTop = -30f, ceiling = 0f)!!
        assertEquals(-30f - DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP - 14f, labels.rawTop, 0.01f)
    }

    @Test
    fun `peak sits on the chance label and is dropped at the ceiling`() {
        val fits = day(highLabelTop = 100f, peakText = "1pm")!!
        val peak = checkNotNull(fits.peak)
        assertEquals(fits.rain.top - DailyRainLabels.RAIN_PEAK_GAP_DP, peak.bottom, 0.01f)
        assertTrue("rawTop includes the peak", fits.rawTop <= peak.top)

        val tooHigh = day(highLabelTop = 30f, peakText = "1pm", ceiling = 10f)!!
        assertNull(tooHigh.peak)
        assertTrue("the chance label stays put", tooHigh.rain.top >= 10f)
    }

    @Test
    fun `peak may overlap the header up to the drawable top`() {
        // Fold 4, 2026-11-04 (WAPI, Thu 11%): the graph top was solved so the peak ended exactly on
        // the header ceiling, and the strict check dropped it on a float sliver. The peak may now
        // rise into the header; only the drawable top (Android bitmap top) drops it.
        val ceiling = 20f
        val atTie = checkNotNull(day(highLabelTop = 40f, peakText = "7pm", ceiling = ceiling, peakCeiling = 0f))
        val peak = checkNotNull(atTie.peak) { "peak dropped although it is inside the bitmap" }
        println("peak over header: rainTop=${atTie.rain.top} peakTop=${peak.top} headerCeiling=$ceiling")
        assertTrue("the peak reaches into the header", peak.top < ceiling)
        assertTrue("the chance label still keeps to the header ceiling", atTie.rain.top >= ceiling)

        // The % clamps to a ceiling of 5, so the peak (14 tall, tucked 2) would start at −7.
        val offBitmap = checkNotNull(day(highLabelTop = 10f, peakText = "7pm", ceiling = 5f, peakCeiling = 0f))
        assertNull("a peak above the bitmap top is still dropped", offBitmap.peak)
    }

    @Test
    fun `peak hour font is at least 15 percent smaller than a chance of 30 or less`() {
        // User, 2026-11-04: with the threshold lowered to > 10 %, the hour must not compete with a small %.
        for (probability in 11..30) for (daysFromToday in 0..7) {
            val labels = checkNotNull(day(highLabelTop = 200f, peakText = "1pm", probability = probability, daysFromToday = daysFromToday))
            val peak = checkNotNull(labels.peak) { "peak dropped at $probability% day+$daysFromToday" }
            val ratio = peak.fontScale / labels.rain.fontScale
            println("peak font ratio: $probability% day+$daysFromToday rain=${labels.rain.fontScale} peak=${peak.fontScale} ratio=$ratio")
            assertTrue("$probability% day+$daysFromToday ratio=$ratio", ratio <= 0.85f + 1e-4f)
        }
    }

    @Test
    fun `no high label means no day label`() {
        assertNull(day(highLabelTop = null))
    }
}

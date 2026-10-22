package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Contention between the header date and the "from yest" caption.
 *
 * The date outranks the caption (`HeaderFit`; user, 2026-10-22). The per-render alternation
 * between the two is gone: the shared fit decides, and this is the painted geometry's safety net
 * ([DailyForecastHeaderRenderer.resolveHeaderContention]).
 */
@Category(ShortDuration::class)
class DailyForecastHeaderDeltaLabelTest {

    private fun contention(
        hasDateText: Boolean,
        dateFitsWithLabel: Boolean,
        dateFitsWithoutLabel: Boolean,
        labelFitsAlone: Boolean,
    ) = DailyForecastHeaderRenderer.resolveHeaderContention(
        hasDateText = hasDateText,
        dateFitsWithLabel = dateFitsWithLabel,
        dateFitsWithoutLabel = dateFitsWithoutLabel,
        labelFitsAlone = labelFitsAlone,
    )

    // ---- date first, then the caption alone ----

    @Test
    fun `label drawn when date still fits with it`() {
        val result = contention(
            hasDateText = true,
            dateFitsWithLabel = true,
            dateFitsWithoutLabel = true,
            labelFitsAlone = false, // api boundary violated, but the date placement already fits
        )
        assertTrue(result.showDeltaLabel)
        assertTrue(result.showDate)
    }

    @Test
    fun `label hidden when it would crowd out the date`() {
        val result = contention(
            hasDateText = true,
            dateFitsWithLabel = false,
            dateFitsWithoutLabel = true,
            labelFitsAlone = true,
        )
        assertFalse(result.showDeltaLabel)
        assertTrue(result.showDate)
    }

    @Test
    fun `label drawn when date fits neither way and cluster clears api label`() {
        val result = contention(
            hasDateText = true,
            dateFitsWithLabel = false,
            dateFitsWithoutLabel = false,
            labelFitsAlone = true,
        )
        assertTrue(result.showDeltaLabel)
        assertFalse(result.showDate)
    }

    @Test
    fun `label hidden when date fits neither way and cluster hits api label`() {
        val result = contention(
            hasDateText = true,
            dateFitsWithLabel = false,
            dateFitsWithoutLabel = false,
            labelFitsAlone = false,
        )
        assertFalse(result.showDeltaLabel)
        assertFalse(result.showDate)
    }

    @Test
    fun `label drawn without date when cluster clears api label`() {
        val result = contention(
            hasDateText = false,
            dateFitsWithLabel = false,
            dateFitsWithoutLabel = false,
            labelFitsAlone = true,
        )
        assertTrue(result.showDeltaLabel)
        assertFalse(result.showDate)
    }

    @Test
    fun `label hidden without date when cluster hits api label`() {
        val result = contention(
            hasDateText = false,
            dateFitsWithLabel = false,
            dateFitsWithoutLabel = false,
            labelFitsAlone = false,
        )
        assertFalse(result.showDeltaLabel)
        assertFalse(result.showDate)
    }

    // ---- invariants ----

    @Test
    fun `the date never loses to the caption`() {
        // Over the whole truth table: whenever the date fits on its own it is shown, and both are
        // kept only when the date fits beside the caption.
        for (hasDate in listOf(true, false)) {
            for (withLabel in listOf(true, false)) {
                for (withoutLabel in listOf(true, false)) {
                    for (labelAlone in listOf(true, false)) {
                        val r = contention(hasDate, withLabel, withoutLabel, labelAlone)
                        val case = "hasDate=$hasDate withLabel=$withLabel withoutLabel=$withoutLabel labelAlone=$labelAlone"
                        if (hasDate && (withLabel || withoutLabel)) assertTrue(case, r.showDate)
                        assertEquals(case, hasDate && withLabel, r.showDeltaLabel && r.showDate)
                    }
                }
            }
        }
    }
}

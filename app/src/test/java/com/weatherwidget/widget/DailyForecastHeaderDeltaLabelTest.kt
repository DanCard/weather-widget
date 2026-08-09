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
 * The first six cases are the original fixed-priority tests, re-expressed against
 * [DailyForecastHeaderRenderer.resolveHeaderContention] with `preferDateOverLabel = true` — they
 * pin the historical behaviour so the alternation cannot silently change the default.
 */
@Category(ShortDuration::class)
class DailyForecastHeaderDeltaLabelTest {

    private fun contention(
        hasDateText: Boolean,
        dateFitsWithLabel: Boolean,
        dateFitsWithoutLabel: Boolean,
        labelFitsAlone: Boolean,
        preferDateOverLabel: Boolean = true,
    ) = DailyForecastHeaderRenderer.resolveHeaderContention(
        hasDateText = hasDateText,
        dateFitsWithLabel = dateFitsWithLabel,
        dateFitsWithoutLabel = dateFitsWithoutLabel,
        labelFitsAlone = labelFitsAlone,
        preferDateOverLabel = preferDateOverLabel,
    )

    // ---- historical fixed-priority behaviour (preferDateOverLabel = true) ----

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

    // ---- alternation ----

    @Test
    fun `caption wins and date is dropped when the swap favours the caption`() {
        // Same inputs as "label hidden when it would crowd out the date", flipped preference.
        // Previously the caption was the structural permanent loser here.
        val result = contention(
            hasDateText = true,
            dateFitsWithLabel = false,
            dateFitsWithoutLabel = true,
            labelFitsAlone = true,
            preferDateOverLabel = false,
        )
        assertTrue(result.showDeltaLabel)
        assertFalse(result.showDate)
    }

    @Test
    fun `swap falls back to the date when the caption cannot fit alone`() {
        // The alternation must never blank a slot that could have been filled: the caption is
        // preferred, does not fit, so the date takes the row rather than both being dropped.
        val result = contention(
            hasDateText = true,
            dateFitsWithLabel = false,
            dateFitsWithoutLabel = true,
            labelFitsAlone = false,
            preferDateOverLabel = false,
        )
        assertFalse(result.showDeltaLabel)
        assertTrue(result.showDate)
    }

    @Test
    fun `swap falls back to the caption when the date cannot fit alone`() {
        val result = contention(
            hasDateText = true,
            dateFitsWithLabel = false,
            dateFitsWithoutLabel = false,
            labelFitsAlone = true,
            preferDateOverLabel = true,
        )
        assertTrue(result.showDeltaLabel)
        assertFalse(result.showDate)
    }

    @Test
    fun `both dropped when neither fits alone regardless of swap`() {
        for (prefer in listOf(true, false)) {
            val result = contention(
                hasDateText = true,
                dateFitsWithLabel = false,
                dateFitsWithoutLabel = false,
                labelFitsAlone = false,
                preferDateOverLabel = prefer,
            )
            assertFalse("preferDate=$prefer", result.showDeltaLabel)
            assertFalse("preferDate=$prefer", result.showDate)
        }
    }

    @Test
    fun `both shown only when the date fits with the caption`() {
        // Invariant over the whole truth table: the only way to keep both is dateFitsWithLabel.
        for (hasDate in listOf(true, false)) {
            for (withLabel in listOf(true, false)) {
                for (withoutLabel in listOf(true, false)) {
                    for (labelAlone in listOf(true, false)) {
                        for (prefer in listOf(true, false)) {
                            val r = contention(hasDate, withLabel, withoutLabel, labelAlone, prefer)
                            val both = r.showDeltaLabel && r.showDate
                            val case = "hasDate=$hasDate withLabel=$withLabel " +
                                "withoutLabel=$withoutLabel labelAlone=$labelAlone prefer=$prefer"
                            assertEquals(case, hasDate && withLabel, both)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the swap flag only matters when both compete and both could win alone`() {
        // Guards against the alternation leaking into cases that are not a real contention —
        // e.g. flipping the caption off on a roomy header.
        for (hasDate in listOf(true, false)) {
            for (withLabel in listOf(true, false)) {
                for (withoutLabel in listOf(true, false)) {
                    for (labelAlone in listOf(true, false)) {
                        val preferDate = contention(hasDate, withLabel, withoutLabel, labelAlone, true)
                        val preferLabel = contention(hasDate, withLabel, withoutLabel, labelAlone, false)
                        val isRealContention =
                            hasDate && !withLabel && withoutLabel && labelAlone
                        val case = "hasDate=$hasDate withLabel=$withLabel " +
                            "withoutLabel=$withoutLabel labelAlone=$labelAlone"
                        assertEquals(case, isRealContention, preferDate != preferLabel)
                    }
                }
            }
        }
    }
}

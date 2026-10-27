package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * [HeaderLine.reserve]: room for what any visible source could show, the same whichever source is
 * displayed (`plans/261026-shared-header-line-reserve.md`; Fold 4, 2026-10-27).
 */
@Category(ShortDuration::class)
class HeaderLineTest {
    /** 10 units a character, scaled by the rain label's font scale. */
    private val measure = HeaderLine.Measure { _, text, fontScale -> text.length * 10f * fontScale }

    @Test
    fun reserveTakesTheWidestRainAndSourceLabel() {
        val inputs = HeaderLine.Inputs(
            sourceLabels = listOf("NWS", "Google"),
            rainLabels = listOf(HeaderContent.PrecipLabel("5%", 0.5f), HeaderContent.PrecipLabel("70%", 1f)),
        )
        val reserve = HeaderLine.reserve(measure, inputs, iconSlot = 25f)
        // icon 25 + "00.0°" 50 + "+0.0" 40 + "70%" 30
        assertEquals(145f, reserve.left, 0.01f)
        assertEquals(60f, reserve.right, 0.01f)
    }

    @Test
    fun dryReserveHasNoRain() {
        val reserve = HeaderLine.reserve(measure, HeaderLine.Inputs(listOf("NWS"), emptyList()), iconSlot = 0f)
        assertEquals(90f, reserve.left, 0.01f)
        assertEquals(30f, reserve.right, 0.01f)
    }
}

package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NwsQualityControlTest {

    @Test
    fun `codes NWS marks as bad are rejected`() {
        assertTrue(NwsQualityControl.isFailed("X")) // failed validation
        assertTrue(NwsQualityControl.isFailed("Q")) // questionable
        assertTrue(NwsQualityControl.isFailed("B")) // subjective bad
        assertTrue(NwsQualityControl.isFailed("T")) // failed time-consistency
    }

    @Test
    fun `codes NWS marks as usable are kept`() {
        assertFalse(NwsQualityControl.isFailed("V"))
        assertFalse(NwsQualityControl.isFailed("C"))
        assertFalse(NwsQualityControl.isFailed("S"))
        assertFalse(NwsQualityControl.isFailed("G"))
    }

    /**
     * `Z` = "preliminary, no QC applied" — the code most real-time reports arrive with (KPAO's live
     * feed, 2026-07-13). Treating it as a failure would discard nearly every current observation.
     */
    @Test
    fun `preliminary un-QCd readings are usable, not failures`() {
        assertFalse(NwsQualityControl.isFailed("Z"))
    }

    @Test
    fun `absent or blank code is not evidence of failure`() {
        assertFalse(NwsQualityControl.isFailed(null))
        assertFalse(NwsQualityControl.isFailed(""))
        assertFalse(NwsQualityControl.isFailed("   "))
    }

    @Test
    fun `codes are matched case and whitespace insensitively`() {
        assertTrue(NwsQualityControl.isFailed("x"))
        assertTrue(NwsQualityControl.isFailed(" Q "))
    }
}

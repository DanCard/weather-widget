package com.weatherwidget.shared.stats

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class AccuracyBaselineFieldTest {

    @Test
    fun `native actual is the default and survives a round trip`() {
        assertEquals(AccuracyBaselineField.NATIVE_ACTUAL, AccuracyBaselineField.DEFAULT)
        assertEquals(
            AccuracyBaselineField.BLENDED_LOCATION,
            AccuracyBaselineField.fromPrefValue(AccuracyBaselineField.BLENDED_LOCATION.prefValue),
        )
    }

    @Test
    fun `unknown or absent pref value falls back to the default`() {
        assertEquals(AccuracyBaselineField.DEFAULT, AccuracyBaselineField.fromPrefValue(null))
        assertEquals(AccuracyBaselineField.DEFAULT, AccuracyBaselineField.fromPrefValue("nonsense"))
    }

    @Test
    fun `native actual reads the api pair`() {
        val result = resolveBaselineTemps(
            field = AccuracyBaselineField.NATIVE_ACTUAL,
            computedHigh = 75.0f,
            computedLow = 60.7f,
            apiHigh = 75.2f,
            apiLow = 56.1f,
        )

        assertEquals(75.2f, result.high)
        assertEquals(56.1f, result.low)
        assertFalse(result.fellBackToBlend)
    }

    @Test
    fun `blended location ignores the api pair entirely`() {
        val result = resolveBaselineTemps(
            field = AccuracyBaselineField.BLENDED_LOCATION,
            computedHigh = 75.0f,
            computedLow = 60.7f,
            apiHigh = 75.2f,
            apiLow = 56.1f,
        )

        assertEquals(75.0f, result.high)
        assertEquals(60.7f, result.low)
        assertFalse(result.fellBackToBlend)
    }

    @Test
    fun `native actual degrades to the blend and flags it when either api value is missing`() {
        val missingLow = resolveBaselineTemps(
            field = AccuracyBaselineField.NATIVE_ACTUAL,
            computedHigh = 75.0f,
            computedLow = 60.7f,
            apiHigh = 75.2f,
            apiLow = null,
        )
        val missingBoth = resolveBaselineTemps(
            field = AccuracyBaselineField.NATIVE_ACTUAL,
            computedHigh = 75.0f,
            computedLow = 60.7f,
            apiHigh = null,
            apiLow = null,
        )

        assertEquals(75.0f, missingLow.high)
        assertTrue(missingLow.fellBackToBlend)
        assertEquals(60.7f, missingBoth.low)
        assertTrue(missingBoth.fellBackToBlend)
    }
}

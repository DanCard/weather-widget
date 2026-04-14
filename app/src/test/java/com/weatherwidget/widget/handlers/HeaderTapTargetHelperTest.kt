package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HeaderTapTargetHelperTest {
    @Test
    fun `shouldShowPrecipTouchZone is false for null`() {
        assertFalse(HeaderTapTargetHelper.shouldShowPrecipTouchZone(null))
    }

    @Test
    fun `shouldShowPrecipTouchZone is false for zero`() {
        assertFalse(HeaderTapTargetHelper.shouldShowPrecipTouchZone(0))
    }

    @Test
    fun `shouldShowPrecipTouchZone is true for positive values`() {
        assertTrue(HeaderTapTargetHelper.shouldShowPrecipTouchZone(1))
        assertTrue(HeaderTapTargetHelper.shouldShowPrecipTouchZone(80))
    }
}

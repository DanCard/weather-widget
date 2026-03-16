package com.weatherwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyForecastGraphRendererSizingTest {

    @Test
    fun `day label width scale shrinks only slightly on tight columns`() {
        val scale = DailyForecastGraphRenderer.computeDayLabelWidthScale(dayWidthDp = 60f)

        assertEquals(0.96f, scale, 0.0001f)
    }

    @Test
    fun `day label width scale stays at baseline on standard columns`() {
        val scale = DailyForecastGraphRenderer.computeDayLabelWidthScale(dayWidthDp = 70f)

        assertEquals(1.0f, scale, 0.0001f)
    }
}

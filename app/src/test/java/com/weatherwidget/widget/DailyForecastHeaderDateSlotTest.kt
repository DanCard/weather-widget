package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The painted date's safety net: the shared fit places the date (`HeaderFit.dateLeft`, tested in
 * `:shared`); the renderer only drops one that would overlap its own painted geometry.
 *
 * Pure arithmetic, no font engine involved (Robolectric has none) — widths are supplied already
 * measured, which is the whole reason [DailyForecastHeaderRenderer.resolveDateDrawX] was extracted.
 */
@Category(ShortDuration::class)
class DailyForecastHeaderDateSlotTest {

    private fun drawX(dateLeftX: Float?, leftClusterRight: Float = 123f, dateRightBoundary: Float = 369f) =
        DailyForecastHeaderRenderer.resolveDateDrawX(
            dateLeftX = dateLeftX,
            dateWidth = 46f,
            leftClusterRight = leftClusterRight,
            dateRightBoundary = dateRightBoundary,
        )

    @Test
    fun `the date is drawn where the shared fit put it`() {
        assertEquals(323f, drawX(300f)!!, 0.01f)
    }

    @Test
    fun `no shared position means no date`() {
        assertNull(drawX(null))
    }

    @Test
    fun `a date that would overlap the painted left cluster is dropped, not moved`() {
        assertNull(drawX(120f))
        assertNotNull(drawX(123f))
    }

    @Test
    fun `a date that would run into the API label is dropped, not moved`() {
        assertNull(drawX(330f))
        assertNotNull(drawX(323f))
    }
}

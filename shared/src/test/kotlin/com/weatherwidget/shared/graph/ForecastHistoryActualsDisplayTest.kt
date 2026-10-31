package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.ForecastHistoryViewLogic.ActualPair
import com.weatherwidget.shared.graph.ForecastHistoryViewLogic.LocationActuals
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** The one legend, footer and error-graph decision behind both History of Forecasts screens. */
@Category(ShortDuration::class)
class ForecastHistoryActualsDisplayTest {

    private val both = LocationActuals(71f, 52f, 1L, 2L)
    private val lowOnly = LocationActuals(null, 52f, null, 2L)

    private fun display(isPast: Boolean, apiHigh: Float? = null, apiLow: Float? = null, actuals: LocationActuals) =
        ForecastHistoryViewLogic.actualsDisplay(isPast, apiHigh, apiLow, actuals)

    @Test
    fun `past day shows every legend, both footers and the error graph`() {
        val d = display(isPast = true, apiHigh = 70f, apiLow = 51f, actuals = both)
        assertTrue(d.showApiActualLegend)
        assertTrue(d.showLocationActualLegend)
        assertEquals(ActualPair(70f, 51f), d.apiFooter)
        assertEquals(ActualPair(71f, 52f), d.locationFooter)
        assertTrue(d.showFooterCard)
        assertTrue(d.errorGraphAvailable)
    }

    @Test
    fun `past day with location actuals only still offers the error graph`() {
        val d = display(isPast = true, actuals = both)
        assertNull(d.apiFooter)
        assertTrue(d.errorGraphAvailable)
    }

    @Test
    fun `past day without a complete pair has no footer or error graph`() {
        val d = display(isPast = true, apiHigh = 70f, actuals = lowOnly)
        assertFalse(d.showFooterCard)
        assertFalse(d.errorGraphAvailable)
    }

    @Test
    fun `today after five pm draws lines without footer or error graph`() {
        val d = display(isPast = false, actuals = both)
        assertFalse(d.showApiActualLegend)
        assertTrue(d.showLocationActualLegend)
        assertFalse(d.showFooterCard)
        assertFalse(d.errorGraphAvailable)
    }

    @Test
    fun `today before five pm shows the location legend for a settled low`() {
        val d = display(isPast = false, actuals = lowOnly)
        assertTrue(d.showLocationActualLegend)
        assertFalse(d.showFooterCard)
    }

    @Test
    fun `future day shows no actuals UI`() {
        val d = display(isPast = false, actuals = LocationActuals.NONE)
        assertFalse(d.showApiActualLegend)
        assertFalse(d.showLocationActualLegend)
        assertFalse(d.showFooterCard)
    }
}

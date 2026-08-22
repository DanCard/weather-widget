package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.shared.util.DailyDayValueResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyDayValueResolverPastLineValuesTest {

    @Test
    fun `actual present passes through and forecast stays an overlay`() {
        val result = DailyDayValueResolver.resolvePastLineValues(
            actualHigh = 71f, actualLow = 55f, forecastHigh = 69f, forecastLow = 52f,
        )
        assertEquals(71f, result.solidHigh)
        assertEquals(55f, result.solidLow)
        assertEquals(69f, result.forecastHigh)
        assertEquals(52f, result.forecastLow)
        org.junit.Assert.assertFalse(result.solidIsForecastFallback)
    }

    @Test
    fun `no actual promotes the forecast into the labeled values`() {
        // THE regression case: Open-Meteo past day with no daily_history actual row — the column
        // must label the forecasted high/low instead of rendering unlabeled bars.
        val result = DailyDayValueResolver.resolvePastLineValues(
            actualHigh = null, actualLow = null, forecastHigh = 73.6f, forecastLow = 58.3f,
        )
        assertEquals(73.6f, result.solidHigh)
        assertEquals(58.3f, result.solidLow)
        assertEquals(73.6f, result.forecastHigh)
        assertEquals(58.3f, result.forecastLow)
        org.junit.Assert.assertTrue(result.solidIsForecastFallback)
    }

    @Test
    fun `no actual and no forecast stays null — nothing is fabricated`() {
        val result = DailyDayValueResolver.resolvePastLineValues(
            actualHigh = null, actualLow = null, forecastHigh = null, forecastLow = null,
        )
        assertNull(result.solidHigh)
        assertNull(result.solidLow)
        assertNull(result.forecastHigh)
        assertNull(result.forecastLow)
        org.junit.Assert.assertFalse(result.solidIsForecastFallback)
    }

    @Test
    fun `partial forecast with no actual stays partial`() {
        val result = DailyDayValueResolver.resolvePastLineValues(
            actualHigh = null, actualLow = null, forecastHigh = 70f, forecastLow = null,
        )
        assertEquals(70f, result.solidHigh)
        assertNull(result.solidLow)
    }

    @Test
    fun `forecast is never promoted when an actual exists`() {
        val result = DailyDayValueResolver.resolvePastLineValues(
            actualHigh = 65f, actualLow = 50f, forecastHigh = 80f, forecastLow = 40f,
        )
        assertEquals(65f, result.solidHigh)
        assertEquals(50f, result.solidLow)
    }
}

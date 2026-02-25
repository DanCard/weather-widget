package com.weatherwidget.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastHistoryActivity.ActualLookupMode
import com.weatherwidget.ui.ForecastHistoryActivity.ButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.hasRequiredHistoryExtras
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.resolveActualLookupMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.resolveButtonMode
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.shouldLaunchTemperature
import com.weatherwidget.ui.ForecastHistoryActivity.Companion.shouldShowTemperatureButton
import com.weatherwidget.ui.ForecastHistoryActivity.GraphMode

class ForecastHistoryActivityTest {

    // --- shouldLaunchTemperature ---

    @Test
    fun `shouldLaunchTemperature - no date - returns false`() {
        assertFalse(shouldLaunchTemperature(hasDate = false, showTemperatureButton = true))
    }

    @Test
    fun `shouldLaunchTemperature - date present and hourly button active - returns true`() {
        assertTrue(shouldLaunchTemperature(hasDate = true, showTemperatureButton = true))
    }

    @Test
    fun `shouldLaunchTemperature - date present and hourly button inactive - returns false`() {
        assertFalse(shouldLaunchTemperature(hasDate = true, showTemperatureButton = false))
    }

    // --- shouldShowTemperatureButton ---

    @Test
    fun `shouldShowTemperatureButton - today without actuals - returns true`() {
        val today = LocalDate.of(2026, 2, 25)
        assertTrue(shouldShowTemperatureButton(date = today, hasActualValues = false, today = today))
    }

    @Test
    fun `shouldShowTemperatureButton - future without actuals - returns true`() {
        val today = LocalDate.of(2026, 2, 25)
        val future = today.plusDays(2)
        assertTrue(shouldShowTemperatureButton(date = future, hasActualValues = false, today = today))
    }

    @Test
    fun `shouldShowTemperatureButton - today with actuals - returns false`() {
        val today = LocalDate.of(2026, 2, 25)
        assertFalse(shouldShowTemperatureButton(date = today, hasActualValues = true, today = today))
    }

    @Test
    fun `shouldShowTemperatureButton - past without actuals - returns false`() {
        val today = LocalDate.of(2026, 2, 25)
        val past = today.minusDays(1)
        assertFalse(shouldShowTemperatureButton(date = past, hasActualValues = false, today = today))
    }

    // --- resolveButtonMode ---

    @Test
    fun `resolveButtonMode - hourly button active - returns TEMPERATURE regardless of graphMode`() {
        assertEquals(ButtonMode.TEMPERATURE, resolveButtonMode(showTemperatureButton = true, GraphMode.EVOLUTION))
        assertEquals(ButtonMode.TEMPERATURE, resolveButtonMode(showTemperatureButton = true, GraphMode.ERROR))
    }

    @Test
    fun `resolveButtonMode - hourly button inactive and evolution mode - returns EVOLUTION`() {
        assertEquals(ButtonMode.EVOLUTION, resolveButtonMode(showTemperatureButton = false, GraphMode.EVOLUTION))
    }

    @Test
    fun `resolveButtonMode - hourly button inactive and error mode - returns ERROR`() {
        assertEquals(ButtonMode.ERROR, resolveButtonMode(showTemperatureButton = false, GraphMode.ERROR))
    }

    // --- hasRequiredHistoryExtras ---

    @Test
    fun `hasRequiredHistoryExtras - accepts date with zero coordinates extras present`() {
        assertTrue(
            hasRequiredHistoryExtras(
                targetDate = "2026-02-25",
                hasLatExtra = true,
                hasLonExtra = true,
            ),
        )
    }

    @Test
    fun `hasRequiredHistoryExtras - missing lat extra returns false`() {
        assertFalse(
            hasRequiredHistoryExtras(
                targetDate = "2026-02-25",
                hasLatExtra = false,
                hasLonExtra = true,
            ),
        )
    }

    @Test
    fun `hasRequiredHistoryExtras - missing lon extra returns false`() {
        assertFalse(
            hasRequiredHistoryExtras(
                targetDate = "2026-02-25",
                hasLatExtra = true,
                hasLonExtra = false,
            ),
        )
    }

    @Test
    fun `hasRequiredHistoryExtras - missing date returns false`() {
        assertFalse(
            hasRequiredHistoryExtras(
                targetDate = null,
                hasLatExtra = true,
                hasLonExtra = true,
            ),
        )
    }

    // --- resolveActualLookupMode ---

    @Test
    fun `resolveActualLookupMode - past date with requested source uses source specific lookup`() {
        val today = LocalDate.of(2026, 2, 25)
        val past = today.minusDays(1)

        assertEquals(
            ActualLookupMode.SOURCE_SPECIFIC,
            resolveActualLookupMode(
                date = past,
                requestedSource = WeatherSource.NWS,
                today = today,
            ),
        )
    }

    @Test
    fun `resolveActualLookupMode - past date without requested source uses any source lookup`() {
        val today = LocalDate.of(2026, 2, 25)
        val past = today.minusDays(1)

        assertEquals(
            ActualLookupMode.ANY_SOURCE,
            resolveActualLookupMode(
                date = past,
                requestedSource = null,
                today = today,
            ),
        )
    }

    @Test
    fun `resolveActualLookupMode - today never loads actuals`() {
        val today = LocalDate.of(2026, 2, 25)

        assertEquals(
            ActualLookupMode.NONE,
            resolveActualLookupMode(
                date = today,
                requestedSource = WeatherSource.OPEN_METEO,
                today = today,
            ),
        )
    }

    @Test
    fun `resolveActualLookupMode - future never loads actuals`() {
        val today = LocalDate.of(2026, 2, 25)
        val future = today.plusDays(1)

        assertEquals(
            ActualLookupMode.NONE,
            resolveActualLookupMode(
                date = future,
                requestedSource = WeatherSource.OPEN_METEO,
                today = today,
            ),
        )
    }
}

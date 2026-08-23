package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyDayValueResolverTest {

    @Test
    fun todayHeadlineExcludesSnapshot() {
        // observed 71, live forecast 80, ghost (peak so far) 72 → headline is the live forecast 80.
        // The 24h-prior snapshot (84) is NOT a parameter here, so it can never inflate the headline.
        val high = DailyDayValueResolver.effectiveHighForLabel(
            isToday = true, solidHigh = 71f, forecastHigh = 80f, ghostHigh = 72f,
        )
        assertEquals(80f, high)
    }

    @Test
    fun todayHeadlineUsesGhostWhenPeakExceededForecast() {
        // If the day already peaked above the live forecast, the ghost high wins.
        val high = DailyDayValueResolver.effectiveHighForLabel(
            isToday = true, solidHigh = 71f, forecastHigh = 80f, ghostHigh = 83f,
        )
        assertEquals(83f, high)
    }

    @Test
    fun nonTodayReturnsObservedHighOnly() {
        val high = DailyDayValueResolver.effectiveHighForLabel(
            isToday = false, solidHigh = 75f, forecastHigh = 99f, ghostHigh = 88f,
        )
        assertEquals(75f, high)
    }

    @Test
    fun todayAllNullReturnsNull() {
        assertNull(
            DailyDayValueResolver.effectiveHighForLabel(
                isToday = true, solidHigh = null, forecastHigh = null, ghostHigh = null,
            )
        )
    }

    // ── High cutoff (5pm) ──────────────────────────────────────────────────

    @Test
    fun todayHighBeforeCutoffStillUsesForecast() {
        // 10am: forecast 80 still wins even though the day has only reached 72 so far.
        val high = DailyDayValueResolver.effectiveHighForLabel(
            isToday = true, solidHigh = 70f, forecastHigh = 80f, ghostHigh = 72f, nowHour = 10,
        )
        assertEquals(80f, high)
    }

    @Test
    fun todayHighAfterCutoffTracksActualNotForecast() {
        // 6pm: the high is settled. Forecast over-predicted (80) but the day peaked at 76 → headline 76.
        val high = DailyDayValueResolver.effectiveHighForLabel(
            isToday = true, solidHigh = 74f, forecastHigh = 80f, ghostHigh = 76f, nowHour = 18,
        )
        assertEquals(76f, high)
    }

    @Test
    fun todayHighAfterCutoffFallsBackToForecastWhenNoActual() {
        // 6pm but no observations yet → never blank; fall back to the forecast-inclusive blend.
        val high = DailyDayValueResolver.effectiveHighForLabel(
            isToday = true, solidHigh = null, forecastHigh = 80f, ghostHigh = null, nowHour = 18,
        )
        assertEquals(80f, high)
    }

    @Test
    fun todayHighExactlyAtCutoffHourTracksActual() {
        // 5pm sharp (>= 17) already counts as settled.
        val high = DailyDayValueResolver.effectiveHighForLabel(
            isToday = true, solidHigh = 74f, forecastHigh = 80f, ghostHigh = 76f, nowHour = 17,
        )
        assertEquals(76f, high)
    }

    // ── isHighTrackingActual (drives the thermostat-color recolor) ──────────

    @Test
    fun highTrackingActualTrueAfterCutoffWithActual() {
        // 6pm with an observed peak → the headline is a settled actual, so recolor it.
        assertEquals(
            true,
            DailyDayValueResolver.isHighTrackingActual(
                isToday = true, solidHigh = 74f, ghostHigh = 76f, nowHour = 18,
            ),
        )
    }

    @Test
    fun highTrackingActualFalseBeforeCutoff() {
        // Noon: high not settled yet → still a forecast blend, keep the normal color.
        assertEquals(
            false,
            DailyDayValueResolver.isHighTrackingActual(
                isToday = true, solidHigh = 74f, ghostHigh = 76f, nowHour = 12,
            ),
        )
    }

    @Test
    fun highTrackingActualFalseAfterCutoffWhenNoActual() {
        // 6pm but no observations → headline falls back to forecast, so it is NOT an actual.
        assertEquals(
            false,
            DailyDayValueResolver.isHighTrackingActual(
                isToday = true, solidHigh = null, ghostHigh = null, nowHour = 18,
            ),
        )
    }

    @Test
    fun highTrackingActualFalseForNonToday() {
        assertEquals(
            false,
            DailyDayValueResolver.isHighTrackingActual(
                isToday = false, solidHigh = 74f, ghostHigh = 76f, nowHour = 18,
            ),
        )
    }

    @Test
    fun highTrackingActualFalseWhenHourNull() {
        // null hour disables the cutoff (legacy behavior) → never recolor.
        assertEquals(
            false,
            DailyDayValueResolver.isHighTrackingActual(
                isToday = true, solidHigh = 74f, ghostHigh = 76f, nowHour = null,
            ),
        )
    }

    // ── Low cutoff (9am) ───────────────────────────────────────────────────

    @Test
    fun todayLowBeforeCutoffUsesMinOfActualAndForecast() {
        // 7am: forecast low 45 still wins over the observed-so-far low of 48.
        val low = DailyDayValueResolver.effectiveLowForLabel(
            isToday = true, solidLow = 48f, forecastLow = 45f, nowHour = 7,
        )
        assertEquals(45f, low)
    }

    @Test
    fun todayLowAfterCutoffTracksActualNotForecast() {
        // 10am: overnight low is settled. Forecast under-predicted (45) but actual low was 48 → 48.
        val low = DailyDayValueResolver.effectiveLowForLabel(
            isToday = true, solidLow = 48f, forecastLow = 45f, nowHour = 10, actualLow = 48f,
        )
        assertEquals(48f, low)
    }

    @Test
    fun todayLowAfterCutoffWithoutActualLowKeepsForecastBlend() {
        // 10am but the source never writes actuals (Open-Meteo): a forecast stand-in
        // solidLow must NOT count as a settled actual — the blend (forecast low) stays.
        val low = DailyDayValueResolver.effectiveLowForLabel(
            isToday = true, solidLow = 57.5f, forecastLow = 57.5f, nowHour = 10, actualLow = null,
        )
        assertEquals(57.5f, low)
    }

    @Test
    fun todayLowAfterCutoffFallsBackToForecastWhenNoActual() {
        val low = DailyDayValueResolver.effectiveLowForLabel(
            isToday = true, solidLow = null, forecastLow = 45f, nowHour = 10,
        )
        assertEquals(45f, low)
    }

    @Test
    fun lowNullHourKeepsLegacyMinBehavior() {
        val low = DailyDayValueResolver.effectiveLowForLabel(
            isToday = true, solidLow = 48f, forecastLow = 45f, nowHour = null,
        )
        assertEquals(45f, low)
    }

    @Test
    fun nonTodayLowReturnsObservedOnly() {
        val low = DailyDayValueResolver.effectiveLowForLabel(
            isToday = false, solidLow = 50f, forecastLow = 40f, nowHour = 12,
        )
        assertEquals(50f, low)
    }

    // ── Icon anchor (lowest drawn bar) ─────────────────────────────────────

    @Test
    fun iconAnchorTracksLowestBarNotPrintedValue() {
        // Today: observed bar stops at 50 but the forecast comparison bar dips to 44 → icon at 44,
        // independent of whatever the headline low number prints.
        val anchor = DailyDayValueResolver.iconAnchorLow(solidLow = 50f, forecastLow = 44f, snapshotLow = 47f)
        assertEquals(44f, anchor)
    }

    @Test
    fun iconAnchorIgnoresNullBars() {
        // Future/history with no snapshot bar: anchor is the min of the present bars.
        val anchor = DailyDayValueResolver.iconAnchorLow(solidLow = 55f, forecastLow = null, snapshotLow = null)
        assertEquals(55f, anchor)
    }

    @Test
    fun iconAnchorAllNullReturnsNull() {
        assertNull(DailyDayValueResolver.iconAnchorLow(solidLow = null, forecastLow = null, snapshotLow = null))
    }

    // ── isLowTrackingActual (drives the thermostat-color recolor) ───────────

    @Test
    fun lowTrackingActualTrueAfterCutoffWithActual() {
        // 10am with a genuinely observed low → settled actual, red thermostat color is correct.
        assertEquals(
            true,
            DailyDayValueResolver.isLowTrackingActual(
                isToday = true, solidLow = 48f, nowHour = 10, actualLow = 48f,
            ),
        )
    }

    @Test
    fun lowTrackingActualFalseWithoutActualEvenAfterCutoff() {
        // Regression: Open-Meteo has no daily_history row, so solidLow was just the current
        // temp standing in — that painted the low label red as a "settled actual".
        assertEquals(
            false,
            DailyDayValueResolver.isLowTrackingActual(
                isToday = true, solidLow = 72.3f, nowHour = 16, actualLow = null,
            ),
        )
    }

    @Test
    fun lowTrackingActualFalseBeforeCutoff() {
        assertEquals(
            false,
            DailyDayValueResolver.isLowTrackingActual(
                isToday = true, solidLow = 48f, nowHour = 7, actualLow = 48f,
            ),
        )
    }

    @Test
    fun lowTrackingActualFalseForNonToday() {
        assertEquals(
            false,
            DailyDayValueResolver.isLowTrackingActual(
                isToday = false, solidLow = 48f, nowHour = 10, actualLow = 48f,
            ),
        )
    }

    // ── resolveTodayLineValues (solidLow = actual low ?: forecast low) ──────

    @Test
    fun todayLineValuesNoActualLowUsesForecastLowNotCurrentTemp() {
        // Regression: Open-Meteo-style day — currentTemp must never masquerade as the
        // observed low; the forecast low stands in so the thermostat spans the day range.
        val values = DailyDayValueResolver.resolveTodayLineValues(
            actualHigh = null, actualLow = null,
            forecastHigh = 73f, forecastLow = 57.5f,
            currentTemp = 72.3f,
        )
        assertEquals(72.3f, values.solidHigh)
        assertEquals(57.5f, values.solidLow)
    }

    @Test
    fun todayLineValuesWithActualLowEqualsActualExactly() {
        val values = DailyDayValueResolver.resolveTodayLineValues(
            actualHigh = 75f, actualLow = 52f,
            forecastHigh = 73f, forecastLow = 57.5f,
            currentTemp = 68f,
        )
        assertEquals(52f, values.solidLow)
        assertEquals(57.5f, values.forecastLow)
    }
}

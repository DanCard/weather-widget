package com.weatherwidget.widget.handlers

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

/**
 * Unit tests for [DailyViewHandler.shouldProbeHistoryBackfill] — the gate that decides whether
 * the daily forecast view probes recent NWS observation coverage to repair an incomplete
 * historical actual (the bug where the daily view never re-fetched a partially-covered past day
 * because its daily_history row already existed).
 */
@Category(ShortDuration::class)
class DailyHistoryBackfillGateTest {

    private val today: LocalDate = LocalDate.of(2026, 6, 1)

    @Test
    fun `probes NWS when centered on today`() {
        assertTrue(DailyViewHandler.shouldProbeHistoryBackfill(WeatherSource.NWS, today, today, visibleDays = 3))
    }

    @Test
    fun `probes NWS at the recency boundary`() {
        assertTrue(
            DailyViewHandler.shouldProbeHistoryBackfill(WeatherSource.NWS, today.minusDays(3), today, visibleDays = 3),
        )
    }

    @Test
    fun `skips when navigated past the fetch horizon`() {
        assertFalse(
            DailyViewHandler.shouldProbeHistoryBackfill(WeatherSource.NWS, today.minusDays(4), today, visibleDays = 3),
        )
    }

    @Test
    fun `skips non-NWS sources`() {
        assertFalse(
            DailyViewHandler.shouldProbeHistoryBackfill(WeatherSource.OPEN_METEO, today, today, visibleDays = 3),
        )
    }

    @Test
    fun `probes when navigated into the future`() {
        assertTrue(
            DailyViewHandler.shouldProbeHistoryBackfill(WeatherSource.NWS, today.plusDays(2), today, visibleDays = 3),
        )
    }

    // ── Borrowed actuals (plans/261013-daily-view-backfills-borrowed-nws-actuals.md) ──────────
    // The gate is asked of the actuals provider. Callers resolve it with backfillActualsSource.

    private val mountainView = 37.417 to -122.089
    private val kyiv = 50.45 to 30.52

    private fun provider(source: WeatherSource, at: Pair<Double, Double>) =
        backfillActualsSource(source, at.first, at.second, preference = { null })

    @Test
    fun `Google inside NWS coverage probes, because it draws NWS actuals`() {
        assertTrue(DailyViewHandler.shouldProbeHistoryBackfill(provider(WeatherSource.GOOGLE_WEATHER, mountainView), today, today))
        assertTrue(DailyViewHandler.shouldProbeHistoryBackfill(provider(WeatherSource.SILURIAN, mountainView), today, today))
    }

    @Test
    fun `Google outside NWS coverage borrows METAR and does not probe`() {
        assertFalse(DailyViewHandler.shouldProbeHistoryBackfill(provider(WeatherSource.GOOGLE_WEATHER, kyiv), today, today))
    }

    @Test
    fun `an explicit non-NWS provider choice does not probe`() {
        val metar = backfillActualsSource(WeatherSource.GOOGLE_WEATHER, mountainView.first, mountainView.second) {
            WeatherSource.METAR
        }
        assertFalse(DailyViewHandler.shouldProbeHistoryBackfill(metar, today, today))
    }

    @Test
    fun `a source with its own actuals is its own provider`() {
        assertFalse(DailyViewHandler.shouldProbeHistoryBackfill(provider(WeatherSource.OPEN_METEO, mountainView), today, today))
    }
}

package com.weatherwidget.widget.handlers

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.testutil.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

/**
 * Android's past-day overlay through the shared rule. Desktop DB 2026-10-02: NWS rows for 09-02/03
 * had collapsed to 74/74, and this path drew them as zero-height bars where an older 74/56 existed.
 */
@Category(ShortDuration::class)
class DailyPastDayResolverOverlayTest {
    private val date = LocalDate.parse("2026-09-02")

    private fun row(high: Float?, low: Float?, fetchedAt: Long, source: String = "NWS") =
        TestData.forecast(targetDate = "2026-09-02", source = source, highTemp = high, lowTemp = low, fetchedAt = fetchedAt)

    private fun overlay(vararg rows: com.weatherwidget.data.local.ForecastEntity, hourly: List<Float> = emptyList()) =
        DailyPastDayResolver.resolvePastDayOverlay(
            actual = null, forecasts = rows.toList(), displaySource = WeatherSource.NWS, date = date, hourlyTemps = hourly,
        )?.let { it.high to it.low }

    private fun resolved(vararg rows: com.weatherwidget.data.local.ForecastEntity, hourly: List<Float> = emptyList()) =
        DailyPastDayResolver.resolvePastDayOverlay(
            actual = null, forecasts = rows.toList(), displaySource = WeatherSource.NWS, date = date, hourlyTemps = hourly,
        )

    @Test
    fun `an older real range beats a newer collapsed row`() {
        assertEquals(74f to 56f, overlay(row(74f, 56f, 1), row(74f, 74f, 2)))
    }

    @Test
    fun `another source's newer row is never a candidate`() {
        assertEquals(74f to 56f, overlay(row(74f, 56f, 1), row(90f, 70f, 5, source = "OPEN_METEO")))
    }

    @Test
    fun `a one-sided row draws no overlay without a fallback`() {
        assertNull(overlay(row(74f, null, 1)))
    }

    @Test
    fun `a missing low falls back to the post-cutoff value, then the hourly low, dashed`() {
        val withHindcast = row(74f, null, 1).copy(hindcastLowTemp = 58f)
        assertEquals(DailyPastDayResolverResult(74f, 58f, true), resolved(withHindcast, hourly = listOf(60f, 75f)).asResult())
        assertEquals(DailyPastDayResolverResult(74f, 60f, true), resolved(row(74f, null, 1), hourly = listOf(60f, 75f)).asResult())
    }

    private data class DailyPastDayResolverResult(val high: Float, val low: Float, val isFallback: Boolean)

    private fun com.weatherwidget.shared.util.PastDayForecastOverlay.Resolved?.asResult() =
        this?.let { DailyPastDayResolverResult(it.high, it.low, it.isFallback) }
}

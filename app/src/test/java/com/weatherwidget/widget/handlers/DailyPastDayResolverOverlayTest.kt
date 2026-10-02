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

    private fun overlay(vararg rows: com.weatherwidget.data.local.ForecastEntity) =
        DailyPastDayResolver.resolvePastDayOverlay(actual = null, forecasts = rows.toList(), displaySource = WeatherSource.NWS, date = date)

    @Test
    fun `an older real range beats a newer collapsed row`() {
        assertEquals(74f to 56f, overlay(row(74f, 56f, 1), row(74f, 74f, 2)))
    }

    @Test
    fun `another source's newer row is never a candidate`() {
        assertEquals(74f to 56f, overlay(row(74f, 56f, 1), row(90f, 70f, 5, source = "OPEN_METEO")))
    }

    @Test
    fun `a one-sided row draws no overlay`() {
        val (high, low) = overlay(row(74f, null, 1))
        assertNull(high)
        assertNull(low)
    }
}

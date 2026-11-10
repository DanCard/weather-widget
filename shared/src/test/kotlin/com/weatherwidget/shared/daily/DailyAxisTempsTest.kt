package com.weatherwidget.shared.daily

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** Which temperatures the daily graph's y-axis spans — only the ones a column draws. */
@Category(ShortDuration::class)
class DailyAxisTempsTest {

    private fun drawn(
        isPast: Boolean = false,
        isToday: Boolean = false,
        solidHigh: Float? = 71f,
        solidLow: Float? = 57f,
        forecastHigh: Float? = null,
        snapshotHigh: Float? = 75f,
        snapshotLow: Float? = 58f,
        futurePriorBarDrawn: Boolean = false,
    ) = DailyAxisTemps.drawn(
        isPast = isPast, isToday = isToday, solidHigh = solidHigh, solidLow = solidLow,
        forecastHigh = forecastHigh, forecastLow = null, ghostHigh = null,
        snapshotHigh = snapshotHigh, snapshotLow = snapshotLow, futurePriorBarDrawn = futurePriorBarDrawn,
    )

    @Test
    fun `future day counts the forecast-24h-ago bar when it is drawn`() {
        // Open-Meteo Nov 13 2026: 74° a day earlier beside today's 64° — the axis must reach the 74.
        assertEquals(listOf(64f, 58f, 74f, 59f), drawn(solidHigh = 64f, solidLow = 58f, snapshotHigh = 74f, snapshotLow = 59f, futurePriorBarDrawn = true))
        assertEquals(listOf(64f, 58f), drawn(solidHigh = 64f, solidLow = 58f, snapshotHigh = 74f, snapshotLow = 59f, futurePriorBarDrawn = false))
    }

    @Test
    fun `future day leaves out its undrawn snapshot`() {
        // Nov 3 2026 on desktop: a 75° snapshot over a 71° bar pushed the axis 4° past the tallest bar.
        assertEquals(listOf(71f, 57f), drawn())
    }

    @Test
    fun `today and past days keep the snapshot their triple bar draws`() {
        assertEquals(listOf(71f, 57f, 75f, 58f), drawn(isToday = true))
        assertEquals(listOf(71f, 57f, 75f, 58f), drawn(isPast = true))
    }

    @Test
    fun `future day with no solid or forecast high keeps the snapshot its label falls back to`() {
        assertEquals(listOf(75f, 58f), drawn(solidHigh = null, solidLow = null))
    }
}

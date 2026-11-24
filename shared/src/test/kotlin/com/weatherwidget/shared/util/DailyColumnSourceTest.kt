package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class DailyColumnSourceTest {
    private val today = LocalDate.of(2026, 10, 6)
    private val google = WeatherSource.GOOGLE_WEATHER.id

    @Test
    fun `the display source's own row is always drawable`() {
        assertTrue(DailyColumnSource.mayDraw(google, google, today.plusDays(1), today))
    }

    @Test
    fun `another provider's row is never drawable`() {
        (0L..10L).forEach { d ->
            assertFalse(DailyColumnSource.mayDraw(WeatherSource.NWS.id, google, today.plusDays(d), today))
        }
    }

    @Test
    fun `climate normals fill only after today plus two`() {
        val gap = WeatherSource.GENERIC_GAP.id
        assertFalse(DailyColumnSource.mayDraw(gap, google, today, today))
        assertFalse(DailyColumnSource.mayDraw(gap, google, today.plusDays(2), today))
        assertTrue(DailyColumnSource.mayDraw(gap, google, today.plusDays(3), today))
    }

    private data class R(val source: String, val high: Float?, val low: Float?)
    private fun choose(rows: List<R>, offset: Long) = DailyColumnSource.chooseRow(
        rows, google, today.plusDays(offset), today, { it.source }, { it.high }, { it.low },
    )
    private val gapRow = R(WeatherSource.GENERIC_GAP.id, 70f, 50f)

    @Test
    fun `a low-only display row is kept over the climate gap row`() {
        // Android used to swap it for the gap row, so it drew a whole climate day where desktop drew
        // the real low (plans/261124-partial-high-days-shared-row-choice-claude.md).
        val lowOnly = R(google, null, 54f)
        assertEquals(lowOnly, choose(listOf(gapRow, lowOnly), 5))
    }

    @Test
    fun `a display row without its low gives way to a complete gap row after today plus two`() {
        val highOnly = R(google, 72f, null)
        assertEquals(gapRow, choose(listOf(highOnly, gapRow), 5))
        assertEquals("no complete gap row: the display row as it is", highOnly, choose(listOf(highOnly), 5))
    }

    @Test
    fun `with no display row the gap row shows after today plus two and nothing before`() {
        assertEquals(gapRow, choose(listOf(gapRow), 3))
        (0L..2L).forEach { assertEquals(null, choose(listOf(gapRow), it)) }
    }

    @Test
    fun `today through today plus two show the display row however partial`() {
        val highOnly = R(google, 72f, null)
        (0L..2L).forEach { assertEquals(highOnly, choose(listOf(gapRow, highOnly), it)) }
    }
}

package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
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
}

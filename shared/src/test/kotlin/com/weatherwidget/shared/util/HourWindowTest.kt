package com.weatherwidget.shared.util

import com.weatherwidget.shared.observations.ObservationsTabs
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime

/**
 * The hourly window every hourly graph frames its hours with, on both platforms (seven copies were
 * folded into [WeatherTimeUtils.hourWindow], 2026-10-24), and the persisted Observations tab ids.
 */
@Category(ShortDuration::class)
class HourWindowTest {
    private fun at(hour: Int, minute: Int) = LocalDateTime.of(2026, 10, 24, hour, minute)

    @Test
    fun `centre before half past rounds down`() {
        assertEquals(HourSpan(at(12, 0), at(16, 0)), WeatherTimeUtils.hourWindow(at(14, 29), 2, 2))
    }

    @Test
    fun `centre at half past rounds up`() {
        assertEquals(HourSpan(at(13, 0), at(17, 0)), WeatherTimeUtils.hourWindow(at(14, 30), 2, 2))
    }

    @Test
    fun `back and forward are independent and can cross midnight`() {
        val span = WeatherTimeUtils.hourWindow(at(23, 45), 3, 6)
        assertEquals(at(21, 0), span.start)
        assertEquals(LocalDateTime.of(2026, 10, 25, 6, 0), span.end)
    }

    @Test
    fun `observations tab ids are the persisted values`() {
        // Android's last_selected_obs_tab pref and desktop's obsSelectedTab store these numbers.
        assertEquals(0, ObservationsTabs.BLEND)
        assertEquals(1, ObservationsTabs.OBSERVATIONS)
        assertEquals(2, ObservationsTabs.FETCH_LOGS)
    }
}

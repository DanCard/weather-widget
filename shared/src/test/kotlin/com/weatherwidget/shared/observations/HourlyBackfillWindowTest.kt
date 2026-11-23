package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HourlyBackfillWindowTest {
    @Test
    fun `sizes lookback to gap minutes plus 2h margin clamped to 3-72h`() {
        assertEquals(3L, HourlyBackfillWindow.lookbackHours("latest_gap_min=48"))
        assertEquals(3L, HourlyBackfillWindow.lookbackHours("latest_gap_min=10"))
        assertEquals(4L, HourlyBackfillWindow.lookbackHours("latest_gap_min=90"))
        assertEquals(8L, HourlyBackfillWindow.lookbackHours("cloud_series_break_min=360"))
        assertEquals(72L, HourlyBackfillWindow.lookbackHours("latest_gap_min=5000"))
    }

    @Test
    fun `empty site or non-gap reason keeps full 72h default`() {
        assertEquals(72L, HourlyBackfillWindow.lookbackHours("no_nws_observations"))
        assertEquals(72L, HourlyBackfillWindow.lookbackHours("unknown_reason"))
    }
}

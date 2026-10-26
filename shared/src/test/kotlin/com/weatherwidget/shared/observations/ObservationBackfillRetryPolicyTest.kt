package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ObservationBackfillRetryPolicyTest {

    @Test
    fun `schedule is 10 s then 1 m then 4 m, then stops`() {
        assertEquals(10_000L, ObservationBackfillRetryPolicy.nextRetryDelayMs(unreachable = true, attempt = 0))
        assertEquals(60_000L, ObservationBackfillRetryPolicy.nextRetryDelayMs(unreachable = true, attempt = 1))
        assertEquals(240_000L, ObservationBackfillRetryPolicy.nextRetryDelayMs(unreachable = true, attempt = 2))
        assertNull(ObservationBackfillRetryPolicy.nextRetryDelayMs(unreachable = true, attempt = 3))
    }

    @Test
    fun `an answered backfill is never retried, even empty`() {
        assertNull(ObservationBackfillRetryPolicy.nextRetryDelayMs(unreachable = false, attempt = 0))
    }
}

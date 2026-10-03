package com.weatherwidget.widget

import com.weatherwidget.data.repository.RecentBackfillResult
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ObservationBackfillRetryPolicyTest {

    private val unreachable = RecentBackfillResult(0, 0, emptySet(), unreachable = true)
    private val answeredEmpty = RecentBackfillResult(5, 0, emptySet(), unreachable = false)

    @Test
    fun `schedule is 10 s then 1 m then 4 m, then stops`() {
        assertEquals(10_000L, ObservationBackfillRetryPolicy.nextRetryDelayMs(unreachable, attempt = 0))
        assertEquals(60_000L, ObservationBackfillRetryPolicy.nextRetryDelayMs(unreachable, attempt = 1))
        assertEquals(240_000L, ObservationBackfillRetryPolicy.nextRetryDelayMs(unreachable, attempt = 2))
        assertNull(ObservationBackfillRetryPolicy.nextRetryDelayMs(unreachable, attempt = 3))
    }

    @Test
    fun `an answered backfill is never retried, even empty`() {
        assertNull(ObservationBackfillRetryPolicy.nextRetryDelayMs(answeredEmpty, attempt = 0))
    }
}

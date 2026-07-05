package com.weatherwidget.data.repository

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForecastHistoryPolicyTest {

    private val priority = setOf("NWS")
    private val nonPriority = "OPEN_METEO"

    @Test
    fun `priority source uses 4h bucket, others use 12h`() {
        assertEquals(ForecastHistoryPolicy.PRIMARY_BUCKET_MS, ForecastHistoryPolicy.bucketMs("NWS", priority))
        assertEquals(4L * 60 * 60 * 1000, ForecastHistoryPolicy.bucketMs("NWS", priority))
        assertEquals(ForecastHistoryPolicy.NON_PRIMARY_BUCKET_MS, ForecastHistoryPolicy.bucketMs(nonPriority, priority))
        assertEquals(12L * 60 * 60 * 1000, ForecastHistoryPolicy.bucketMs(nonPriority, priority))
    }

    @Test
    fun `every displayed source in the priority set gets the fast bucket`() {
        val displayed = setOf("OPEN_METEO", "SILURIAN")
        // A source the user is viewing gets 4h even when it is not the global first-in-order one.
        assertEquals(ForecastHistoryPolicy.PRIMARY_BUCKET_MS, ForecastHistoryPolicy.bucketMs("OPEN_METEO", displayed))
        assertEquals(ForecastHistoryPolicy.PRIMARY_BUCKET_MS, ForecastHistoryPolicy.bucketMs("SILURIAN", displayed))
        // A background source not in the set still gets the slow 12h bucket.
        assertEquals(ForecastHistoryPolicy.NON_PRIMARY_BUCKET_MS, ForecastHistoryPolicy.bucketMs("NWS", displayed))
    }

    @Test
    fun `timestampToGroupPredictions floors to the bucket boundary`() {
        val fiveHours = 5L * 60 * 60 * 1000
        // Priority: 4h buckets -> floor(5h) = 4h boundary.
        assertEquals(4L * 60 * 60 * 1000, ForecastHistoryPolicy.timestampToGroupPredictions(fiveHours, "NWS", priority))
        // Non-priority: 12h buckets -> floor(5h) = 0.
        assertEquals(0L, ForecastHistoryPolicy.timestampToGroupPredictions(fiveHours, nonPriority, priority))
    }

    @Test
    fun `fetches within the same bucket share a snapshot, across buckets differ`() {
        val base = 100L * 24 * 60 * 60 * 1000 // arbitrary day boundary
        val t0 = base + 1L * 60 * 60 * 1000   // +1h
        val t1 = base + 3L * 60 * 60 * 1000   // +3h (same 4h priority bucket as t0)
        val t2 = base + 5L * 60 * 60 * 1000   // +5h (next 4h priority bucket)

        val b0 = ForecastHistoryPolicy.timestampToGroupPredictions(t0, "NWS", priority)
        val b1 = ForecastHistoryPolicy.timestampToGroupPredictions(t1, "NWS", priority)
        val b2 = ForecastHistoryPolicy.timestampToGroupPredictions(t2, "NWS", priority)

        assertEquals("0-4h fetches collapse to one snapshot", b0, b1)
        assertEquals(base, b0)
        assertEquals("crossing the 4h boundary starts a new snapshot", base + 4L * 60 * 60 * 1000, b2)
    }

    @Test
    fun `non-priority keeps 12h spacing where priority would split`() {
        val base = 100L * 24 * 60 * 60 * 1000
        val t5 = base + 5L * 60 * 60 * 1000  // +5h
        val t11 = base + 11L * 60 * 60 * 1000 // +11h
        // For a non-priority source both fall in the same 12h bucket (would be different 4h buckets).
        assertEquals(
            ForecastHistoryPolicy.timestampToGroupPredictions(t5, nonPriority, priority),
            ForecastHistoryPolicy.timestampToGroupPredictions(t11, nonPriority, priority),
        )
    }
}

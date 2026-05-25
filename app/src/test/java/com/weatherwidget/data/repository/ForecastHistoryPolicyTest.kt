package com.weatherwidget.data.repository

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForecastHistoryPolicyTest {

    private val primary = "NWS"
    private val nonPrimary = "OPEN_METEO"

    @Test
    fun `primary source uses 4h bucket, others use 8h`() {
        assertEquals(ForecastHistoryPolicy.PRIMARY_BUCKET_MS, ForecastHistoryPolicy.bucketMs(primary, primary))
        assertEquals(4L * 60 * 60 * 1000, ForecastHistoryPolicy.bucketMs(primary, primary))
        assertEquals(ForecastHistoryPolicy.NON_PRIMARY_BUCKET_MS, ForecastHistoryPolicy.bucketMs(nonPrimary, primary))
        assertEquals(8L * 60 * 60 * 1000, ForecastHistoryPolicy.bucketMs(nonPrimary, primary))
    }

    @Test
    fun `snapshotBucket floors to the bucket boundary`() {
        val fiveHours = 5L * 60 * 60 * 1000
        // Primary: 4h buckets -> floor(5h) = 4h boundary.
        assertEquals(4L * 60 * 60 * 1000, ForecastHistoryPolicy.snapshotBucket(fiveHours, primary, primary))
        // Non-primary: 8h buckets -> floor(5h) = 0.
        assertEquals(0L, ForecastHistoryPolicy.snapshotBucket(fiveHours, nonPrimary, primary))
    }

    @Test
    fun `fetches within the same bucket share a snapshot, across buckets differ`() {
        val base = 100L * 24 * 60 * 60 * 1000 // arbitrary day boundary
        val t0 = base + 1L * 60 * 60 * 1000   // +1h
        val t1 = base + 3L * 60 * 60 * 1000   // +3h (same 4h primary bucket as t0)
        val t2 = base + 5L * 60 * 60 * 1000   // +5h (next 4h primary bucket)

        val b0 = ForecastHistoryPolicy.snapshotBucket(t0, primary, primary)
        val b1 = ForecastHistoryPolicy.snapshotBucket(t1, primary, primary)
        val b2 = ForecastHistoryPolicy.snapshotBucket(t2, primary, primary)

        assertEquals("0-4h fetches collapse to one snapshot", b0, b1)
        assertEquals(base, b0)
        assertEquals("crossing the 4h boundary starts a new snapshot", base + 4L * 60 * 60 * 1000, b2)
    }

    @Test
    fun `non-primary keeps 8h spacing where primary would split`() {
        val base = 100L * 24 * 60 * 60 * 1000
        val t5 = base + 5L * 60 * 60 * 1000 // +5h
        val t7 = base + 7L * 60 * 60 * 1000 // +7h
        // For a non-primary source both fall in the same 8h bucket (would be different 4h buckets).
        assertEquals(
            ForecastHistoryPolicy.snapshotBucket(t5, nonPrimary, primary),
            ForecastHistoryPolicy.snapshotBucket(t7, nonPrimary, primary),
        )
    }
}

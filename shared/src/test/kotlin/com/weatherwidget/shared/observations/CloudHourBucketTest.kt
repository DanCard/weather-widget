package com.weatherwidget.shared.observations

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CloudHourBucketTest {

    private val hour = 1_800_000_000_000L // exactly hour-aligned (divides by 3_600_000)
    private val min = 60_000L

    @Test
    fun `the read range reaches back far enough to cover the first visible hour`() {
        // The regression: KSJC's 00:30 METAR buckets into 01:00, the first visible hour of a
        // 1a-5a graph, but a bare `timestamp >= 01:00` read excluded it and the actual curve
        // started at 2a. The padded read must admit it.
        val reportBeforeWindow = hour - 30 * min
        assertEquals(hour, CloudHourBucket.startMsOf(reportBeforeWindow))
        assertTrue(
            "a report that buckets into the first visible hour must be inside the read range",
            reportBeforeWindow >= CloudHourBucket.readStartMs(hour),
        )
    }

    @Test
    fun `the read range reaches forward far enough to cover the last visible hour`() {
        val reportAfterWindow = hour + 29 * min
        assertEquals(hour, CloudHourBucket.startMsOf(reportAfterWindow))
        assertTrue(
            "a report that buckets into the last visible hour must be inside the read range",
            reportAfterWindow < CloudHourBucket.readEndMs(hour + 1),
        )
    }

    @Test
    fun `the pad is exactly the bucketing tolerance - never wide enough to reach another hour mark`() {
        // Padding by a full hour would pull whole extra hour marks (and the synthetic rows that sit
        // on them) into the read. Half an hour is the most the rounding rule can ever need.
        assertEquals(30 * min, CloudHourBucket.TOLERANCE_MS)
        assertEquals(hour - 30 * min, CloudHourBucket.readStartMs(hour))
        assertEquals(hour + 30 * min, CloudHourBucket.readEndMs(hour))
        // No hour mark lies in the padding on either side.
        assertEquals(hour, CloudHourBucket.startMsOf(CloudHourBucket.readStartMs(hour)))
    }
}

package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class CloudActualSeriesTest {
    private val quarter = 15 * 60_000L
    private val start = 1_800_000_000_000L

    @Test
    fun `native quarter-hour points are retained through the inclusive current timestamp`() {
        val points = CloudActualSeries.points(
            values = mapOf(
                start - quarter to 90,
                start to 68,
                start + quarter to 56,
                start + 2 * quarter to 42,
            ),
            startMs = start,
            endMs = start + quarter,
        )

        assertEquals(listOf(start, start + quarter), points.map { it.timeMs })
        assertEquals(listOf(68, 56), points.map { it.cover })
    }

    @Test
    fun `quarter-hour series splits rather than bridging a missing half hour`() {
        val points = listOf(
            TimedCloudCover(start, 80),
            TimedCloudCover(start + quarter, 60),
            TimedCloudCover(start + 4 * quarter, 20),
            TimedCloudCover(start + 5 * quarter, 10),
        )

        assertEquals(listOf(2, 2), CloudActualSeries.segments(points).map { it.size })
    }

    @Test
    fun `station-offset pairs do not shatter the line into dots`() {
        // The 2026-08-24 desktop bug: KNUQ at :15/:35/:55 and KSJC at :53 produce a smallest step
        // of 2 minutes. A minimum-step cadence rule turns that into a 4-minute bridge and every
        // measured point becomes a lone dot. The median (20 minutes here) must see through the
        // offset pairs and bridge the reporting cadence — while still splitting the genuine gap.
        val min = 60_000L
        val points = listOf(
            TimedCloudCover(start, 80),                // :00
            TimedCloudCover(start + 20 * min, 75),     // :20
            TimedCloudCover(start + 38 * min, 70),     // :38
            TimedCloudCover(start + 40 * min, 65),     // :40 — station-offset pair with :38
            TimedCloudCover(start + 60 * min, 60),     // +1h
            // 72-minute genuine gap, beyond the 40-minute bridge.
            TimedCloudCover(start + 132 * min, 40),
            TimedCloudCover(start + 152 * min, 30),
        )

        assertEquals(listOf(5, 2), CloudActualSeries.segments(points).map { it.size })
    }

    @Test
    fun `dense NWS timestamps do not split normal METAR intervals`() {
        // Live emulator shape on 2026-08-24: the merged series was dominated by 5-minute ASOS
        // timestamps, so its median was 5 minutes even though ordinary METAR reports were 15-20
        // minutes apart. A cadence-only 10-minute bridge produced singleton segments at 05:55 and
        // 06:15 despite 139 valid actual points being present.
        val min = 60_000L
        val denseRun = (0..8).map { i -> TimedCloudCover(start + i * 5 * min, 80 - i) }
        val points = denseRun + listOf(
            TimedCloudCover(start + 55 * min, 70),  // normal 15-minute interval
            TimedCloudCover(start + 75 * min, 65),  // normal 20-minute interval
            TimedCloudCover(start + 95 * min, 60),  // normal 20-minute interval
            // Genuine 40-minute hole: beyond the METAR anchor tolerance, so it must split.
            TimedCloudCover(start + 135 * min, 40),
            TimedCloudCover(start + 140 * min, 35),
        )

        assertEquals(listOf(12, 2), CloudActualSeries.segments(points).map { it.size })
    }

    // ── coverage(): the same bridge, asked by whatever reacts to a break ──────────────────

    /**
     * Cloud-carrying observation times from the two devices on 2026-09-03, as minutes from the
     * first point. The Samsung's curve broke at 11:35→12:15 and the emulator's did not; the
     * difference is KSJC's 5-minute ASOS feed, which the emulator's history sweep had captured and
     * the Samsung's (run at 00:11) had not.
     */
    private val samsungCarrierMinutes = listOf(
        0, 2, 22, 27, 37, 42, 52, 62, 72, 82, 92, 102, 117, 122, 127, 142, 147, 157, 162, 167,
        182, 192, 202, 207, 212, 222, 232, 240, 242, 252, 262, 267, 272, 282, 287, 294, 297, 302,
        317, 322, 332, 342, 357, 362, 372, 382, 387, 392, 402, 412, 414, 422, 432, 442, 462, 474,
        477, 482, 492, 502, 512, 517, 522, 562, 572, 582, 594, 602, 612, 617, 622, 632, 642, 647,
        654, 657, 662, 672, 677, 682, 692, 702,
    )

    private fun times(minutes: List<Int>): List<Long> = minutes.map { start + it * 60_000L }

    @Test
    fun `a forty-minute hole breaks a ten-minute series`() {
        // The incident shape: median cadence 10 minutes floors the bridge at 30, so 40 breaks.
        val minutes = listOf(0, 10, 20, 30, 40, 50, 60, 100, 110, 120)
        val coverage = CloudActualSeries.coverage(times(minutes))!!

        assertEquals(30 * 60_000L, coverage.bridgeMs)
        assertEquals(40 * 60_000L, coverage.largestGapMs)
        assertTrue(coverage.breaks)
    }

    @Test
    fun `the same forty-minute hole does not break a twenty-minute series`() {
        // 2 x 20 = 40, and the split is strictly greater-than, so this draws as one line. The gate
        // must agree: re-fetching here would be chasing a curve that is not broken.
        val minutes = listOf(0, 20, 40, 60, 80, 100, 140, 160, 180, 200)
        val coverage = CloudActualSeries.coverage(times(minutes))!!

        assertEquals(40 * 60_000L, coverage.bridgeMs)
        assertEquals(40 * 60_000L, coverage.largestGapMs)
        assertFalse(coverage.breaks)
    }

    @Test
    fun `coverage agrees with segments on the same points`() {
        // The gate and the drawn line must never disagree about what a break is.
        val minutes = listOf(0, 10, 20, 30, 40, 50, 60, 100, 110, 120)
        val points = times(minutes).map { TimedCloudCover(it, 50) }

        assertEquals(CloudActualSeries.segments(points).size > 1, CloudActualSeries.coverage(times(minutes))!!.breaks)
    }

    @Test
    fun `samsung 2026-09-03 carrier times break and the emulator's do not`() {
        val samsung = CloudActualSeries.coverage(times(samsungCarrierMinutes))!!
        assertEquals("median cadence was 10 minutes", 30 * 60_000L, samsung.bridgeMs)
        assertEquals("the 11:35 to 12:15 hole", 40 * 60_000L, samsung.largestGapMs)
        assertTrue("this is the curve the user saw split", samsung.breaks)

        // The emulator's KSJC feed filled every 5 minutes through the same window.
        val emulator = CloudActualSeries.coverage(times((0..140).map { it * 5 }))!!
        assertFalse(emulator.breaks)
    }

    @Test
    fun `duplicate timestamps from several stations do not fake a dense series`() {
        // Three stations reporting on each mark is not a 0-minute cadence. Counting the duplicate
        // steps would drag the median to zero, floor the bridge at 30 minutes for every series, and
        // — worse — make a genuinely sparse hourly series look like it had broken.
        val marks = listOf(0, 10, 20, 30, 40, 50, 60, 100)
        val minutes = marks.flatMap { listOf(it, it, it) }
        val coverage = CloudActualSeries.coverage(times(minutes))!!

        assertEquals(30 * 60_000L, coverage.bridgeMs)
        assertEquals(40 * 60_000L, coverage.largestGapMs)
        assertTrue(coverage.breaks)
        assertEquals(CloudActualSeries.coverage(times(marks)), coverage)
    }

    @Test
    fun `a two-point series cannot break on its only step`() {
        // The bridge is twice the median, and with one step the median IS that step. An hourly
        // station reporting twice is sparse, not broken — there is no evidence of a missed report.
        val coverage = CloudActualSeries.coverage(times(listOf(0, 60)))!!

        assertEquals(120 * 60_000L, coverage.bridgeMs)
        assertFalse(coverage.breaks)
    }

    @Test
    fun `fewer than two distinct points has nothing to say about gaps`() {
        assertNull(CloudActualSeries.coverage(emptyList()))
        assertNull(CloudActualSeries.coverage(times(listOf(0))))
        assertNull(CloudActualSeries.coverage(times(listOf(7, 7, 7))))
    }
}

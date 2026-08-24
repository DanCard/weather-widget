package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
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
}

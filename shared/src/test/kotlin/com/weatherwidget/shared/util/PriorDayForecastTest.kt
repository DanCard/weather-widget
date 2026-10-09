package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Yesterday's forecast" for day D: low from the newest fetch before 06:00 on D−1, high from the
 * newest before 16:00 on D−1. See plans/261005-past-days-triple-bar-prior-forecast-at-cutoffs.md.
 */
@Category(ShortDuration::class)
class PriorDayForecastTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 10, 5)
    private val hour = 3_600_000L

    private data class Row(val fetchedAt: Long, val high: Float?, val low: Float?)

    /** Epoch ms at [h]:[m] local on the day before [day] (plus [plusDays]). */
    private fun prev(h: Int, m: Int = 0, plusDays: Long = 0) =
        day.minusDays(1).plusDays(plusDays).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun select(rows: List<Row>, fallback: Boolean = false, date: LocalDate = day, z: ZoneId = zone) =
        PriorDayForecast.select(rows, date, z, { it.fetchedAt }, { it.high }, { it.low }, fallback)

    @Test
    fun `low and high come from different anchors`() {
        val rows = listOf(
            Row(prev(2), 80f, 55f),
            Row(prev(5, 59), 81f, 56f), // newest before 06:00 → low
            Row(prev(6, 0), 82f, 57f), // AT the low cutoff: not before it
            Row(prev(15, 59), 83f, 58f), // newest before 16:00 → high
            Row(prev(16, 0), 84f, 59f),
            Row(prev(22), 85f, 60f),
        )
        val pick = select(rows)
        assertEquals(56f, pick.lowRow!!.low)
        assertEquals(83f, pick.highRow!!.high)
    }

    @Test
    fun `a side missing from the newest row is taken from an older one`() {
        // An NWS batch whose low has dropped out: the high still comes from it, the low from earlier.
        val rows = listOf(Row(prev(4), 80f, 55f), Row(prev(5), 81f, null))
        val pick = select(rows)
        assertEquals(55f, pick.lowRow!!.low)
        assertEquals(81f, pick.highRow!!.high)
    }

    @Test
    fun `collapsed rows are skipped`() {
        val rows = listOf(Row(prev(3), 80f, 55f), Row(prev(5), 74f, 74f))
        val pick = select(rows)
        assertEquals(80f, pick.highRow!!.high)
        assertEquals(55f, pick.lowRow!!.low)
    }

    @Test
    fun `history has no fallback when nothing precedes the cutoff`() {
        val rows = listOf(Row(prev(20), 80f, 55f))
        val pick = select(rows, fallback = false)
        assertTrue(pick.isEmpty)
    }

    @Test
    fun `today falls back to the earliest usable row per side`() {
        val rows = listOf(Row(prev(20), 80f, 55f), Row(prev(23), 81f, 56f))
        val pick = select(rows, fallback = true)
        assertEquals(80f, pick.highRow!!.high)
        assertEquals(55f, pick.lowRow!!.low)
    }

    @Test
    fun `a fetch more than 48h before the cutoff is no pick, and today's fallback skips it too`() {
        // Pixel 2026-10-09: an Oct 2 fetch (90/74) stood as Oct 9's yesterday's forecast.
        val weekOld = Row(prev(11) - 6 * 24 * hour, 90f, 74f)
        val late = Row(prev(20), 69.5f, 56f)
        assertTrue(select(listOf(weekOld, late)).isEmpty)
        val today = select(listOf(weekOld, late), fallback = true)
        assertEquals(69.5f, today.highRow!!.high)
        assertEquals(56f, today.lowRow!!.low)
        assertTrue(select(listOf(weekOld), fallback = true).isEmpty)
    }

    @Test
    fun `a fetch just inside 48h before the cutoff still counts`() {
        val row = Row(prev(16) - 47 * hour, 88f, 60f)
        assertEquals(88f, select(listOf(row)).highRow!!.high)
    }

    @Test
    fun `fallback is per side`() {
        // Low has a pre-06:00 row; high's only candidates before 16:00 lack a high.
        val rows = listOf(Row(prev(5), null, 55f), Row(prev(20), 82f, 57f))
        val pick = select(rows, fallback = true)
        assertEquals(55f, pick.lowRow!!.low)
        assertEquals(82f, pick.highRow!!.high)
        assertNull(select(rows, fallback = false).highRow)
    }

    @Test
    fun `cutoffs are local wall-clock times across DST`() {
        // 2026-11-01 is the fall-back day in Los Angeles: a 25h day. Anchors are still 06:00/16:00.
        val dstNext = LocalDate.of(2026, 11, 2)
        val low = PriorDayForecast.lowCutoffMs(dstNext, zone)
        val high = PriorDayForecast.highCutoffMs(dstNext, zone)
        assertEquals(LocalDate.of(2026, 11, 1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli(), low)
        assertEquals(10 * hour, high - low)
        // Spring-forward (2026-03-08): still 06:00 local.
        val springNext = LocalDate.of(2026, 3, 9)
        assertEquals(
            LocalDate.of(2026, 3, 8).atTime(6, 0).atZone(zone).toInstant().toEpochMilli(),
            PriorDayForecast.lowCutoffMs(springNext, zone),
        )
    }

    @Test
    fun `non-US zone anchors in that zone`() {
        val kyiv = ZoneId.of("Europe/Kyiv")
        val atFiveKyiv = day.minusDays(1).atTime(5, 0).atZone(kyiv).toInstant().toEpochMilli()
        val atSevenKyiv = day.minusDays(1).atTime(7, 0).atZone(kyiv).toInstant().toEpochMilli()
        val pick = select(listOf(Row(atFiveKyiv, 70f, 50f), Row(atSevenKyiv, 71f, 51f)), z = kyiv)
        assertEquals(50f, pick.lowRow!!.low)
        assertEquals(71f, pick.highRow!!.high)
    }

    @Test
    fun `stale when last confirmed more than 24h before the cutoff`() {
        val cutoff = PriorDayForecast.highCutoffMs(day, zone)
        assertFalse(PriorDayForecast.isStale(cutoff - 23 * hour - 59 * 60_000L, cutoff))
        assertFalse(PriorDayForecast.isStale(cutoff - 24 * hour, cutoff))
        assertTrue(PriorDayForecast.isStale(cutoff - 24 * hour - 60_000L, cutoff))
        // A fallback row fetched after its cutoff is not stale.
        assertFalse(PriorDayForecast.isStale(cutoff + 5 * hour, cutoff))
    }

    private data class HRow(val fetchedAt: Long, val high: Float?, val low: Float?, val hHigh: Float? = null, val hLow: Float? = null)

    private fun resolve(frozenHigh: Float?, frozenLow: Float?, rows: List<Row>) =
        PriorDayForecast.resolvePast(frozenHigh, frozenLow, rows, day, zone, { it.fetchedAt }, { it.high }, { it.low })

    private fun real(h: Float, l: Float) = PriorDayForecast.Past(h, l, isFallback = false)
    private fun fallback(h: Float, l: Float) = PriorDayForecast.Past(h, l, isFallback = true)

    private val anchored = listOf(Row(prev(5), 81f, 56f), Row(prev(15), 83f, 58f), Row(prev(20), 90f, 60f))

    @Test
    fun `resolvePast prefers the frozen pair`() {
        assertEquals(real(70f, 50f), resolve(70f, 50f, anchored))
    }

    @Test
    fun `resolvePast picks live before the freeze has run`() {
        assertEquals(real(83f, 56f), resolve(null, null, anchored))
    }

    @Test
    fun `resolvePast fills only the missing side`() {
        assertEquals(real(70f, 56f), resolve(70f, null, anchored))
    }

    @Test
    fun `a side fetched after its anchor is a dashed fallback from the earliest such row`() {
        // Source first fetched at 11:16 on D-1 (Google, 2026-10-06): the high anchor (16:00) is met,
        // the low anchor (06:00) is not — the earliest row stands in for the low.
        val rows = listOf(Row(prev(11, 16), 82f, 59f), Row(prev(14, 45), 84f, 58f), Row(prev(20), 84f, 58f))
        assertEquals(fallback(84f, 59f), resolve(null, null, rows))
        // Frozen high + late low: still a fallback.
        assertEquals(fallback(70f, 60f), resolve(70f, null, listOf(Row(prev(20), 90f, 60f))))
    }

    @Test
    fun `the earliest post-cutoff value is the last resort, and nothing still draws nothing`() {
        val rows = listOf(
            HRow(prev(9, plusDays = 1), 83f, null, hLow = 61f),
            HRow(prev(12, plusDays = 1), 83f, null, hLow = 60f),
        )
        val past = PriorDayForecast.resolvePast(
            null, null, rows, day, zone, { it.fetchedAt }, { it.high }, { it.low }, { it.hHigh }, { it.hLow },
        )
        assertEquals(fallback(83f, 61f), past)
        assertNull(resolve(null, null, emptyList()))
        assertNull(resolve(null, null, listOf(Row(prev(20), 90f, null))))
    }
}

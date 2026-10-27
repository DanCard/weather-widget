package com.weatherwidget.data.local

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.shared.graph.CloudBands
import com.weatherwidget.shared.graph.PriorDayBandForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.ZoneId
import kotlin.random.Random

/**
 * [HistorySnapshotRetention] is only correct if every reader returns exactly what it returned on the
 * full history. These run the REAL readers (stitcher, prior-day band) on generated snapshot stacks —
 * sites inside the same-site and borrow radii and beyond them, fetchedAt ties, random nulls in every
 * coalesced field, live-table overlap — and compare full vs pruned. (Rain accuracy's reducer lives in
 * :app; see HistorySnapshotRetentionRainTest there.)
 */
@Category(ShortDuration::class)
class HistorySnapshotRetentionTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val hourMs = 3_600_000L
    private val centre = 52.2334 to 20.9703
    private val t0 = 1_790_640_000_000L // 2026-09-29T08:00Z, hour-aligned

    private fun generate(seed: Int, postHour: Boolean = false): List<HistorySnapshotRetention.Row> {
        val rnd = Random(seed)
        // Offsets: same spot, jitter (<0.002), borrow range (<0.01), and a different town (0.05).
        val siteOffsets = listOf(0.0, 0.0012, 0.006, 0.05).shuffled(rnd).take(1 + rnd.nextInt(4))
        val sources = listOf("OPEN_METEO", "NWS", "SILURIAN").take(1 + rnd.nextInt(3))
        val rows = mutableListOf<HistorySnapshotRetention.Row>()
        for (source in sources) for (off in siteOffsets) {
            val lat = centre.first + off
            val lon = centre.second - off / 2
            val hours = (0 until 6).map { t0 + (it - 2) * 24 * hourMs + rnd.nextInt(24) * hourMs }.distinct()
            for (hour in hours) {
                val depth = 1 + rnd.nextInt(35)
                // postHour adds hindcasts: fetches up to a day after the hour, which the settled pick
                // must ignore for a started hour and retention must therefore not keep in its place.
                val buckets = (0 until depth).map {
                    if (postHour && rnd.nextInt(4) == 0) hour + rnd.nextInt(24) * hourMs + 60_000L
                    else hour - (1 + rnd.nextInt(96)) * hourMs
                }.distinct()
                for (b in buckets) {
                    // fetchedAt usually tracks the bucket, sometimes ties or inverts.
                    val fetchedAt = when (rnd.nextInt(6)) {
                        0 -> hour - 10 * hourMs
                        1 -> b + rnd.nextInt(3) * 60_000L
                        else -> b + 5 * 60_000L
                    }
                    fun <T> maybe(v: T): T? = if (rnd.nextInt(3) == 0) null else v
                    rows += HistorySnapshotRetention.Row(
                        source = source, dateTime = hour, lat = lat, lon = lon, bucket = b, fetchedAt = fetchedAt,
                        cloudCover = maybe(rnd.nextInt(101)), cloudCoverLow = maybe(rnd.nextInt(101)),
                        cloudCoverMid = maybe(rnd.nextInt(101)), cloudCoverHigh = maybe(rnd.nextInt(101)),
                        precipProbability = maybe(rnd.nextInt(101)), precipAmountMm = maybe(rnd.nextFloat()),
                    )
                }
            }
        }
        return rows
    }

    /** DAO order: `ORDER BY dateTime ASC, timestampToGroupPredictions DESC`, ties by site. */
    private fun daoOrder(rows: List<HistorySnapshotRetention.Row>) =
        rows.sortedWith(compareBy<HistorySnapshotRetention.Row> { it.dateTime }.thenByDescending { it.bucket }
            .thenBy { it.lat }.thenBy { it.lon }.thenBy { it.source })

    private fun HistorySnapshotRetention.Row.toHourly() = HourlyForecast(
        dateTime = dateTime, temperature = (bucket % 97).toFloat(), condition = "c$bucket", source = source,
        fetchedAt = fetchedAt, locationLat = lat, locationLon = lon, cloudCover = cloudCover,
        cloudCoverLow = cloudCoverLow, cloudCoverMid = cloudCoverMid, cloudCoverHigh = cloudCoverHigh,
        precipProbability = precipProbability, precipAmountMm = precipAmountMm,
    )

    private fun pruned(rows: List<HistorySnapshotRetention.Row>): List<HistorySnapshotRetention.Row> {
        // Pinned to t0: on the wall clock these hours age out of the active tier the tests exercise.
        val keep = HistorySnapshotRetention.keptKeys(rows, zone, nowMs = t0)
        return rows.filter { it.key in keep }
    }

    @Test
    fun `stitched hourly series is identical on full and pruned history`() {
        repeat(300) { seed ->
            val full = generate(seed)
            val kept = pruned(full)
            // Some live rows too: they win per hour, history fills gaps and null fields.
            val live = full.filter { it.bucket % 7 == 0L }.map { it.toHourly() }
            val expected = HourlyForecastStitcher.stitchBySource(live, daoOrder(full).map { it.toHourly() }, 0L, centre.first, centre.second)
            val actual = HourlyForecastStitcher.stitchBySource(live, daoOrder(kept).map { it.toHourly() }, 0L, centre.first, centre.second)
            assertEquals("seed=$seed", expected, actual)
        }
    }

    /**
     * The same equivalence with a real "now" ([t0]) and post-hour hindcasts in the stacks, so hours
     * before t0 take the settled (pre-hour) pick. The test above passes nowMs = 0, which treats every
     * hour as not started — the gap that let 2a3293ef read pre-hour rows retention did not keep.
     */
    @Test
    fun `stitched hourly series is identical on full and pruned history for started hours`() {
        repeat(300) { seed ->
            val full = generate(seed, postHour = true)
            val kept = pruned(full)
            val live = full.filter { it.bucket % 7 == 0L }.map { it.toHourly() }
            val expected = HourlyForecastStitcher.stitchBySource(live, daoOrder(full).map { it.toHourly() }, t0, centre.first, centre.second)
            val actual = HourlyForecastStitcher.stitchBySource(live, daoOrder(kept).map { it.toHourly() }, t0, centre.first, centre.second)
            assertEquals("seed=$seed", expected, actual)
        }
    }

    /** Desktop DB, NWS 2026-10-25 12:00: last pre-noon fetch 11:44, newest 12:01 — neither rule 1–4 kept 11:44. */
    @Test
    fun `the newest pre-hour snapshot survives behind a post-hour hindcast`() {
        val noon = t0 + 4 * hourMs
        val minute = 60_000L
        val early = HistorySnapshotRetention.Row("NWS", noon, 52.2334, 20.9703, noon - 6 * hourMs, noon - 6 * hourMs + 5 * minute)
        val preNoon = HistorySnapshotRetention.Row("NWS", noon, 52.2334, 20.9703, noon - 16 * minute, noon - 16 * minute)
        val hindcast = HistorySnapshotRetention.Row("NWS", noon, 52.2334, 20.9703, noon + minute, noon + minute)

        val keep = HistorySnapshotRetention.keptKeys(listOf(early, preNoon, hindcast), zone, nowMs = noon + hourMs)

        assertTrue(preNoon.key in keep)
        assertTrue(early.key !in keep)
    }

    @Test
    fun `with no pre-hour snapshot the earliest is kept`() {
        val hour = t0 + 4 * hourMs
        val first = HistorySnapshotRetention.Row("NWS", hour, 52.2334, 20.9703, hour + hourMs, hour + hourMs)
        val second = HistorySnapshotRetention.Row("NWS", hour, 52.2334, 20.9703, hour + 3 * hourMs, hour + 3 * hourMs)
        val third = HistorySnapshotRetention.Row("NWS", hour, 52.2334, 20.9703, hour + 5 * hourMs, hour + 5 * hourMs)

        val keep = HistorySnapshotRetention.keptKeys(listOf(first, second, third), zone, nowMs = hour + 6 * hourMs)

        assertTrue(first.key in keep)
        assertTrue(second.key !in keep)
    }

    @Test
    fun `prior-day band is identical on full and pruned history`() {
        fun band(rows: List<HistorySnapshotRetention.Row>, source: String) = PriorDayBandForecast.select(
            rows.filter { it.source == source && LocationMatch.sameSite(centre.first, centre.second, it.lat, it.lon) }
                .map { PriorDayBandForecast.BandSnapshot(it.dateTime, it.bucket, CloudBands(mid = it.cloudCoverMid, high = it.cloudCoverHigh)) },
        )
        repeat(300) { seed ->
            val full = generate(seed)
            val kept = pruned(full)
            for (source in full.map { it.source }.distinct()) assertEquals("seed=$seed $source", band(full, source), band(kept, source))
        }
    }

    @Test
    fun `deep stacks shrink to a handful of rows per hour`() {
        val full = (0 until 200).flatMap { seed -> generate(seed) }
        val kept = pruned(full)
        val groups = full.map { listOf(it.source, it.dateTime, it.lat, it.lon) }.distinct().size
        assertTrue("kept ${kept.size} of ${full.size}", kept.size < full.size / 2)
        // At most: 2 newest + 6 fields + band + prior-day, before ties.
        assertTrue("kept per group ${kept.size.toDouble() / groups}", kept.size.toDouble() / groups <= 10.0)
    }

    @Test
    fun `a single snapshot is always kept`() {
        val row = HistorySnapshotRetention.Row("OPEN_METEO", t0, 52.0, 21.0, t0 - hourMs, t0 - hourMs)
        assertEquals(setOf(row.key), HistorySnapshotRetention.keptKeys(listOf(row), zone))
    }

    @Test
    fun `hours past 30 days are collapsed to strictly one row per hour per city cluster`() {
        val pastHour = t0 - 40 * 24 * hourMs // 40 days in the past
        // 5 snapshots for the same past hour, across slightly different coordinates in the same city
        val rows = listOf(
            HistorySnapshotRetention.Row("OPEN_METEO", pastHour, 49.841, 24.031, pastHour - 5 * hourMs, pastHour - 4 * hourMs),
            HistorySnapshotRetention.Row("OPEN_METEO", pastHour, 49.843, 24.032, pastHour - 3 * hourMs, pastHour - 2 * hourMs), // newest pre-hour: winner
            HistorySnapshotRetention.Row("OPEN_METEO", pastHour, 49.840, 24.029, pastHour - 1 * hourMs, pastHour + 10_000L), // post-hour hindcast
            HistorySnapshotRetention.Row("NWS", pastHour, 49.841, 24.031, pastHour - 5 * hourMs, pastHour - 4 * hourMs), // NWS winner
            HistorySnapshotRetention.Row("NWS", pastHour, 49.842, 24.030, pastHour - 1 * hourMs, pastHour + 20_000L), // post-hour hindcast
        )
        val keep = HistorySnapshotRetention.keptKeys(rows, zone, nowMs = t0)
        // Exactly 2 rows kept, each the stitcher's settled pick: the newest fetched at or before the hour.
        assertEquals(2, keep.size)
        assertTrue(keep.contains(rows[1].key))
        assertTrue(keep.contains(rows[3].key))
    }

    @Test
    fun `settled archive keeps distinct city clusters that are far apart`() {
        val pastHour = t0 - 45 * 24 * hourMs
        // Lviv (49.84, 24.03) vs Kyiv (50.45, 30.52) - hundreds of km apart
        val rows = listOf(
            HistorySnapshotRetention.Row("OPEN_METEO", pastHour, 49.841, 24.031, pastHour - 2 * hourMs, pastHour + 1000L),
            HistorySnapshotRetention.Row("OPEN_METEO", pastHour, 50.451, 30.521, pastHour - 2 * hourMs, pastHour + 2000L),
        )
        val keep = HistorySnapshotRetention.keptKeys(rows, zone, nowMs = t0)
        assertEquals(2, keep.size)
        assertTrue(keep.contains(rows[0].key))
        assertTrue(keep.contains(rows[1].key))
    }
}

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

    private fun generate(seed: Int): List<HistorySnapshotRetention.Row> {
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
                val buckets = (0 until depth).map { hour - (1 + rnd.nextInt(96)) * hourMs }.distinct()
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
        val keep = HistorySnapshotRetention.keptKeys(rows, zone)
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
}

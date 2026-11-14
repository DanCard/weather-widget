package com.weatherwidget.data.local

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HindcastGuard
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
            val expected = HourlyForecastStitcher.stitchBySource(live + daoOrder(full).map { it.toHourly() }, centre.first, centre.second, 0L)
            val actual = HourlyForecastStitcher.stitchBySource(live + daoOrder(kept).map { it.toHourly() }, centre.first, centre.second, 0L)
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
        // The guard's age is a user setting, so one prune must serve every value of it.
        // plans/261113-no-hindcasts-unless-prior-missing.md
        val saved = HindcastGuard.maxPriorAgeHours
        try {
            for (hours in listOf(0, 1, 6, 48)) {
                HindcastGuard.setMaxPriorAgeHours(hours)
                repeat(300) { seed ->
                    val full = generate(seed, postHour = true)
                    val kept = pruned(full)
                    val live = full.filter { it.bucket % 7 == 0L }.map { it.toHourly() }
                    val expected = HourlyForecastStitcher.stitchBySource(live + daoOrder(full).map { it.toHourly() }, centre.first, centre.second, t0)
                    val actual = HourlyForecastStitcher.stitchBySource(live + daoOrder(kept).map { it.toHourly() }, centre.first, centre.second, t0)
                    assertEquals("T=${hours}h seed=$seed", expected, actual)
                }
                println("T=${hours}h: 300 seeds identical after prune")
            }
        } finally {
            HindcastGuard.setMaxPriorAgeHours(saved)
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
        assertEquals(setOf(row.key), HistorySnapshotRetention.keptKeys(listOf(row), zone, nowMs = t0))
    }

    @Test
    fun `past 5 days the pre-hour pick and the first hindcast both stay, for any guard age`() {
        val hour = t0 - 6 * 24 * hourMs
        val preHour = HistorySnapshotRetention.Row("OPEN_METEO", hour, 49.84, 24.03, hour - 2 * hourMs, hour - 2 * hourMs)
        val hindcast = HistorySnapshotRetention.Row("OPEN_METEO", hour, 49.84, 24.03, hour + 2 * hourMs, hour + 2 * hourMs)

        // preHour is the pick while it counts as fresh; the hindcast stands in when the guard's age
        // is set below 2 h or to 0 (off). The prune cannot know the setting, so it keeps both.
        assertEquals(setOf(preHour.key, hindcast.key), HistorySnapshotRetention.keptKeys(listOf(preHour, hindcast), zone, nowMs = t0))
    }

    @Test
    fun `inside 5 days the newest hindcast stays beside the settled pick`() {
        val hour = t0 - 4 * 24 * hourMs
        val preHour = HistorySnapshotRetention.Row("OPEN_METEO", hour, 49.84, 24.03, hour - 2 * hourMs, hour - 2 * hourMs)
        val hindcast = HistorySnapshotRetention.Row("OPEN_METEO", hour, 49.84, 24.03, hour + 2 * hourMs, hour + 2 * hourMs)

        assertEquals(
            setOf(preHour.key, hindcast.key),
            HistorySnapshotRetention.keptKeys(listOf(preHour, hindcast), zone, nowMs = t0),
        )
    }

    @Test
    fun `past 5 days the rain and band readers keep their rows`() {
        val hour = t0 - 20 * 24 * hourMs
        val day = java.time.Instant.ofEpochMilli(hour).atZone(zone).toLocalDate()
        val priorDay = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val dayAhead = HistorySnapshotRetention.Row(
            "OPEN_METEO", hour, 49.84, 24.03, priorDay, priorDay, cloudCoverMid = 30, precipProbability = 40,
        )
        val preHour = HistorySnapshotRetention.Row("OPEN_METEO", hour, 49.84, 24.03, hour - hourMs, hour - hourMs)

        val keep = HistorySnapshotRetention.keptKeys(listOf(dayAhead, preHour), zone, nowMs = t0)

        assertTrue(dayAhead.key in keep)
        assertTrue(preHour.key in keep)
    }

    @Test
    fun `hours past the retained window keep nothing`() {
        val hour = t0 - (RetentionPolicy.HOURLY_FORECAST_DAYS + 1) * 24 * hourMs
        val row = HistorySnapshotRetention.Row("OPEN_METEO", hour, 49.84, 24.03, hour - hourMs, hour - hourMs)

        assertTrue(HistorySnapshotRetention.keptKeys(listOf(row), zone, nowMs = t0).isEmpty())
    }

    @Test
    fun `retention outlasts every reader that looks back`() {
        // Rain accuracy starts at local midnight 30 calendar days back; the cloud curve reaches 31.
        assertTrue(
            RetentionPolicy.HOURLY_FORECAST_DAYS * 24 >
                com.weatherwidget.shared.graph.PriorDayCloudRun.PAST_DAYS * 24,
        )
        assertTrue(RetentionPolicy.HOURLY_FORECAST_DAYS * 24 > -com.weatherwidget.util.NavigationUtils.MIN_HOURLY_OFFSET)
    }

    /**
     * Every head the pick can choose stays: the pre-hour row (fresh prior), the first hindcast (prior
     * stale for a short guard age), and the newest (guard off). plans/261113-no-hindcasts-unless-prior-missing.md
     */
    @Test
    fun `the pre-hour row, the first hindcast and the newest row all stay`() {
        val hour = 1_790_640_000_000L
        fun row(fetchedAt: Long, cloud: Int?) = HistorySnapshotRetention.Row(
            source = "OPEN_METEO", dateTime = hour, lat = 37.42, lon = -122.08,
            bucket = fetchedAt, fetchedAt = fetchedAt, cloudCover = cloud,
        )
        val preHour = row(hour - 3_600_000L, cloud = 40)
        val hindcastWithCloud = row(hour + 3_600_000L, cloud = 50)
        val newest = row(hour + 7_200_000L, cloud = null)

        val kept = HistorySnapshotRetention.keptKeys(
            listOf(preHour, hindcastWithCloud, newest),
            ZoneId.of("America/Los_Angeles"),
            nowMs = hour + 24 * 3_600_000L,
        )

        assertEquals(setOf(preHour.key, hindcastWithCloud.key, newest.key), kept)
    }
}

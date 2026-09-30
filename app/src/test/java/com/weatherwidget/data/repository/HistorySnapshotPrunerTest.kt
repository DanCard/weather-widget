package com.weatherwidget.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.HistorySnapshotRetention
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.stats.RainAccuracyCalculator
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * The prune on a real Room database (HistorySnapshotPruner + HourlyForecastHistoryDao + the shared
 * rule), and the rain-accuracy reader — the one [HistorySnapshotRetention] reader that lives in :app —
 * on full vs pruned rows. The stitcher and prior-day band equivalences are in :shared.
 */
@Category(LongDuration::class)
class HistorySnapshotPrunerTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private val zone = ZoneId.of("Europe/Warsaw")
    private val hourMs = 3_600_000L
    private val t0 = 1_790_640_000_000L // 2026-09-29T08:00Z

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Deep stacks at two sites, across several local days (including hours near midnight). */
    private fun generate(seed: Int): List<HourlyForecastHistoryEntity> {
        val rnd = Random(seed)
        val rows = LinkedHashMap<String, HourlyForecastHistoryEntity>()
        for (source in listOf("OPEN_METEO", "NWS")) for (site in listOf(52.2334 to 20.9703, 52.2346 to 20.9711)) {
            val hours = (0 until 8).map { t0 + (it - 3) * 24 * hourMs + rnd.nextInt(24) * hourMs }.distinct()
            for (hour in hours) repeat(1 + rnd.nextInt(30)) {
                val b = hour - (1 + rnd.nextInt(96)) * hourMs
                fun <T> maybe(v: T): T? = if (rnd.nextInt(3) == 0) null else v
                val e = HourlyForecastHistoryEntity(
                    dateTime = hour, locationLat = site.first, locationLon = site.second, temperature = 60f,
                    condition = "Clear", source = source, timestampToGroupPredictions = b,
                    precipProbability = maybe(rnd.nextInt(101)), cloudCover = maybe(rnd.nextInt(101)),
                    precipAmountMm = maybe(rnd.nextFloat()), fetchedAt = b + 60_000L,
                    cloudCoverLow = maybe(rnd.nextInt(101)), cloudCoverMid = maybe(rnd.nextInt(101)),
                    cloudCoverHigh = maybe(rnd.nextInt(101)),
                )
                rows["${e.dateTime}|${e.source}|${e.locationLat}|${e.locationLon}|${e.timestampToGroupPredictions}"] = e
            }
        }
        return rows.values.toList()
    }

    private fun HourlyForecastHistoryEntity.key() = toRetentionRow().key

    @Test
    fun `the prune leaves exactly the spec's keep set`() = runTest {
        repeat(5) { seed ->
            db.clearAllTables()
            val full = generate(seed)
            db.hourlyForecastHistoryDao().insertAll(full)
            val expected = HistorySnapshotRetention.keptKeys(full.map { it.toRetentionRow() }, zone)

            val result = HistorySnapshotPruner(db.hourlyForecastHistoryDao(), db.appLogDao(), zone = { zone }).prune()

            val remaining = db.hourlyForecastHistoryDao().getAllInDateTimeRange(Long.MIN_VALUE, Long.MAX_VALUE)
            assertEquals("seed=$seed", expected, remaining.map { it.key() }.toSet())
            assertEquals(full.size - expected.size, result.deleted)
            assertTrue("seed=$seed deep stacks must shrink", result.deleted > 0)
        }
    }

    @Test
    fun `a second prune deletes nothing`() = runTest {
        db.hourlyForecastHistoryDao().insertAll(generate(7))
        val pruner = HistorySnapshotPruner(db.hourlyForecastHistoryDao(), db.appLogDao(), zone = { zone })
        pruner.prune()
        assertEquals(0, pruner.prune().deleted)
    }

    @Test
    fun `a scoped pass visits only days that received new snapshots, and still matches the spec`() = runTest {
        val dao = db.hourlyForecastHistoryDao()
        val old = generate(21)
        dao.insertAll(old)
        val pruner = HistorySnapshotPruner(dao, db.appLogDao(), zone = { zone })
        pruner.prune()

        // New fetch: fresh snapshots for hours of ONE local day, fetched after the last pass.
        val lastPass = old.maxOf { it.fetchedAt } + 1
        val hour = old.first().dateTime
        val fresh = (1..5).map { k ->
            old.first().copy(timestampToGroupPredictions = hour - k * 60_000L, fetchedAt = lastPass + k)
        }
        dao.insertAll(fresh)

        val result = pruner.prune(touchedSinceFetchedAt = lastPass)

        assertEquals("only the touched day is visited", 1, result.days)
        // Fixpoint: what is left is exactly what the spec keeps of what is left.
        val all = dao.getAllInDateTimeRange(Long.MIN_VALUE, Long.MAX_VALUE)
        assertTrue("the fresh snapshots were considered", result.scanned > 0)
        assertEquals(
            HistorySnapshotRetention.keptKeys(all.map { it.toRetentionRow() }, zone),
            all.map { it.key() }.toSet(),
        )
    }

    @Test
    fun `the prune logs HISTORY_PRUNE`() = runTest {
        db.hourlyForecastHistoryDao().insertAll(generate(3))
        HistorySnapshotPruner(db.hourlyForecastHistoryDao(), db.appLogDao(), zone = { zone }).prune()
        val logs: List<AppLogEntity> = db.appLogDao().getRecentLogs(20)
        assertTrue(logs.any { it.tag == "HISTORY_PRUNE" && "deleted=" in it.message })
    }

    @Test
    fun `rain accuracy's prior-day pick is identical on full and pruned rows`() {
        repeat(200) { seed ->
            val full = generate(seed)
            val keep = HistorySnapshotRetention.keptKeys(full.map { it.toRetentionRow() }, zone)
            val kept = full.filter { it.key() in keep }
            // Exactly RainAccuracyCalculator's query: hours of `date`, snapshots captured the day before.
            val dates = full.map { Instant.ofEpochMilli(it.dateTime).atZone(zone).toLocalDate() }.distinct()
            for (source in listOf("OPEN_METEO", "NWS")) for (date in dates) {
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val prevStart = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                fun window(rows: List<HourlyForecastHistoryEntity>) = rows.filter {
                    it.source == source && it.dateTime in dayStart until dayEnd &&
                        it.timestampToGroupPredictions in prevStart until dayStart
                }.sortedByDescending { it.timestampToGroupPredictions }
                assertEquals(
                    "seed=$seed $source $date",
                    RainAccuracyCalculator.latestSnapshotPrecipByHour(window(full), zone),
                    RainAccuracyCalculator.latestSnapshotPrecipByHour(window(kept), zone),
                )
            }
        }
    }

    @Test
    fun `gate - once a day, and only after the one-shot repairs`() {
        val day = HistorySnapshotPruner.MIN_INTERVAL_MS
        assertTrue(HistorySnapshotPruner.shouldPrune(nowMs = 10 * day, lastPruneMs = 0, repairsDone = true))
        assertFalse(HistorySnapshotPruner.shouldPrune(nowMs = 10 * day, lastPruneMs = 10 * day - 1, repairsDone = true))
        assertFalse(HistorySnapshotPruner.shouldPrune(nowMs = 10 * day, lastPruneMs = 0, repairsDone = false))
    }
}

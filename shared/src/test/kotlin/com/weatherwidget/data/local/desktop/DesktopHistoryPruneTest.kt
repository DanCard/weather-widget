package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.local.HistorySnapshotRetention
import com.weatherwidget.test.category.MediumDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.random.Random

/**
 * Desktop's JDBC prune on a real database leaves exactly [HistorySnapshotRetention]'s keep set.
 * (Reader equivalence of the rule itself is proven in HistorySnapshotRetentionTest.)
 */
@Category(MediumDuration::class)
class DesktopHistoryPruneTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private val zone = ZoneId.of("America/Los_Angeles")
    private val hourMs = 3_600_000L
    private val t0 = 1_790_640_000_000L

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("weather_prune_test", ".db")
        db = DesktopWeatherDatabase(tempDbPath)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(tempDbPath)
    }

    private fun seed(seed: Int): List<HistorySnapshotRetention.Row> {
        val rnd = Random(seed)
        val rows = LinkedHashMap<HistorySnapshotRetention.Key, HistorySnapshotRetention.Row>()
        for (source in listOf("NWS", "OPEN_METEO")) for (site in listOf(37.4166 to -122.0889, 37.4172 to -122.0880)) {
            val hours = (0 until 8).map { t0 + (it - 3) * 24 * hourMs + rnd.nextInt(24) * hourMs }.distinct()
            for (hour in hours) repeat(1 + rnd.nextInt(30)) {
                val b = hour - (1 + rnd.nextInt(96)) * hourMs
                fun maybe(v: Int): Int? = if (rnd.nextInt(3) == 0) null else v
                val r = HistorySnapshotRetention.Row(
                    source = source, dateTime = hour, lat = site.first, lon = site.second, bucket = b,
                    fetchedAt = if (rnd.nextInt(5) == 0) hour - 10 * hourMs else b + 60_000L,
                    cloudCover = maybe(rnd.nextInt(101)), cloudCoverLow = maybe(rnd.nextInt(101)),
                    cloudCoverMid = maybe(rnd.nextInt(101)), cloudCoverHigh = maybe(rnd.nextInt(101)),
                    precipProbability = maybe(rnd.nextInt(101)),
                    precipAmountMm = if (rnd.nextInt(3) == 0) null else rnd.nextFloat(),
                )
                rows[r.key] = r
            }
        }
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO hourly_forecast_history (dateTime, locationLat, locationLon, temperature, condition, source, " +
                    "timestampToGroupPredictions, precipProbability, cloudCover, cloudCoverLow, cloudCoverMid, cloudCoverHigh, " +
                    "precipAmountMm, fetchedAt) VALUES (?, ?, ?, 60, 'Clear', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { st ->
                for (r in rows.values) {
                    st.setLong(1, r.dateTime); st.setDouble(2, r.lat); st.setDouble(3, r.lon); st.setString(4, r.source)
                    st.setLong(5, r.bucket)
                    listOf(r.precipProbability, r.cloudCover, r.cloudCoverLow, r.cloudCoverMid, r.cloudCoverHigh)
                        .forEachIndexed { i, v -> if (v == null) st.setNull(6 + i, java.sql.Types.INTEGER) else st.setInt(6 + i, v) }
                    if (r.precipAmountMm == null) st.setNull(11, java.sql.Types.REAL) else st.setFloat(11, r.precipAmountMm!!)
                    st.setLong(12, r.fetchedAt)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
        return rows.values.toList()
    }

    private fun remainingKeys(): Set<HistorySnapshotRetention.Key> {
        val keys = mutableSetOf<HistorySnapshotRetention.Key>()
        db.getConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT dateTime, source, locationLat, locationLon, timestampToGroupPredictions FROM hourly_forecast_history").use { rs ->
                    while (rs.next()) keys += HistorySnapshotRetention.Key(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getLong(5))
                }
            }
        }
        return keys
    }

    @Test
    fun `desktop prune leaves exactly the spec's keep set, and is idempotent`() {
        val full = seed(11)
        val expected = HistorySnapshotRetention.keptKeys(full, zone)

        val result = dao.pruneHourlyHistorySnapshots(zone)

        assertEquals(expected, remainingKeys())
        assertEquals(full.size - expected.size, result.deleted)
        assertTrue(result.deleted > 0)
        assertEquals(0, dao.pruneHourlyHistorySnapshots(zone).deleted)
    }

    @Test
    fun `vacuum after a prune returns the freed pages to the filesystem`() {
        seed(5)
        dao.pruneHourlyHistorySnapshots(zone)
        val freed = dao.freelistBytes()
        val before = Files.size(tempDbPath)
        dao.vacuum()
        assertEquals(0L, dao.freelistBytes())
        assertTrue("file must shrink by the freed pages: before=$before after=${Files.size(tempDbPath)} freed=$freed",
            freed == 0L || Files.size(tempDbPath) < before)
    }
}

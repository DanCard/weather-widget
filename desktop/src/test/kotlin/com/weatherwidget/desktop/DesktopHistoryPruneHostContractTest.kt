package com.weatherwidget.desktop

import com.weatherwidget.data.local.HistoryPruneRun
import com.weatherwidget.data.local.HistoryPruneSchedule
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.HistoryPruneHostContract
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.experimental.categories.Category
import java.nio.file.Files

/** Desktop SQLite half of [HistoryPruneHostContract]; the Room half is `RoomHistoryPruneHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopHistoryPruneHostContractTest : HistoryPruneHostContract() {
    private val path = Files.createTempFile("weather_prune_contract", ".db")
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    override fun open(): HistoryPruneRun.Host {
        db = DesktopWeatherDatabase(path).apply { initialize() }
        dao = DesktopWeatherDao(db)
        return DesktopHistoryPruneHost(dao)
    }

    override suspend fun seed(rows: List<Seed>) = db.getConnection().use { conn ->
        conn.autoCommit = false
        conn.prepareStatement(
            "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, " +
                "timestampToGroupPredictions, fetchedAt) VALUES (?, 37.42, -122.08, 60, 'Clear', 'OPEN_METEO', ?, ?)",
        ).use { st ->
            rows.forEach {
                st.setLong(1, it.hourMs)
                st.setLong(2, it.labelMs)
                st.setLong(3, it.fetchedAtMs)
                st.addBatch()
            }
            st.executeBatch()
        }
        conn.commit()
    }

    override suspend fun rowCount(): Int = db.getConnection().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM hourly_forecasts").use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    override suspend fun lastCompleted(): Long =
        dao.getRecentLogsByTags(listOf(HistoryPruneSchedule.LAST_RUN_TAG), limit = 2).singleOrNull()?.timestamp ?: 0L

    override suspend fun logged(): List<String> =
        dao.getRecentLogsByTags(listOf(HistoryPruneSchedule.LOG_TAG), limit = 10).map { it.message }

    override suspend fun archived() = dao.getHourlyActuals(37.42, -122.08, "NWS", 0L, Long.MAX_VALUE)

    override suspend fun seedObservation(timestampMs: Long, fetchedAtMs: Long) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO observations (stationId, stationName, timestamp, temperature, condition, locationLat, " +
                    "locationLon, fetchedAt, api) VALUES ('KNUQ', 'KNUQ', ?, 60, 'Clear', 37.42, -122.08, ?, 'NWS')",
            ).use { st ->
                st.setLong(1, timestampMs)
                st.setLong(2, fetchedAtMs)
                st.executeUpdate()
            }
        }
    }

    @After
    fun deleteDatabase() {
        Files.deleteIfExists(path)
    }
}

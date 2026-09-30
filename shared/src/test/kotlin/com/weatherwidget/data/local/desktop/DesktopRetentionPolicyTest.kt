package com.weatherwidget.data.local.desktop

import com.weatherwidget.test.category.MediumDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/**
 * Desktop applies the shared RetentionPolicy — the same values as Android: daily_history 18 months,
 * network_usage 90 days, observations 10 days, app_logs 72 h (except permanent markers), everything
 * else 30 days. Each table is checked on both sides of its boundary.
 */
@Category(MediumDuration::class)
class DesktopRetentionPolicyTest {
    private lateinit var path: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private val now = 1_790_700_000_000L
    private val day = 24L * 3_600_000L

    @Before
    fun setUp() {
        path = Files.createTempFile("retention", ".db")
        db = DesktopWeatherDatabase(path)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(path)
    }

    private fun exec(sql: String) = db.getConnection().use { c -> c.createStatement().use { it.execute(sql) } }
    private fun count(sql: String): Int = db.getConnection().use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) } }
    }

    /** Inserts a row `ageDays` old; the table's other columns get fixed values. */
    private fun insert(table: String, ageDays: Double, id: Int) {
        val t = now - (ageDays * day).toLong()
        when (table) {
            "forecasts" -> exec("INSERT INTO forecasts (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, condition, source, batchFetchedAt, fetchedAt) VALUES ($id, $id, 1, 1, 1, 1, 'c', 'NWS', $t, $t)")
            "hourly_forecasts" -> exec("INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, fetchedAt) VALUES ($id, 1, 1, 1, 'c', 'NWS', $t)")
            "hourly_forecast_history" -> exec("INSERT INTO hourly_forecast_history (dateTime, locationLat, locationLon, temperature, condition, source, timestampToGroupPredictions, fetchedAt) VALUES ($id, 1, 1, 1, 'c', 'NWS', $id, $t)")
            "observations" -> exec("INSERT INTO observations (stationId, stationName, timestamp, temperature, condition, locationLat, locationLon, fetchedAt, api) VALUES ('S$id', 'S', $id, 1, 'c', 1, 1, $t, 'NWS')")
            "daily_history" -> exec("INSERT INTO daily_history (date, locationLat, locationLon, source, condition, updatedAt) VALUES ($id, 1, 1, 'NWS', 'c', $t)")
            "station_cache" -> exec("INSERT INTO station_cache (cacheKey, stations, updatedAt) VALUES ('k$id', '[]', $t)")
            "current_status" -> exec("INSERT INTO current_status (locationLat, locationLon, source, updatedAt) VALUES ($id, 1, 'NWS', $t)")
            "network_usage" -> exec("INSERT INTO network_usage (timestamp, bytes, networkType, isForeground) VALUES ($t, 1, 'wifi', 1)")
            else -> error(table)
        }
    }

    @Test
    fun `each table keeps rows inside its window and drops rows outside it`() {
        val windows = mapOf(
            "forecasts" to 30.0, "hourly_forecasts" to 30.0, "hourly_forecast_history" to 30.0,
            "station_cache" to 30.0, "current_status" to 30.0,
            "observations" to 10.0, "daily_history" to 547.0, "network_usage" to 90.0,
        )
        for ((table, days) in windows) {
            insert(table, days - 1, 1)
            insert(table, days + 1, 2)
        }

        dao.applyRetention(now)

        for ((table, days) in windows) assertEquals("$table keeps < $days d, drops > $days d", 1, count("SELECT count(*) FROM $table"))
    }

    @Test
    fun `app logs keep 72 h, except the permanent markers`() {
        fun log(tag: String, ageHours: Long) =
            exec("INSERT INTO app_logs (timestamp, level, tag, message) VALUES (${now - ageHours * 3_600_000L}, 'INFO', '$tag', 'm')")
        log("REFRESH", 71)
        log("REFRESH", 73)
        log("CHANCE_BACKFILL_DONE", 24 * 400)

        dao.applyRetention(now, protectedLogTags = listOf("CHANCE_BACKFILL_DONE"))

        assertEquals(1, count("SELECT count(*) FROM app_logs WHERE tag = 'REFRESH'"))
        assertEquals("a permanent marker survives any age", 1, count("SELECT count(*) FROM app_logs WHERE tag = 'CHANCE_BACKFILL_DONE'"))
    }
}

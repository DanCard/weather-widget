package com.weatherwidget.data.local.desktop

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories

class DesktopWeatherDatabase(private val dbPath: Path) {

    init {
        dbPath.parent.createDirectories()
    }

    fun getConnection(): Connection {
        val url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        val conn = DriverManager.getConnection(url)
        // WAL lets the genmon reader and the app writer access the file concurrently without
        // "database is locked"; busy_timeout gives a writer up to 5s to wait out a competing lock.
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA busy_timeout = 5000")
            stmt.execute("PRAGMA journal_mode = WAL")
        }
        return conn
    }

    fun initialize() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                // Forecasts
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS forecasts (
                        targetDate INTEGER NOT NULL,
                        forecastDate INTEGER NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        locationName TEXT NOT NULL DEFAULT '',
                        highTemp REAL,
                        lowTemp REAL,
                        condition TEXT NOT NULL,
                        nativeDailyIconToken TEXT,
                        isClimateNormal INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL,
                        precipProbability INTEGER,
                        daytimePrecipProbability INTEGER,
                        nighttimePrecipProbability INTEGER,
                        periodStartTime INTEGER,
                        periodEndTime INTEGER,
                        precipAmountMm REAL,
                        batchFetchedAt INTEGER NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY (targetDate, forecastDate, locationLat, locationLon, source, fetchedAt)
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_forecasts_location ON forecasts(locationLat, locationLon)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_forecasts_batch ON forecasts(targetDate, source, locationLat, locationLon, batchFetchedAt)")

                // Hourly Forecasts
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS hourly_forecasts (
                        dateTime INTEGER NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        temperature REAL NOT NULL,
                        condition TEXT NOT NULL,
                        source TEXT NOT NULL,
                        precipProbability INTEGER,
                        cloudCover INTEGER,
                        precipAmountMm REAL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY (dateTime, source, locationLat, locationLon)
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_hourly_location ON hourly_forecasts(locationLat, locationLon)")

                // Hourly Forecast History
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS hourly_forecast_history (
                        dateTime INTEGER NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        temperature REAL NOT NULL,
                        condition TEXT NOT NULL,
                        source TEXT NOT NULL,
                        snapshotBucket INTEGER NOT NULL,
                        precipProbability INTEGER,
                        cloudCover INTEGER,
                        precipAmountMm REAL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY (dateTime, source, locationLat, locationLon, snapshotBucket)
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_hourly_history_lookup ON hourly_forecast_history(locationLat, locationLon, source, snapshotBucket)")

                // Observations
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS observations (
                        stationId TEXT NOT NULL,
                        stationName TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        temperature REAL NOT NULL,
                        condition TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        distanceKm REAL NOT NULL DEFAULT 0,
                        stationType TEXT NOT NULL DEFAULT 'UNKNOWN',
                        fetchedAt INTEGER NOT NULL,
                        maxTempLast24h REAL,
                        minTempLast24h REAL,
                        api TEXT NOT NULL,
                        precipAmountMm REAL,
                        isWebFallback INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (stationId, timestamp)
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_observations_location ON observations(locationLat, locationLon)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_observations_time_loc ON observations(timestamp, locationLat, locationLon)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_observations_api ON observations(api)")

                // Daily Extremes
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS daily_extremes (
                        date INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        highTemp REAL NOT NULL,
                        lowTemp REAL NOT NULL,
                        condition TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        precipAmountMm REAL,
                        precipDayMm REAL,
                        precipNightMm REAL,
                        PRIMARY KEY (date, source, locationLat, locationLon)
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_extremes_lookup ON daily_extremes(date, locationLat, locationLon)")

                // App log — persistent, queryable record of pipeline health (mirrors the Android
                // app_logs table). timestamp is epoch ms so `datetime(timestamp/1000,'unixepoch',
                // 'localtime')` works the same as on Android.
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS app_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp INTEGER NOT NULL,
                        level TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        message TEXT NOT NULL
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_app_logs_time ON app_logs(timestamp)")

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS station_cache (
                        cacheKey TEXT PRIMARY KEY,
                        stations TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                // Climate normals — 12 monthly-average rows per location (mirrors the Android
                // climate_normals table). highTemp/lowTemp are REAL so one-decimal normals survive.
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS climate_normals (
                        monthDay TEXT NOT NULL,
                        locationKey TEXT NOT NULL,
                        highTemp REAL NOT NULL,
                        lowTemp REAL NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY (monthDay, locationKey)
                    )
                """.trimIndent())

                // Migration / Versioning
                val rs = stmt.executeQuery("PRAGMA user_version")
                val currentVersion = if (rs.next()) rs.getInt(1) else 0
                if (currentVersion == 0) {
                    stmt.execute("PRAGMA user_version = $SCHEMA_VERSION")
                } else {
                    migrate(conn, currentVersion, SCHEMA_VERSION)
                }
            }
        }
    }

    private fun migrate(conn: Connection, from: Int, to: Int) {
        if (from >= to) return
        conn.createStatement().use { stmt ->
            // v3: collapse coordinate-fragmented hourly rows (float lat/lon in the PK let GPS jitter
            // accumulate one row per precision for the same hour) — keep the freshest per quantized
            // key and round survivors onto the grid LocationMatch.quantize now writes to.
            if (from < 3) {
                dedupeQuantizeHourly(stmt, "hourly_forecasts", "")
                dedupeQuantizeHourly(stmt, "hourly_forecast_history", ", h.snapshotBucket")
            }
            if (from < 4) {
                addColumnIfMissing(stmt, "observations", "isWebFallback", "INTEGER NOT NULL DEFAULT 0")
            }
            stmt.execute("PRAGMA user_version = $to")
        }
    }

    private fun addColumnIfMissing(stmt: java.sql.Statement, table: String, column: String, type: String) {
        val rs = stmt.executeQuery("PRAGMA table_info($table)")
        val columns = mutableListOf<String>()
        while (rs.next()) {
            columns.add(rs.getString("name"))
        }
        rs.close()
        if (!columns.contains(column)) {
            stmt.execute("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }

    /** Keep the freshest row per (dateTime, source[, snapshotBucket], rounded lat/lon), then round
     *  the surviving coordinates so future REPLACE upserts overwrite instead of fragmenting. */
    private fun dedupeQuantizeHourly(stmt: java.sql.Statement, table: String, bucketCol: String) {
        val bucketJoin = if (bucketCol.isEmpty()) "" else " AND h2.snapshotBucket = h.snapshotBucket"
        stmt.execute(
            "DELETE FROM $table WHERE rowid NOT IN (" +
                "SELECT rowid FROM $table h WHERE fetchedAt = (" +
                "SELECT MAX(fetchedAt) FROM $table h2 WHERE " +
                "h2.dateTime = h.dateTime AND h2.source = h.source$bucketJoin AND " +
                "ROUND(h2.locationLat, 3) = ROUND(h.locationLat, 3) AND " +
                "ROUND(h2.locationLon, 3) = ROUND(h.locationLon, 3)) " +
                "GROUP BY h.dateTime, h.source$bucketCol, ROUND(h.locationLat, 3), ROUND(h.locationLon, 3))",
        )
        stmt.execute("UPDATE $table SET locationLat = ROUND(locationLat, 3), locationLon = ROUND(locationLon, 3)")
    }

    companion object {
        private const val SCHEMA_VERSION = 4
    }
}

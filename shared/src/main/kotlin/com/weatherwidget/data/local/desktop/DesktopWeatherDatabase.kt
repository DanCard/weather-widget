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

                // Migration / Versioning
                val rs = stmt.executeQuery("PRAGMA user_version")
                val currentVersion = if (rs.next()) rs.getInt(1) else 0
                if (currentVersion == 0) {
                    stmt.execute("PRAGMA user_version = 1")
                } else {
                    migrate(conn, currentVersion, 1)
                }
            }
        }
    }

    private fun migrate(conn: Connection, from: Int, to: Int) {
        // Implementation for future migrations
    }
}

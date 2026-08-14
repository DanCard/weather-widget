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
                        dateOfPrediction INTEGER NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
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
                        PRIMARY KEY (targetDate, dateOfPrediction, locationLat, locationLon, source, fetchedAt)
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
                        timestampToGroupPredictions INTEGER NOT NULL,
                        precipProbability INTEGER,
                        cloudCover INTEGER,
                        precipAmountMm REAL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY (dateTime, source, locationLat, locationLon, timestampToGroupPredictions)
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_hourly_history_lookup ON hourly_forecast_history(locationLat, locationLon, source, timestampToGroupPredictions)")

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
                        qcFailed INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (stationId, timestamp)
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_observations_location ON observations(locationLat, locationLon)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_observations_time_loc ON observations(timestamp, locationLat, locationLon)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_observations_api ON observations(api)")

                // Daily history: high/low extremes, observed day/night precip, (from v6) the
                // resolved forecast rain-chance % snapshotted while each day was current, and
                // (from v7) the frozen forecast overlay + noon cloud % (DailyHistoryFreeze) so the
                // daily bar view can render past days from this table alone.
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS daily_history (
                        date INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        computedHighTemp REAL NOT NULL,
                        computedLowTemp REAL NOT NULL,
                        condition TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        precipAmountMm REAL,
                        precipDayMm REAL,
                        precipNightMm REAL,
                        forecastDayPrecipChance INTEGER,
                        forecastNightPrecipChance INTEGER,
                        forecastHighTemp REAL,
                        forecastLowTemp REAL,
                        forecastPrecipAmountMm REAL,
                        noonCloudPercent INTEGER,
                        apiHighTemp REAL,
                        apiLowTemp REAL,
                        apiStationId TEXT,
                        apiStationDistanceKm REAL,
                        actualsSource TEXT,
                        lastWriter TEXT,
                        PRIMARY KEY (date, source, locationLat, locationLon)
                    )
                """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_daily_history_lookup ON daily_history(date, locationLat, locationLon)")

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

                // Current status — the daemon's published resolved current-temperature snapshot.
                // Single row per (location, source), overwritten on every fetch. The genmon panel
                // and the UI popup read this instead of re-deriving the value themselves, so the
                // two displays cannot drift. display/applied/delta columns are REAL so one-decimal
                // values survive; nullable because a fresh install resolves nothing until its first
                // fetch.
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS current_status (
                        locationLat REAL NOT NULL,
                        locationLon REAL NOT NULL,
                        source TEXT NOT NULL,
                        displayTempF REAL,
                        appliedDeltaF REAL,
                        deltaFromYesterdayF REAL,
                        observedAtMs INTEGER,
                        condition TEXT,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY (locationLat, locationLon, source)
                    )
                """.trimIndent())

                // Migration / Versioning
                val rs = stmt.executeQuery("PRAGMA user_version")
                val currentVersion = if (rs.next()) rs.getInt(1) else 0
                rs.close()
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
                dedupeQuantizeHourly(stmt, "hourly_forecast_history", ", h.timestampToGroupPredictions")
            }
            if (from < 4) {
                addColumnIfMissing(stmt, "observations", "isWebFallback", "INTEGER NOT NULL DEFAULT 0")
            }
            // v5: round forecasts lat/lon onto the LocationMatch.quantize grid (the daily-view
            // counterpart of the v3 hourly collapse). Unlike hourly, forecasts keeps per-batch
            // history for accuracy/evolution, so dedupe ONLY true rounded-PK collisions
            // (fetchedAt is ms-precision, so genuine collisions are vanishingly rare).
            if (from < 5) {
                stmt.execute(
                    "DELETE FROM forecasts WHERE rowid NOT IN (" +
                        "SELECT MAX(rowid) FROM forecasts GROUP BY targetDate, dateOfPrediction, source, fetchedAt, " +
                        "ROUND(locationLat, 3), ROUND(locationLon, 3))",
                )
                stmt.execute("UPDATE forecasts SET locationLat = ROUND(locationLat, 3), locationLon = ROUND(locationLon, 3)")
            }
            // v6: daily_extremes -> daily_history (the table now also holds displayed forecast
            // rain-chance snapshots, not just temperature extremes), plus the two chance columns.
            if (from < 6) {
                if (hasTable(stmt, "daily_extremes")) {
                    if (!hasTable(stmt, "daily_history")) {
                        stmt.execute("ALTER TABLE daily_extremes RENAME TO daily_history")
                    } else {
                        // daily_history already exists (fresh CREATE TABLE with latest schema
                        // ran first). Merge data, adapting to whatever columns are present.
                        val dailyHistoryCols = tableColumns(stmt, "daily_history")
                        val targetHigh = if (dailyHistoryCols.contains("computedHighTemp")) "computedHighTemp" else "highTemp"
                        val targetLow = if (dailyHistoryCols.contains("computedLowTemp")) "computedLowTemp" else "lowTemp"
                        stmt.execute("""
                            INSERT OR IGNORE INTO daily_history (date, source, locationLat, locationLon, $targetHigh, $targetLow, condition, updatedAt, precipAmountMm, precipDayMm, precipNightMm)
                            SELECT date, source, locationLat, locationLon, highTemp, lowTemp, condition, updatedAt, precipAmountMm, precipDayMm, precipNightMm FROM daily_extremes
                        """.trimIndent())
                        stmt.execute("DROP TABLE daily_extremes")
                    }
                }
                addColumnIfMissing(stmt, "daily_history", "forecastDayPrecipChance", "INTEGER")
                addColumnIfMissing(stmt, "daily_history", "forecastNightPrecipChance", "INTEGER")
            }
            // v7: frozen forecast-overlay + noon-cloud columns (DailyHistoryFreeze) — the daily bar
            // view's remaining external display inputs, archived so past days render from this row
            // alone once the forecasts / hourly tables age out.
            if (from < 7) {
                addColumnIfMissing(stmt, "daily_history", "forecastHighTemp", "REAL")
                addColumnIfMissing(stmt, "daily_history", "forecastLowTemp", "REAL")
                addColumnIfMissing(stmt, "daily_history", "forecastPrecipAmountMm", "REAL")
                addColumnIfMissing(stmt, "daily_history", "noonCloudPercent", "INTEGER")
            }
            if (from < 8) {
                val rs = stmt.executeQuery("PRAGMA table_info(hourly_forecast_history)")
                var hasSnapshotBucket = false
                while (rs.next()) {
                    if (rs.getString("name") == "snapshotBucket") {
                        hasSnapshotBucket = true
                        break
                    }
                }
                rs.close()
                if (hasSnapshotBucket) {
                    stmt.execute("DROP INDEX IF EXISTS idx_hourly_history_lookup")
                    stmt.execute("ALTER TABLE hourly_forecast_history RENAME COLUMN snapshotBucket TO timestampToGroupPredictions")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_hourly_history_lookup ON hourly_forecast_history(locationLat, locationLon, source, timestampToGroupPredictions)")
                }
            }
            if (from < 9) {
                val rs = stmt.executeQuery("PRAGMA table_info(forecasts)")
                var hasForecastDate = false
                while (rs.next()) {
                    if (rs.getString("name") == "forecastDate") {
                        hasForecastDate = true
                        break
                    }
                }
                rs.close()
                if (hasForecastDate) {
                    stmt.execute("ALTER TABLE forecasts RENAME COLUMN forecastDate TO dateOfPrediction")
                }
            }
            if (from < 10) {
                val rs = stmt.executeQuery("PRAGMA table_info(forecasts)")
                var hasLocationName = false
                while (rs.next()) {
                    if (rs.getString("name") == "locationName") {
                        hasLocationName = true
                        break
                    }
                }
                rs.close()
                if (hasLocationName) {
                    stmt.execute("ALTER TABLE forecasts DROP COLUMN locationName")
                }
            }
            // v11: mark readings rejected by upstream QC (Synoptic qc_flags) so the stations UI
            // can show them without letting them into blends.
            if (from < 11) {
                addColumnIfMissing(stmt, "observations", "qcFailed", "INTEGER NOT NULL DEFAULT 0")
            }
            // v12: rename highTemp/lowTemp → computedHighTemp/computedLowTemp to clarify these
            // are the IDW-blended extremes ("Location actual"), and add apiHighTemp/apiLowTemp
            // for provider-reported observed values ("API actual").
            if (from < 12) {
                val cols = tableColumns(stmt, "daily_history")
                val hasHighTemp = cols.contains("highTemp")
                val hasLowTemp = cols.contains("lowTemp")
                if (hasHighTemp || hasLowTemp) {
                    stmt.execute("""
                        CREATE TABLE daily_history_new (
                            date INTEGER NOT NULL,
                            source TEXT NOT NULL,
                            locationLat REAL NOT NULL,
                            locationLon REAL NOT NULL,
                            computedHighTemp REAL NOT NULL,
                            computedLowTemp REAL NOT NULL,
                            condition TEXT NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            precipAmountMm REAL,
                            precipDayMm REAL,
                            precipNightMm REAL,
                            forecastDayPrecipChance INTEGER,
                            forecastNightPrecipChance INTEGER,
                            forecastHighTemp REAL,
                            forecastLowTemp REAL,
                            forecastPrecipAmountMm REAL,
                            noonCloudPercent INTEGER,
                            apiHighTemp REAL,
                            apiLowTemp REAL,
                            PRIMARY KEY (date, source, locationLat, locationLon)
                        )
                    """.trimIndent())
                    val highSrc = if (hasHighTemp) "highTemp" else "computedHighTemp"
                    val lowSrc = if (hasLowTemp) "lowTemp" else "computedLowTemp"
                    stmt.execute("""
                        INSERT INTO daily_history_new (
                            date, source, locationLat, locationLon,
                            computedHighTemp, computedLowTemp, condition, updatedAt,
                            precipAmountMm, precipDayMm, precipNightMm,
                            forecastDayPrecipChance, forecastNightPrecipChance,
                            forecastHighTemp, forecastLowTemp,
                            forecastPrecipAmountMm, noonCloudPercent
                        )
                        SELECT
                            date, source, locationLat, locationLon,
                            COALESCE($highSrc, 0), COALESCE($lowSrc, 0), condition, updatedAt,
                            precipAmountMm, precipDayMm, precipNightMm,
                            forecastDayPrecipChance, forecastNightPrecipChance,
                            forecastHighTemp, forecastLowTemp,
                            forecastPrecipAmountMm, noonCloudPercent
                        FROM daily_history
                        WHERE $highSrc IS NOT NULL AND $lowSrc IS NOT NULL
                    """.trimIndent())
                    stmt.execute("DROP TABLE daily_history")
                    stmt.execute("ALTER TABLE daily_history_new RENAME TO daily_history")
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_daily_history_lookup ON daily_history(date, locationLat, locationLon)")
                } else {
                    // Fresh v11 install already has computedHighTemp/computedLowTemp — just add api
                    addColumnIfMissing(stmt, "daily_history", "apiHighTemp", "REAL")
                    addColumnIfMissing(stmt, "daily_history", "apiLowTemp", "REAL")
                }
            }
            // v13: add the station provenance columns, and repair NWS api actuals that were
            // really gridpoint FORECAST values. persistNwsApiActuals used to file the leftover
            // past-date windows of the NDFD forecast grid as observations, so a past day's
            // "actual" was that day's forecast. Mirrors Room MIGRATION_58_59 on Android; see
            // plans/260808-nws-actuals-forecast-contamination.md.
            if (from < 13) {
                addColumnIfMissing(stmt, "daily_history", "apiStationId", "TEXT")
                addColumnIfMissing(stmt, "daily_history", "apiStationDistanceKm", "REAL")
                // Both writers that ever populated NWS api actuals are gone (gridpoint FORECAST
                // values, and Open-Meteo ERA5 filling their gaps), so none of the stored values
                // are legitimate. Clear them all; the station resolver refills from observations.
                stmt.execute("UPDATE daily_history SET apiHighTemp = NULL, apiLowTemp = NULL WHERE source = 'NWS'")
            }
            // v14: record which pipeline produced a row's NWS actuals (DailyActualsSource), which
            // also replaces the implicit `apiStationId != null` freeze marker.
            if (from < 14) {
                addColumnIfMissing(stmt, "daily_history", "actualsSource", "TEXT")
            }
            // v15: record which code path last wrote the row (DailyHistoryWriter). Diagnostic.
            if (from < 15) {
                addColumnIfMissing(stmt, "daily_history", "lastWriter", "TEXT")
            }
            stmt.execute("PRAGMA user_version = $to")
        }
    }

    private fun hasTable(stmt: java.sql.Statement, table: String): Boolean {
        val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'")
        val exists = rs.next()
        rs.close()
        return exists
    }

    private fun addColumnIfMissing(stmt: java.sql.Statement, table: String, column: String, type: String) {
        val columns = tableColumns(stmt, table)
        if (!columns.contains(column)) {
            stmt.execute("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }

    private fun tableColumns(stmt: java.sql.Statement, table: String): List<String> {
        val columns = mutableListOf<String>()
        stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
        }
        return columns
    }

    /** Keep the freshest row per (dateTime, source[, snapshotBucket], rounded lat/lon), then round
     *  the surviving coordinates so future REPLACE upserts overwrite instead of fragmenting. */
    private fun dedupeQuantizeHourly(stmt: java.sql.Statement, table: String, bucketCol: String) {
        val bucketJoin = if (bucketCol.isEmpty()) "" else " AND h2.timestampToGroupPredictions = h.timestampToGroupPredictions"
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
        private const val SCHEMA_VERSION = 15
    }
}

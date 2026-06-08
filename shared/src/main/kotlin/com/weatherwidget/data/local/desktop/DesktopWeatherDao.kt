package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.DailyExtreme
import com.weatherwidget.data.model.DailyActual
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.util.Log
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.LocalDate
import java.time.ZoneOffset

class DesktopWeatherDao(private val db: DesktopWeatherDatabase) {

    fun upsertHourlyForecasts(locationLat: Double, locationLon: Double, source: String, hourly: List<HourlyForecast>) {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO hourly_forecasts 
                    (dateTime, locationLat, locationLon, temperature, condition, source, precipProbability, cloudCover, precipAmountMm, fetchedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    val now = System.currentTimeMillis()
                    for (h in hourly) {
                        stmt.setLong(1, h.dateTime)
                        stmt.setDouble(2, locationLat)
                        stmt.setDouble(3, locationLon)
                        stmt.setFloat(4, h.temperature)
                        stmt.setString(5, h.condition)
                        stmt.setString(6, source)
                        stmt.setNullableInt(7, h.precipProbability)
                        stmt.setNullableInt(8, h.cloudCover)
                        stmt.setNullableFloat(9, h.precipAmountMm)
                        stmt.setLong(10, now)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun upsertHourlyForecastHistory(locationLat: Double, locationLon: Double, source: String, snapshotBucket: Long, hourly: List<HourlyForecast>) {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO hourly_forecast_history 
                    (dateTime, locationLat, locationLon, temperature, condition, source, snapshotBucket, precipProbability, cloudCover, precipAmountMm, fetchedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    val now = System.currentTimeMillis()
                    for (h in hourly) {
                        stmt.setLong(1, h.dateTime)
                        stmt.setDouble(2, locationLat)
                        stmt.setDouble(3, locationLon)
                        stmt.setFloat(4, h.temperature)
                        stmt.setString(5, h.condition)
                        stmt.setString(6, source)
                        stmt.setLong(7, snapshotBucket)
                        stmt.setNullableInt(8, h.precipProbability)
                        stmt.setNullableInt(9, h.cloudCover)
                        stmt.setNullableFloat(10, h.precipAmountMm)
                        stmt.setLong(11, now)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun upsertForecasts(locationLat: Double, locationLon: Double, source: String, daily: List<DailyForecast>) {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO forecasts 
                    (targetDate, forecastDate, locationLat, locationLon, locationName, highTemp, lowTemp, condition, 
                     nativeDailyIconToken, isClimateNormal, source, precipProbability, precipAmountMm, batchFetchedAt, fetchedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    val now = System.currentTimeMillis()
                    val todayEpoch = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    for (d in daily) {
                        val targetDate = LocalDate.parse(d.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                        stmt.setLong(1, targetDate)
                        stmt.setLong(2, todayEpoch) // simplified for desktop Tier 1
                        stmt.setDouble(3, locationLat)
                        stmt.setDouble(4, locationLon)
                        stmt.setString(5, "") // locationName
                        stmt.setFloat(6, d.highTemp)
                        stmt.setFloat(7, d.lowTemp)
                        stmt.setString(8, d.condition)
                        stmt.setString(9, d.iconToken)
                        stmt.setInt(10, 0) // isClimateNormal
                        stmt.setString(11, source)
                        stmt.setNullableInt(12, d.precipProbability)
                        stmt.setNullableFloat(13, d.precipAmountMm)
                        stmt.setLong(14, now)
                        stmt.setLong(15, now)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun upsertObservations(observations: List<DesktopObservationEntity>) {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO observations 
                    (stationId, stationName, timestamp, temperature, condition, locationLat, locationLon, distanceKm, stationType, fetchedAt, maxTempLast24h, minTempLast24h, api, precipAmountMm)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    for (obs in observations) {
                        stmt.setString(1, obs.stationId)
                        stmt.setString(2, obs.stationName)
                        stmt.setLong(3, obs.timestamp)
                        stmt.setFloat(4, obs.temperature)
                        stmt.setString(5, obs.condition)
                        stmt.setDouble(6, obs.locationLat)
                        stmt.setDouble(7, obs.locationLon)
                        stmt.setFloat(8, obs.distanceKm)
                        stmt.setString(9, obs.stationType)
                        stmt.setLong(10, obs.fetchedAt)
                        stmt.setNullableFloat(11, obs.maxTempLast24h)
                        stmt.setNullableFloat(12, obs.minTempLast24h)
                        stmt.setString(13, obs.api)
                        stmt.setNullableFloat(14, obs.precipAmountMm)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun getLatestObservation(locationLat: Double, locationLon: Double, maxAgeMs: Long): DesktopObservationEntity? {
        val minTimestamp = System.currentTimeMillis() - maxAgeMs
        db.getConnection().use { conn ->
            val sql = """
                SELECT * FROM observations 
                WHERE locationLat = ? AND locationLon = ? AND timestamp >= ?
                ORDER BY timestamp DESC LIMIT 1
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setLong(3, minTimestamp)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return DesktopObservationEntity(
                        stationId = rs.getString("stationId"),
                        stationName = rs.getString("stationName"),
                        timestamp = rs.getLong("timestamp"),
                        temperature = rs.getFloat("temperature"),
                        condition = rs.getString("condition"),
                        locationLat = rs.getDouble("locationLat"),
                        locationLon = rs.getDouble("locationLon"),
                        distanceKm = rs.getFloat("distanceKm"),
                        stationType = rs.getString("stationType"),
                        fetchedAt = rs.getLong("fetchedAt"),
                        maxTempLast24h = rs.getNullableFloat("maxTempLast24h"),
                        minTempLast24h = rs.getNullableFloat("minTempLast24h"),
                        api = rs.getString("api"),
                        precipAmountMm = rs.getNullableFloat("precipAmountMm")
                    )
                }
            }
        }
        return null
    }

    fun upsertDailyExtremes(extremes: List<DailyExtreme>) {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO daily_extremes 
                    (date, source, locationLat, locationLon, highTemp, lowTemp, condition, updatedAt, precipAmountMm, precipDayMm, precipNightMm)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    for (ex in extremes) {
                        stmt.setLong(1, ex.date)
                        stmt.setString(2, ex.source)
                        stmt.setDouble(3, ex.locationLat)
                        stmt.setDouble(4, ex.locationLon)
                        stmt.setFloat(5, ex.highTemp)
                        stmt.setFloat(6, ex.lowTemp)
                        stmt.setString(7, ex.condition)
                        stmt.setLong(8, ex.updatedAt)
                        stmt.setNullableFloat(9, ex.precipAmountMm)
                        stmt.setNullableFloat(10, ex.precipDayMm)
                        stmt.setNullableFloat(11, ex.precipNightMm)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun cleanup(beforeEpochMs: Long) {
        db.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM forecasts WHERE fetchedAt < $beforeEpochMs")
                stmt.execute("DELETE FROM hourly_forecasts WHERE fetchedAt < $beforeEpochMs")
                stmt.execute("DELETE FROM hourly_forecast_history WHERE fetchedAt < $beforeEpochMs")
                stmt.execute("DELETE FROM observations WHERE fetchedAt < $beforeEpochMs")
                stmt.execute("DELETE FROM daily_extremes WHERE updatedAt < $beforeEpochMs")
                stmt.execute("DELETE FROM app_logs WHERE timestamp < $beforeEpochMs")
                stmt.execute("DELETE FROM station_cache WHERE updatedAt < $beforeEpochMs")
            }
        }
    }

    fun getCachedStations(cacheKey: String, maxAgeMs: Long): List<NwsApi.StationInfo>? {
        val minUpdatedAt = System.currentTimeMillis() - maxAgeMs
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT stations FROM station_cache WHERE cacheKey = ? AND updatedAt >= ?").use { stmt ->
                stmt.setString(1, cacheKey)
                stmt.setLong(2, minUpdatedAt)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return rs.getString("stations")
                        .split("|")
                        .mapNotNull(NwsApi.Companion::decodeStationInfo)
                }
            }
        }
        return null
    }

    fun upsertStationCache(cacheKey: String, stations: List<NwsApi.StationInfo>) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO station_cache (cacheKey, stations, updatedAt)
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, cacheKey)
                stmt.setString(2, stations.joinToString("|", transform = NwsApi.Companion::encodeStationInfo))
                stmt.setLong(3, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Appends an app-log row. Best-effort: a logging failure must never break a fetch, so any error
     * is reported to the console logger and swallowed (this is the persistence backstop, not the
     * primary log path).
     */
    fun log(tag: String, message: String, level: String = "INFO") {
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO app_logs (timestamp, level, tag, message) VALUES (?, ?, ?, ?)"
                ).use { stmt ->
                    stmt.setLong(1, System.currentTimeMillis())
                    stmt.setString(2, level)
                    stmt.setString(3, tag)
                    stmt.setString(4, message)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            Log.w("DesktopWeatherDao", "app_logs write failed: $e")
        }
    }

    fun getLastSuccessfulFetch(source: String? = null): Long? {
        db.getConnection().use { conn ->
            val sql = if (source != null) {
                "SELECT MAX(timestamp) FROM app_logs WHERE tag = 'REFRESH' AND level = 'INFO' AND message LIKE ?"
            } else {
                "SELECT MAX(timestamp) FROM app_logs WHERE tag = 'REFRESH' AND level = 'INFO'"
            }
            conn.prepareStatement(sql).use { stmt ->
                if (source != null) {
                    stmt.setString(1, "source=$source%")
                }
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val ts = rs.getLong(1)
                    return if (ts > 0L) ts else null
                }
            }
        }
        return null
    }

    fun getLastSuccessfulObservationFetch(source: String? = null): Long? {
        db.getConnection().use { conn ->
            val sql = if (source != null) {
                """
                    SELECT MAX(timestamp) FROM app_logs
                    WHERE tag IN ('REFRESH', 'OBS_REFRESH') AND level = 'INFO' AND message LIKE ?
                """.trimIndent()
            } else {
                "SELECT MAX(timestamp) FROM app_logs WHERE tag IN ('REFRESH', 'OBS_REFRESH') AND level = 'INFO'"
            }
            conn.prepareStatement(sql).use { stmt ->
                if (source != null) {
                    stmt.setString(1, "source=$source%")
                }
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val ts = rs.getLong(1)
                    return if (ts > 0L) ts else null
                }
            }
        }
        return null
    }

    fun getRecentLogs(limit: Int = 200): List<DesktopLogEntity> {
        val result = mutableListOf<DesktopLogEntity>()
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT timestamp, level, tag, message FROM app_logs ORDER BY timestamp DESC LIMIT ?").use { stmt ->
                stmt.setInt(1, limit)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    result.add(DesktopLogEntity(
                        timestamp = rs.getLong("timestamp"),
                        level = rs.getString("level"),
                        tag = rs.getString("tag"),
                        message = rs.getString("message"),
                    ))
                }
            }
        }
        return result
    }

    fun getLatestHourly(locationLat: Double, locationLon: Double, source: String, maxAgeMs: Long): List<HourlyForecast> {
        val now = System.currentTimeMillis()
        val minFetchedAt = now - maxAgeMs
        val result = mutableListOf<HourlyForecast>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT * FROM hourly_forecasts 
                WHERE locationLat = ? AND locationLon = ? AND source = ? AND fetchedAt >= ?
                ORDER BY dateTime ASC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                stmt.setLong(4, minFetchedAt)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    result.add(HourlyForecast(
                        dateTime = rs.getLong("dateTime"),
                        temperature = rs.getFloat("temperature"),
                        condition = rs.getString("condition"),
                        precipProbability = rs.getNullableInt("precipProbability"),
                        cloudCover = rs.getNullableInt("cloudCover"),
                        precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                        source = source,
                        fetchedAt = rs.getLong("fetchedAt"),
                    ))
                }
            }
        }
        return result
    }

    fun getHourlyHistory(locationLat: Double, locationLon: Double, source: String, startMs: Long, endMs: Long): List<HourlyForecast> {
        val result = mutableListOf<HourlyForecast>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT dateTime, temperature, condition, precipProbability, cloudCover, precipAmountMm, fetchedAt
                FROM hourly_forecast_history
                WHERE locationLat = ? AND locationLon = ? AND source = ? AND dateTime >= ? AND dateTime <= ?
                ORDER BY dateTime ASC, snapshotBucket DESC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                stmt.setLong(4, startMs)
                stmt.setLong(5, endMs)
                val rs = stmt.executeQuery()
                val seen = hashSetOf<Long>()
                while (rs.next()) {
                    val dateTime = rs.getLong("dateTime")
                    if (!seen.add(dateTime)) continue
                    result.add(
                        HourlyForecast(
                            dateTime = dateTime,
                            temperature = rs.getFloat("temperature"),
                            condition = rs.getString("condition"),
                            precipProbability = rs.getNullableInt("precipProbability"),
                            cloudCover = rs.getNullableInt("cloudCover"),
                            precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                            source = source,
                            fetchedAt = rs.getLong("fetchedAt"),
                        ),
                    )
                }
            }
        }
        return result
    }

    fun getHourlyWithHistory(locationLat: Double, locationLon: Double, source: String, startMs: Long, endMs: Long, maxAgeMs: Long): List<HourlyForecast> {
        val current = getLatestHourly(locationLat, locationLon, source, maxAgeMs).filter { it.dateTime in startMs..endMs }
        val history = getHourlyHistory(locationLat, locationLon, source, startMs, endMs)
        val stitched = HourlyForecastStitcher.stitch(current, history)
        val repairedCloudCoverCount = current.count { row ->
            row.cloudCover == null && stitched.firstOrNull { it.dateTime == row.dateTime }?.cloudCover != null
        }
        if (repairedCloudCoverCount > 0) {
            Log.i(
                "DesktopWeatherDao",
                "getHourlyWithHistory: repairedCloudCover=$repairedCloudCoverCount current=${current.size} history=${history.size} stitched=${stitched.size}",
            )
        }
        return stitched
    }

    fun getDailyForecasts(locationLat: Double, locationLon: Double, source: String): List<DailyForecast> {
        val result = mutableListOf<DailyForecast>()
        db.getConnection().use { conn ->
            // Get the latest batch
            val latestBatchSql = "SELECT MAX(batchFetchedAt) FROM forecasts WHERE locationLat = ? AND locationLon = ? AND source = ?"
            val latestBatch = conn.prepareStatement(latestBatchSql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getLong(1) else 0L
            }

            if (latestBatch == 0L) return emptyList()

            val sql = """
                SELECT * FROM forecasts 
                WHERE locationLat = ? AND locationLon = ? AND source = ? AND batchFetchedAt = ?
                ORDER BY targetDate ASC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                stmt.setLong(4, latestBatch)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val targetDate = rs.getLong("targetDate")
                    val dateStr = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(targetDate), ZoneOffset.UTC).toString()
                    result.add(DailyForecast(
                        date = dateStr,
                        highTemp = rs.getFloat("highTemp"),
                        lowTemp = rs.getFloat("lowTemp"),
                        condition = rs.getString("condition"),
                        iconToken = rs.getString("nativeDailyIconToken"),
                        precipProbability = rs.getNullableInt("precipProbability"),
                        precipAmountMm = rs.getNullableFloat("precipAmountMm")
                    ))
                }
            }
        }
        return result
    }

    fun getDailyForecastSnapshots(
        startEpoch: Long,
        endEpoch: Long,
        locationLat: Double,
        locationLon: Double,
        source: String,
    ): Map<String, List<DailyForecastSnapshot>> {
        val result = linkedMapOf<String, MutableList<DailyForecastSnapshot>>()
        db.getConnection().use { conn ->
            val latestBatchSql = """
                SELECT MAX(batchFetchedAt) FROM forecasts
                WHERE locationLat = ? AND locationLon = ? AND source = ?
            """.trimIndent()
            val latestBatch = conn.prepareStatement(latestBatchSql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getLong(1) else 0L
            }

            val sql = """
                SELECT targetDate, highTemp, lowTemp, condition, nativeDailyIconToken,
                    precipProbability, precipAmountMm, fetchedAt, batchFetchedAt
                FROM forecasts
                WHERE locationLat = ? AND locationLon = ? AND source = ?
                    AND targetDate >= ? AND targetDate <= ?
                ORDER BY targetDate ASC, fetchedAt DESC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                stmt.setLong(4, startEpoch)
                stmt.setLong(5, endEpoch)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    if (latestBatch != 0L && rs.getLong("batchFetchedAt") == latestBatch) continue
                    val targetDate = rs.getLong("targetDate")
                    val dateStr = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(targetDate), ZoneOffset.UTC).toString()
                    result.getOrPut(dateStr) { mutableListOf() }.add(
                        DailyForecastSnapshot(
                            date = dateStr,
                            highTemp = rs.getNullableFloat("highTemp"),
                            lowTemp = rs.getNullableFloat("lowTemp"),
                            condition = rs.getString("condition"),
                            iconToken = rs.getString("nativeDailyIconToken"),
                            precipProbability = rs.getNullableInt("precipProbability"),
                            precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                            fetchedAt = rs.getLong("fetchedAt"),
                        )
                    )
                }
            }
        }
        return result
    }

    fun getRecentObservations(sinceMs: Long): List<DesktopObservationEntity> {
        val result = mutableListOf<DesktopObservationEntity>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT * FROM observations 
                WHERE timestamp >= ?
                ORDER BY timestamp DESC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, sinceMs)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    result.add(
                        DesktopObservationEntity(
                            stationId = rs.getString("stationId"),
                            stationName = rs.getString("stationName"),
                            timestamp = rs.getLong("timestamp"),
                            temperature = rs.getFloat("temperature"),
                            condition = rs.getString("condition"),
                            locationLat = rs.getDouble("locationLat"),
                            locationLon = rs.getDouble("locationLon"),
                            distanceKm = rs.getFloat("distanceKm"),
                            stationType = rs.getString("stationType"),
                            fetchedAt = rs.getLong("fetchedAt"),
                            maxTempLast24h = if (rs.getObject("maxTempLast24h") != null) rs.getFloat("maxTempLast24h") else null,
                            minTempLast24h = if (rs.getObject("minTempLast24h") != null) rs.getFloat("minTempLast24h") else null,
                            api = rs.getString("api"),
                            precipAmountMm = if (rs.getObject("precipAmountMm") != null) rs.getFloat("precipAmountMm") else null,
                        )
                    )
                }
            }
        }
        return result
    }

    fun getObservationsInRange(startTs: Long, endTs: Long, locationLat: Double, locationLon: Double): List<DesktopObservationEntity> {
        val result = mutableListOf<DesktopObservationEntity>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT * FROM observations
                WHERE locationLat = ? AND locationLon = ? AND timestamp >= ? AND timestamp < ?
                ORDER BY timestamp ASC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setLong(3, startTs)
                stmt.setLong(4, endTs)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    result.add(DesktopObservationEntity(
                        stationId = rs.getString("stationId"),
                        stationName = rs.getString("stationName"),
                        timestamp = rs.getLong("timestamp"),
                        temperature = rs.getFloat("temperature"),
                        condition = rs.getString("condition"),
                        locationLat = rs.getDouble("locationLat"),
                        locationLon = rs.getDouble("locationLon"),
                        distanceKm = rs.getFloat("distanceKm"),
                        stationType = rs.getString("stationType"),
                        fetchedAt = rs.getLong("fetchedAt"),
                        maxTempLast24h = rs.getNullableFloat("maxTempLast24h"),
                        minTempLast24h = rs.getNullableFloat("minTempLast24h"),
                        api = rs.getString("api"),
                        precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                    ))
                }
            }
        }
        return result
    }

    fun getExtremesInRange(startEpoch: Long, endEpoch: Long, locationLat: Double, locationLon: Double): List<DailyExtreme> {
        val result = mutableListOf<DailyExtreme>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT * FROM daily_extremes
                WHERE locationLat = ? AND locationLon = ? AND date >= ? AND date <= ?
                ORDER BY date ASC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setLong(3, startEpoch)
                stmt.setLong(4, endEpoch)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    result.add(DailyExtreme(
                        date = rs.getLong("date"),
                        source = rs.getString("source"),
                        locationLat = rs.getDouble("locationLat"),
                        locationLon = rs.getDouble("locationLon"),
                        highTemp = rs.getFloat("highTemp"),
                        lowTemp = rs.getFloat("lowTemp"),
                        condition = rs.getString("condition"),
                        updatedAt = rs.getLong("updatedAt"),
                        precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                        precipDayMm = rs.getNullableFloat("precipDayMm"),
                        precipNightMm = rs.getNullableFloat("precipNightMm"),
                    ))
                }
            }
        }
        return result
    }

    fun getDailyActuals(startEpoch: Long, endEpoch: Long, locationLat: Double, locationLon: Double, source: String): Map<String, DailyExtreme> {
        return getExtremesInRange(startEpoch, endEpoch, locationLat, locationLon)
            .filter { it.source == source }
            .associateBy { LocalDate.ofEpochDay(it.date / 86_400_000L).toString() }
    }

    fun getForecastsInRangeBySource(startEpoch: Long, endEpoch: Long, locationLat: Double, locationLon: Double, source: String): List<DesktopForecastRow> {
        val result = mutableListOf<DesktopForecastRow>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT targetDate, forecastDate, source, highTemp, lowTemp, fetchedAt FROM forecasts
                WHERE locationLat = ? AND locationLon = ? AND source = ? AND targetDate >= ? AND targetDate <= ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                stmt.setLong(4, startEpoch)
                stmt.setLong(5, endEpoch)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    result.add(DesktopForecastRow(
                        targetDate = rs.getLong("targetDate"),
                        forecastDate = rs.getLong("forecastDate"),
                        source = rs.getString("source"),
                        highTemp = rs.getNullableFloat("highTemp"),
                        lowTemp = rs.getNullableFloat("lowTemp"),
                        fetchedAt = rs.getLong("fetchedAt"),
                    ))
                }
            }
        }
        return result
    }

    // Helper extensions for nullable types with JDBC
    private fun PreparedStatement.setNullableInt(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    private fun PreparedStatement.setNullableFloat(index: Int, value: Float?) {
        if (value == null) setNull(index, Types.REAL) else setFloat(index, value)
    }

    private fun java.sql.ResultSet.getNullableInt(column: String): Int? {
        val v = getInt(column)
        return if (wasNull()) null else v
    }

    private fun java.sql.ResultSet.getNullableFloat(column: String): Float? {
        val v = getFloat(column)
        return if (wasNull()) null else v
    }
}

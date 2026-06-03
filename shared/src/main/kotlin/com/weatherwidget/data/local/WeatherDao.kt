package com.weatherwidget.data.local

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.LocalDate
import java.time.ZoneOffset

class WeatherDao(private val db: WeatherDatabase) {

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

    fun upsertObservations(observations: List<com.weatherwidget.data.local.ObservationEntity>) {
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

    fun getLatestObservation(locationLat: Double, locationLon: Double, maxAgeMs: Long): com.weatherwidget.data.local.ObservationEntity? {
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
                    return com.weatherwidget.data.local.ObservationEntity(
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

    fun upsertDailyExtremes(extremes: List<com.weatherwidget.data.local.DailyExtremeEntity>) {
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
            }
        }
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
                        precipAmountMm = rs.getNullableFloat("precipAmountMm")
                    ))
                }
            }
        }
        return result
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

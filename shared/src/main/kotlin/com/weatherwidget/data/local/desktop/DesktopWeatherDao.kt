package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.DailyActual
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.HourlyForecastStitcher
import com.weatherwidget.shared.graph.CloudBands
import com.weatherwidget.shared.util.VisibleCloudCover
import com.weatherwidget.shared.graph.PriorDayBandForecast
import com.weatherwidget.shared.graph.PriorDayCloudForecast
import com.weatherwidget.data.model.CurrentStatus
import com.weatherwidget.data.model.CloudVerticalKind
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.orNullIfImplausibleTempF
import com.weatherwidget.shared.actuals.MetarCloudBlender
import com.weatherwidget.shared.util.ForecastTempRounding
import com.weatherwidget.shared.util.Log
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.LocalDate
import java.time.ZoneOffset

class DesktopWeatherDao(private val db: DesktopWeatherDatabase) {

    companion object {
        /** Marker in the NOT NULL `condition` column of prior-run cloud rows; never displayed. */
        private const val PRIOR_CLOUD_CONDITION = "prior-run cloud"
    }

    data class TomorrowCleanupResult(
        val observationsDeleted: Int,
        val dailyRowsDeleted: Int,
    )

    data class OpenMeteoCleanupResult(
        val observationsDeleted: Int,
        val dailyRowsDeleted: Int,
    )

    /** One-time removal of generic Tomorrow.io actuals written by older builds. */
    fun cleanupLegacyTomorrowIoActuals(): TomorrowCleanupResult? {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val alreadyDone = conn.prepareStatement(
                    "SELECT 1 FROM app_logs WHERE tag = 'TMRW_ACTUALS_CLEANUP_V2' LIMIT 1",
                ).use { it.executeQuery().next() }
                if (alreadyDone) {
                    conn.rollback()
                    return null
                }

                val observationsDeleted = conn.createStatement().use { stmt ->
                    stmt.executeUpdate(
                        "DELETE FROM observations WHERE api = 'TOMORROW_IO' " +
                            "AND stationId NOT IN ('TOMORROW_IO_RECENT_HISTORY', 'TOMORROW_IO_REALTIME')",
                    )
                }
                // Contaminated debug-only rows are removed as a unit; computed-null rows are
                // FORECAST_ONLY_ROWs from the newer writer (display surface, not legacy actuals)
                // and are kept. Current/future forecast state lives in the forecast tables.
                val dailyRowsDeleted = conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM daily_history WHERE source = 'TOMORROW_IO' AND computedHighTemp IS NOT NULL")
                }
                conn.prepareStatement(
                    "INSERT INTO app_logs (timestamp, level, tag, message) VALUES (?, 'INFO', 'TMRW_ACTUALS_CLEANUP_V2', ?)",
                ).use { stmt ->
                    stmt.setLong(1, System.currentTimeMillis())
                    stmt.setString(2, "legacyObservations=$observationsDeleted dailyRows=$dailyRowsDeleted")
                    stmt.executeUpdate()
                }
                conn.commit()
                return TomorrowCleanupResult(observationsDeleted, dailyRowsDeleted)
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    /** One-time removal of Open-Meteo Forecast API model rows previously stored as actuals. */
    fun cleanupLegacyOpenMeteoActuals(): OpenMeteoCleanupResult? {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val alreadyDone = conn.prepareStatement(
                    "SELECT 1 FROM app_logs WHERE tag = 'METEO_ACTUALS_CLEANUP_V1' LIMIT 1",
                ).use { it.executeQuery().next() }
                if (alreadyDone) {
                    conn.rollback()
                    return null
                }

                val observationsDeleted = conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM observations WHERE api = 'OPEN_METEO'")
                }
                // computed-null rows are FORECAST_ONLY_ROWs written by the newer writer — they are
                // the display surface for Meteo history, not legacy model actuals; keep them.
                val dailyRowsDeleted = conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM daily_history WHERE source = 'OPEN_METEO' AND computedHighTemp IS NOT NULL")
                }
                conn.prepareStatement(
                    "INSERT INTO app_logs (timestamp, level, tag, message) VALUES (?, 'INFO', 'METEO_ACTUALS_CLEANUP_V1', ?)",
                ).use { stmt ->
                    stmt.setLong(1, System.currentTimeMillis())
                    stmt.setString(2, "modelObservations=$observationsDeleted dailyRows=$dailyRowsDeleted")
                    stmt.executeUpdate()
                }
                conn.commit()
                return OpenMeteoCleanupResult(observationsDeleted, dailyRowsDeleted)
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }


    fun upsertHourlyForecasts(locationLat: Double, locationLon: Double, source: String, hourly: List<HourlyForecast>) {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO hourly_forecasts 
                    (dateTime, locationLat, locationLon, temperature, condition, source, precipProbability, cloudCover, cloudCoverLow, cloudCoverMid, cloudCoverHigh, precipAmountMm, fetchedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    val now = System.currentTimeMillis()
                    // Quantize the PK coordinate so geocoding/GPS jitter overwrites instead of
                    // accumulating per-precision fragments (see LocationMatch.quantize).
                    val keyLat = LocationMatch.quantize(locationLat)
                    val keyLon = LocationMatch.quantize(locationLon)
                    for (h in hourly) {
                        stmt.setLong(1, h.dateTime)
                        stmt.setDouble(2, keyLat)
                        stmt.setDouble(3, keyLon)
                        stmt.setFloat(4, h.temperature)
                        stmt.setString(5, h.condition)
                        stmt.setString(6, source)
                        stmt.setNullableInt(7, h.precipProbability)
                        stmt.setNullableInt(8, h.cloudCover)
                        stmt.setNullableInt(9, h.cloudCoverLow)
                        stmt.setNullableInt(10, h.cloudCoverMid)
                        stmt.setNullableInt(11, h.cloudCoverHigh)
                        stmt.setNullableFloat(12, h.precipAmountMm)
                        stmt.setLong(13, now)
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

    /**
     * Stores the day-ago cloud predictions backing the cloud graph's frozen forecast curve.
     *
     * Separate from [upsertHourlyForecastHistory] because each row needs its **own**
     * `timestampToGroupPredictions` — the nominal time that hour's prediction was made — rather than
     * one bucket for the whole batch. The bucket is derived, so refetching an hour REPLACEs in place.
     *
     * `temperature`/`condition` are NOT NULL in the schema but meaningless for this row; they are
     * filled with a neutral marker and must never be read. Only `cloudCover` carries meaning here,
     * and only [com.weatherwidget.shared.graph.PriorDayCloudForecast.SOURCE_ID] rows are ever read back.
     */
    fun upsertPriorDayCloudForecast(locationLat: Double, locationLon: Double, coverByHour: Map<Long, Int>) =
        upsertSyntheticCloudSeries(
            locationLat, locationLon, coverByHour,
            source = PriorDayCloudForecast.SOURCE_ID,
            condition = PRIOR_CLOUD_CONDITION,
            bucketFor = PriorDayCloudForecast::predictionBucketFor,
        )

    private fun upsertSyntheticCloudSeries(
        locationLat: Double,
        locationLon: Double,
        coverByHour: Map<Long, Int>,
        source: String,
        condition: String,
        bucketFor: (Long) -> Long,
    ) {
        if (coverByHour.isEmpty()) return
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO hourly_forecast_history
                    (dateTime, locationLat, locationLon, temperature, condition, source, timestampToGroupPredictions, precipProbability, cloudCover, cloudCoverLow, cloudCoverMid, cloudCoverHigh, precipAmountMm, fetchedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    val now = System.currentTimeMillis()
                    val keyLat = LocationMatch.quantize(locationLat)
                    val keyLon = LocationMatch.quantize(locationLon)
                    for ((hourMs, cover) in coverByHour) {
                        stmt.setLong(1, hourMs)
                        stmt.setDouble(2, keyLat)
                        stmt.setDouble(3, keyLon)
                        stmt.setFloat(4, 0f)
                        stmt.setString(5, condition)
                        stmt.setString(6, source)
                        stmt.setLong(7, bucketFor(hourMs))
                        stmt.setNull(8, java.sql.Types.INTEGER)
                        // The previous-runs variable is cloud_cover_previous_day1 — the total
                        // column — so it is filed where `cloudCover` means what it means everywhere
                        // else in the schema. Rows written while it was the LOW variable still carry
                        // their value on cloudCoverLow and are still read through
                        // VisibleCloudCover's band fallback; nothing needs migrating.
                        stmt.setInt(9, cover)
                        stmt.setNull(10, java.sql.Types.INTEGER)
                        stmt.setNull(11, java.sql.Types.INTEGER)
                        stmt.setNull(12, java.sql.Types.INTEGER)
                        stmt.setNull(13, java.sql.Types.REAL)
                        stmt.setLong(14, now)
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

    /**
     * Cloud actuals for the window, as timestamp -> visible-layer percent
     * ([com.weatherwidget.shared.util.VisibleCloudCover], the same resolver the forecast curve uses).
     *
     * Delegates the source-aware branch selection to the shared
     * [MetarCloudBlender.fromSiteRows]; this DAO contributes only the site-collapsed read. Mirrors
     * Android's `ObservationDao.getCloudActuals` by construction — same shared code.
     */
    suspend fun getCloudActuals(locationLat: Double, locationLon: Double, startMs: Long, endMs: Long, sourceId: String): MetarCloudBlender.Result =
        MetarCloudBlender.fromSiteRows(
            startMs = startMs,
            endMs = endMs,
            sourceId = sourceId,
        ) { readStart, readEnd ->
            getObservationsInRange(readStart, readEnd, locationLat, locationLon).map { it.toReading() }
        }

    /** Day-ago cloud predictions for the window, as hour -> percent. */
    fun getPriorDayCloudForecast(locationLat: Double, locationLon: Double, startMs: Long, endMs: Long): Map<Long, Int> =
        getSyntheticCloudSeries(locationLat, locationLon, PriorDayCloudForecast.SOURCE_ID, startMs, endMs)

    /**
     * Day-ago mid/high band predictions for the window, as hour -> bands.
     *
     * Its own query rather than [getHourlyHistory], which collapses to one freshest row per hour —
     * the opposite of what is needed here, where the point is to recover an OLD snapshot. Scoped to
     * the real source id because Open-Meteo's Previous Runs API has no band data at all; see
     * [PriorDayBandForecast].
     */
    fun getPriorDayBandForecast(locationLat: Double, locationLon: Double, source: String, startMs: Long, endMs: Long): Map<Long, CloudBands> {
        val snapshots = mutableListOf<PriorDayBandForecast.BandSnapshot>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT dateTime, timestampToGroupPredictions, cloudCoverMid, cloudCoverHigh
                FROM hourly_forecast_history
                WHERE ${LocationMatch.JDBC_WHERE} AND source = ?
                  AND dateTime >= ? AND dateTime <= ?
                  AND timestampToGroupPredictions >= ? AND timestampToGroupPredictions <= ?
                  AND (cloudCoverMid IS NOT NULL OR cloudCoverHigh IS NOT NULL)
                ORDER BY dateTime ASC, timestampToGroupPredictions ASC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                stmt.setLong(4, startMs)
                stmt.setLong(5, endMs)
                stmt.setLong(6, startMs - PriorDayBandForecast.MAX_LEAD_MS)
                stmt.setLong(7, endMs - PriorDayBandForecast.LEAD_MS)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    snapshots += PriorDayBandForecast.BandSnapshot(
                        hourMs = rs.getLong("dateTime"),
                        bucketMs = rs.getLong("timestampToGroupPredictions"),
                        bands = CloudBands(
                            mid = rs.getNullableInt("cloudCoverMid"),
                            high = rs.getNullableInt("cloudCoverHigh"),
                        ),
                    )
                }
            }
        }
        return PriorDayBandForecast.select(snapshots)
    }

    private fun getSyntheticCloudSeries(locationLat: Double, locationLon: Double, source: String, startMs: Long, endMs: Long): Map<Long, Int> =
        getHourlyHistory(locationLat, locationLon, source, startMs, endMs)
            // Total-preferred, like every other cloud read. The fallback still finds the rows
            // written while the previous-runs variable was the LOW layer, which carry their value
            // on cloudCoverLow; nothing needs migrating.
            .mapNotNull { row -> with(VisibleCloudCover) { row.visibleCloudCover() }?.let { row.dateTime to it } }
            .toMap()

    fun upsertHourlyForecastHistory(locationLat: Double, locationLon: Double, source: String, timestampToGroupPredictions: Long, hourly: List<HourlyForecast>) {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO hourly_forecast_history 
                    (dateTime, locationLat, locationLon, temperature, condition, source, timestampToGroupPredictions, precipProbability, cloudCover, cloudCoverLow, cloudCoverMid, cloudCoverHigh, precipAmountMm, fetchedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    val now = System.currentTimeMillis()
                    // Quantize the PK coordinate so jitter overwrites instead of fragmenting.
                    val keyLat = LocationMatch.quantize(locationLat)
                    val keyLon = LocationMatch.quantize(locationLon)
                    for (h in hourly) {
                        stmt.setLong(1, h.dateTime)
                        stmt.setDouble(2, keyLat)
                        stmt.setDouble(3, keyLon)
                        stmt.setFloat(4, h.temperature)
                        stmt.setString(5, h.condition)
                        stmt.setString(6, source)
                        stmt.setLong(7, timestampToGroupPredictions)
                        stmt.setNullableInt(8, h.precipProbability)
                        stmt.setNullableInt(9, h.cloudCover)
                        stmt.setNullableInt(10, h.cloudCoverLow)
                        stmt.setNullableInt(11, h.cloudCoverMid)
                        stmt.setNullableInt(12, h.cloudCoverHigh)
                        stmt.setNullableFloat(13, h.precipAmountMm)
                        stmt.setLong(14, now)
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
                    (targetDate, dateOfPrediction, locationLat, locationLon, highTemp, lowTemp, condition,
                     nativeDailyIconToken, isClimateNormal, source, precipProbability, precipAmountMm,
                     daytimePrecipProbability, nighttimePrecipProbability, batchFetchedAt, fetchedAt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    val now = System.currentTimeMillis()
                    val todayEpoch = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    // Quantize the storage key so coordinate jitter between fetches lands on the
                    // same PK instead of stranding a stale per-precision site (see LocationMatch.quantize).
                    val keyLat = LocationMatch.quantize(locationLat)
                    val keyLon = LocationMatch.quantize(locationLon)
                    for (d in daily) {
                        val targetDate = LocalDate.parse(d.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                        // Parity with Android (ForecastRepository): today keeps decimals, future days
                        // round to integer. Climate normals are stored as-is. Shared rule keeps both
                        // platforms writing identical values for the same fetch.
                        val isToday = targetDate == todayEpoch
                        val highToStore = if (d.isClimateNormal) d.highTemp
                            else ForecastTempRounding.forStorage(d.highTemp, isToday) ?: d.highTemp
                        val lowToStore = if (d.isClimateNormal) d.lowTemp
                            else ForecastTempRounding.forStorage(d.lowTemp, isToday) ?: d.lowTemp
                        stmt.setLong(1, targetDate)
                        stmt.setLong(2, todayEpoch) // simplified for desktop Tier 1
                        stmt.setDouble(3, keyLat)
                        stmt.setDouble(4, keyLon)
                        stmt.setFloat(5, highToStore)
                        stmt.setFloat(6, lowToStore)
                        stmt.setString(7, d.condition)
                        stmt.setString(8, d.iconToken)
                        stmt.setInt(9, if (d.isClimateNormal) 1 else 0)
                        stmt.setString(10, source)
                        stmt.setNullableInt(11, d.precipProbability)
                        stmt.setNullableFloat(12, d.precipAmountMm)
                        stmt.setNullableInt(13, d.daytimePrecipProbability)
                        stmt.setNullableInt(14, d.nighttimePrecipProbability)
                        stmt.setLong(15, now)
                        stmt.setLong(16, now)
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
                    (stationId, stationName, timestamp, temperature, condition, locationLat, locationLon, distanceKm, stationType, fetchedAt, maxTempLast24h, minTempLast24h, api, precipAmountMm, isWebFallback, qcFailed, cloudCover, cloudCoverLow, isMetar, rawMetar, cloudCoverMid, cloudCoverHigh, cloudBaseLowMeters, cloudBaseMidMeters, cloudBaseHighMeters, cloudEnvelopeBaseMeters, cloudEnvelopeTopMeters, cloudVerticalKind)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        stmt.setInt(15, if (obs.isWebFallback) 1 else 0)
                        stmt.setInt(16, if (obs.qcFailed) 1 else 0)
                        stmt.setNullableInt(17, obs.cloudCover)
                        stmt.setNullableInt(18, obs.cloudCoverLow)
                        stmt.setInt(19, if (obs.isMetar) 1 else 0)
                        stmt.setString(20, obs.rawMetar)
                        stmt.setNullableInt(21, obs.cloudCoverMid)
                        stmt.setNullableInt(22, obs.cloudCoverHigh)
                        stmt.setNullableInt(23, obs.cloudBaseLowMeters)
                        stmt.setNullableInt(24, obs.cloudBaseMidMeters)
                        stmt.setNullableInt(25, obs.cloudBaseHighMeters)
                        stmt.setNullableInt(26, obs.cloudEnvelopeBaseMeters)
                        stmt.setNullableInt(27, obs.cloudEnvelopeTopMeters)
                        stmt.setInt(28, obs.cloudVerticalKind.dbCode)
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
                WHERE ${LocationMatch.JDBC_WHERE} AND timestamp >= ?
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
                        precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                        isWebFallback = rs.getInt("isWebFallback") == 1,
                        qcFailed = rs.getInt("qcFailed") == 1,
                        cloudCover = rs.getNullableInt("cloudCover"),
                        cloudCoverLow = rs.getNullableInt("cloudCoverLow"),
                        isMetar = rs.getInt("isMetar") == 1,
                        rawMetar = rs.getString("rawMetar"),
                        cloudCoverMid = rs.getNullableInt("cloudCoverMid"),
                        cloudCoverHigh = rs.getNullableInt("cloudCoverHigh"),
                        cloudBaseLowMeters = rs.getNullableInt("cloudBaseLowMeters"),
                        cloudBaseMidMeters = rs.getNullableInt("cloudBaseMidMeters"),
                        cloudBaseHighMeters = rs.getNullableInt("cloudBaseHighMeters"),
                        cloudEnvelopeBaseMeters = rs.getNullableInt("cloudEnvelopeBaseMeters"),
                        cloudEnvelopeTopMeters = rs.getNullableInt("cloudEnvelopeTopMeters"),
                        cloudVerticalKind = CloudVerticalKind.fromDbCode(rs.getInt("cloudVerticalKind")),
                    )
                }
            }
        }
        return null
    }

    fun upsertDailyHistory(extremes: List<DailyHistory>) {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT OR REPLACE INTO daily_history
                    (date, source, locationLat, locationLon, computedHighTemp, computedLowTemp, condition, updatedAt, precipAmountMm, precipDayMm, precipNightMm, forecastDayPrecipChance, forecastNightPrecipChance, forecastHighTemp, forecastLowTemp, forecastPrecipAmountMm, noonCloudPercent, apiHighTemp, apiLowTemp, apiStationId, apiStationDistanceKm, actualsSource, lastWriter)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    for (ex in extremes) {
                        stmt.setLong(1, ex.date)
                        stmt.setString(2, ex.source)
                        stmt.setDouble(3, ex.locationLat)
                        stmt.setDouble(4, ex.locationLon)
                    stmt.setNullableFloat(5, ex.computedHighTemp)
                    stmt.setNullableFloat(6, ex.computedLowTemp)
                        stmt.setString(7, ex.condition)
                        stmt.setLong(8, ex.updatedAt)
                        stmt.setNullableFloat(9, ex.precipAmountMm)
                        stmt.setNullableFloat(10, ex.precipDayMm)
                        stmt.setNullableFloat(11, ex.precipNightMm)
                        stmt.setNullableInt(12, ex.forecastDayPrecipChance)
                        stmt.setNullableInt(13, ex.forecastNightPrecipChance)
                        stmt.setNullableFloat(14, ex.forecastHighTemp)
                        stmt.setNullableFloat(15, ex.forecastLowTemp)
                        stmt.setNullableFloat(16, ex.forecastPrecipAmountMm)
                        stmt.setNullableInt(17, ex.noonCloudPercent)
                        stmt.setNullableFloat(18, ex.apiHighTemp)
                        stmt.setNullableFloat(19, ex.apiLowTemp)
                        stmt.setString(20, ex.apiStationId)
                        stmt.setNullableFloat(21, ex.apiStationDistanceKm)
                        stmt.setString(22, ex.actualsSource)
                        stmt.setString(23, ex.lastWriter)
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
                stmt.execute("DELETE FROM daily_history WHERE updatedAt < $beforeEpochMs")
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
     * Replaces this location's cached climate normals with 12 monthly-mean rows (keyed "MM-15").
     * Clears other locations first so the cache holds one location at a time (matches Android).
     */
    fun upsertClimateNormals(locationKey: String, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>) {
        db.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM climate_normals WHERE locationKey != ?").use { stmt ->
                    stmt.setString(1, locationKey)
                    stmt.executeUpdate()
                }
                val now = System.currentTimeMillis()
                conn.prepareStatement(
                    """
                    INSERT OR REPLACE INTO climate_normals (monthDay, locationKey, highTemp, lowTemp, fetchedAt)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    for (month in 1..12) {
                        val high = monthlyHigh[month] ?: continue
                        val low = monthlyLow[month] ?: continue
                        stmt.setString(1, "${month.toString().padStart(2, '0')}-15")
                        stmt.setString(2, locationKey)
                        stmt.setFloat(3, high)
                        stmt.setFloat(4, low)
                        stmt.setLong(5, now)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /** Reads this location's 12 cached monthly normals as (month -> high, month -> low); empty if none. */
    fun getClimateNormals(locationKey: String): Pair<Map<Int, Float>, Map<Int, Float>> {
        val monthlyHigh = mutableMapOf<Int, Float>()
        val monthlyLow = mutableMapOf<Int, Float>()
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT monthDay, highTemp, lowTemp FROM climate_normals WHERE locationKey = ?").use { stmt ->
                stmt.setString(1, locationKey)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val month = rs.getString("monthDay").take(2).toInt()
                    monthlyHigh[month] = rs.getFloat("highTemp")
                    monthlyLow[month] = rs.getFloat("lowTemp")
                }
            }
        }
        return monthlyHigh to monthlyLow
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

    fun getLatestLogByTagAndMessagePrefix(
        tag: String,
        messagePrefix: String,
    ): DesktopLogEntity? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT timestamp, level, tag, message
                FROM app_logs
                WHERE tag = ? AND message LIKE ?
                ORDER BY timestamp DESC, id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tag)
                stmt.setString(2, "$messagePrefix%")
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return DesktopLogEntity(
                        timestamp = rs.getLong("timestamp"),
                        level = rs.getString("level"),
                        tag = rs.getString("tag"),
                        message = rs.getString("message"),
                    )
                }
            }
        }
        return null
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
                    stmt.setString(1, "source=$source %")
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
                    stmt.setString(1, "source=$source %")
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

    /**
     * The newest durable fetch time for the observation feed that actually supplies current
     * temperature. This is intentionally provider- and location-specific: a recent native
     * Open-Meteo row must not make borrowed METAR actuals look fresh, and another saved location
     * must not suppress this location's refresh.
     */
    fun getLatestObservationFetchedAt(
        locationLat: Double,
        locationLon: Double,
        providerId: String,
    ): Long? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """
                    SELECT MAX(fetchedAt) FROM observations
                    WHERE locationLat = ? AND locationLon = ? AND api = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, providerId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val fetchedAt = rs.getLong(1)
                    return if (fetchedAt > 0L) fetchedAt else null
                }
            }
        }
        return null
    }

    /**
     * Timestamp of the newest wake/network transition the daemon recorded. WAKE_EVENT rows are a
     * dedicated contract written only at the three real transitions (resume kick accepted,
     * network-restored kick accepted, daemon startup) — deliberately NOT the diagnostic
     * RESUME_DETECT/NETWORK_DETECT rows, whose wording and levels are free to change. The UI uses
     * this to blame a fresh offline fetch failure on post-wake network warm-up rather than
     * surfacing it as a hard error.
     */
    fun getLatestWakeEventMs(): Long? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT MAX(timestamp) FROM app_logs WHERE tag = '${WakeEventLog.TAG}'"
            ).use { stmt ->
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val ts = rs.getLong(1)
                    return if (ts > 0L) ts else null
                }
            }
        }
        return null
    }

    fun getLatestCurrentTempStatus(source: String): CurrentTempStatus? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT timestamp, message FROM app_logs WHERE tag = '${CurrentTempStatusLog.TAG}' AND message LIKE ? ORDER BY timestamp DESC LIMIT 1"
            ).use { stmt ->
                stmt.setString(1, "source=$source %")
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val timestamp = rs.getLong("timestamp")
                    val msg = rs.getString("message")
                    val ok = CurrentTempStatusLog.isOk(msg)
                    return CurrentTempStatus(timestamp, ok, msg)
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

    /**
     * Marks a completed fetch *attempt* on a station's newest observation row. A fetch that
     * completes but yields nothing storable (station publishing null-temperature reports) would
     * otherwise leave fetchedAt frozen at the last stored data, making a silent station look like
     * a stalled pipeline in the stations list. Touching only the newest row is sufficient — the
     * UI displays one row per station.
     */
    fun touchLatestObservationFetchedAt(stationId: String, nowMs: Long) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE observations SET fetchedAt = ?
                WHERE stationId = ?
                  AND timestamp = (SELECT MAX(timestamp) FROM observations WHERE stationId = ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, nowMs)
                stmt.setString(2, stationId)
                stmt.setString(3, stationId)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Like [getRecentLogs] but restricts to the given tags in SQL, so the [limit] counts only
     * matching rows. The verbose current-temp tags (e.g. CurrentTempResolver, ~21k rows) otherwise
     * swamp any all-tag fetch then in-memory filter, starving the result to near-zero rows.
     */
    fun getRecentLogsByTags(tags: List<String>, limit: Int = 100): List<DesktopLogEntity> {
        if (tags.isEmpty()) return emptyList()
        val placeholders = tags.joinToString(",") { "?" }
        val result = mutableListOf<DesktopLogEntity>()
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT timestamp, level, tag, message FROM app_logs " +
                    "WHERE tag IN ($placeholders) ORDER BY timestamp DESC LIMIT ?"
            ).use { stmt ->
                tags.forEachIndexed { i, t -> stmt.setString(i + 1, t) }
                stmt.setInt(tags.size + 1, limit)
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
                WHERE ${LocationMatch.JDBC_WHERE} AND source = ? AND fetchedAt >= ?
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
                        cloudCoverLow = rs.getNullableInt("cloudCoverLow"),
                        cloudCoverMid = rs.getNullableInt("cloudCoverMid"),
                        cloudCoverHigh = rs.getNullableInt("cloudCoverHigh"),
                        precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                        source = source,
                        fetchedAt = rs.getLong("fetchedAt"),
                        locationLat = rs.getDouble("locationLat"),
                        locationLon = rs.getDouble("locationLon"),
                    ))
                }
            }
        }
        return result
    }

    /** Plain dateTime-range read of the LIVE hourly_forecasts table (no history blend). */
    fun getHourlyForecasts(locationLat: Double, locationLon: Double, source: String, startMs: Long, endMs: Long): List<HourlyForecast> {
        val result = mutableListOf<HourlyForecast>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT * FROM hourly_forecasts
                WHERE ${LocationMatch.JDBC_WHERE} AND source = ? AND dateTime >= ? AND dateTime <= ?
                ORDER BY dateTime ASC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                stmt.setLong(4, startMs)
                stmt.setLong(5, endMs)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    result.add(HourlyForecast(
                        dateTime = rs.getLong("dateTime"),
                        temperature = rs.getFloat("temperature"),
                        condition = rs.getString("condition"),
                        precipProbability = rs.getNullableInt("precipProbability"),
                        cloudCover = rs.getNullableInt("cloudCover"),
                        cloudCoverLow = rs.getNullableInt("cloudCoverLow"),
                        cloudCoverMid = rs.getNullableInt("cloudCoverMid"),
                        cloudCoverHigh = rs.getNullableInt("cloudCoverHigh"),
                        precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                        source = source,
                        fetchedAt = rs.getLong("fetchedAt"),
                        locationLat = rs.getDouble("locationLat"),
                        locationLon = rs.getDouble("locationLon"),
                    ))
                }
            }
        }
        return result
    }

    fun getHourlyHistory(locationLat: Double, locationLon: Double, source: String, startMs: Long, endMs: Long, nowMs: Long = System.currentTimeMillis()): List<HourlyForecast> {
        val result = mutableListOf<HourlyForecast>()
        db.getConnection().use { conn ->
            // GENERIC_GAP ('Generic') is a FUTURE-ONLY filler — it covers forecast hours beyond the
            // selected API's horizon, and must NEVER stand in for history (its Open-Meteo decimals
            // would masquerade as the real, whole-degree NWS forecast that once existed for past hours,
            // or invent a forecast for hours we never actually forecast). So only admit Generic rows
            // for future timestamps; the real source supplies all past/near hours.
            val sql = """
                SELECT dateTime, temperature, condition, precipProbability, cloudCover, cloudCoverLow, cloudCoverMid, cloudCoverHigh, precipAmountMm, fetchedAt, source, locationLat, locationLon
                FROM hourly_forecast_history
                WHERE ${LocationMatch.JDBC_WHERE} AND (source = ? OR (source = 'Generic' AND dateTime > ?)) AND dateTime >= ? AND dateTime <= ?
                ORDER BY dateTime ASC, (source = ?) DESC, timestampToGroupPredictions DESC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                stmt.setLong(4, nowMs)
                stmt.setLong(5, startMs)
                stmt.setLong(6, endMs)
                stmt.setString(7, source)
                val rs = stmt.executeQuery()
                // One merged row per (dateTime, source). The query is ordered snapshotBucket DESC, so
                // the FRESHEST snapshot of an hour is seen first and supplies temperature and
                // condition — the latest forecast, matching the live line and HourlyForecastStitcher.
                // (This feeds the stitcher's `history` fallback, used only for hours that have aged out
                // of the live table.) Older snapshots of the same hour only backfill nullable fields the
                // freshest one is missing — chiefly cloudCover: NWS frequently omits sky cover on the
                // near-term hours, yet provided it for that same hour when it sat further out in the
                // forecast horizon, so coalescing across buckets keeps the cloud-cover graph populated.
                val merged = LinkedHashMap<Pair<Long, String>, HourlyForecast>()
                while (rs.next()) {
                    val dateTime = rs.getLong("dateTime")
                    val rowSource = rs.getString("source")
                    val row = HourlyForecast(
                        dateTime = dateTime,
                        temperature = rs.getFloat("temperature"),
                        condition = rs.getString("condition"),
                        precipProbability = rs.getNullableInt("precipProbability"),
                        cloudCover = rs.getNullableInt("cloudCover"),
                        cloudCoverLow = rs.getNullableInt("cloudCoverLow"),
                        cloudCoverMid = rs.getNullableInt("cloudCoverMid"),
                        cloudCoverHigh = rs.getNullableInt("cloudCoverHigh"),
                        precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                        source = rowSource,
                        fetchedAt = rs.getLong("fetchedAt"),
                        locationLat = rs.getDouble("locationLat"),
                        locationLon = rs.getDouble("locationLon"),
                    )
                    val key = dateTime to rowSource
                    val existing = merged[key]
                    if (existing == null) {
                        merged[key] = row
                    } else if (existing.cloudCover == null || existing.cloudCoverLow == null ||
                        existing.cloudCoverMid == null || existing.cloudCoverHigh == null ||
                        existing.precipProbability == null || existing.precipAmountMm == null
                    ) {
                        merged[key] = existing.copy(
                            precipProbability = existing.precipProbability ?: row.precipProbability,
                            cloudCover = existing.cloudCover ?: row.cloudCover,
                            cloudCoverLow = existing.cloudCoverLow ?: row.cloudCoverLow,
                            cloudCoverMid = existing.cloudCoverMid ?: row.cloudCoverMid,
                            cloudCoverHigh = existing.cloudCoverHigh ?: row.cloudCoverHigh,
                            precipAmountMm = existing.precipAmountMm ?: row.precipAmountMm,
                        )
                    }
                }
                result.addAll(merged.values)
            }
        }
        return result
    }

    fun getHourlyHistoryCount(locationLat: Double, locationLon: Double, source: String, startMs: Long, endMs: Long): Int {
        db.getConnection().use { conn ->
            val sql = """
                SELECT COUNT(DISTINCT dateTime) FROM hourly_forecast_history
                WHERE ${LocationMatch.JDBC_WHERE} AND (source = ? OR source = 'Generic')
                AND dateTime >= ? AND dateTime <= ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                stmt.setLong(4, startMs)
                stmt.setLong(5, endMs)
                val rs = stmt.executeQuery()
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    fun getHourlyWithHistory(locationLat: Double, locationLon: Double, source: String, startMs: Long, endMs: Long, maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): List<HourlyForecast> {
        val current = getLatestHourly(locationLat, locationLon, source, maxAgeMs).filter { it.dateTime in startMs..endMs }
        val history = getHourlyHistory(locationLat, locationLon, source, startMs, endMs)
        val stitched = HourlyForecastStitcher.stitch(current, history, nowMs, locationLat, locationLon)
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
            val latestBatchSql = "SELECT MAX(batchFetchedAt) FROM forecasts WHERE ${LocationMatch.JDBC_WHERE} AND source = ?"
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
                WHERE ${LocationMatch.JDBC_WHERE} AND source = ? AND batchFetchedAt = ?
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
                        precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                        isClimateNormal = rs.getInt("isClimateNormal") == 1,
                        source = source,
                        daytimePrecipProbability = rs.getNullableInt("daytimePrecipProbability"),
                        nighttimePrecipProbability = rs.getNullableInt("nighttimePrecipProbability"),
                    ))
                }
            }

            // Repair degenerate (high == low) days. Late in the day NWS stops reporting a low for
            // today, so NwsDailyMapper collapses it to low = high (`temps.second ?: high`). That
            // latest "forecast" is no longer a genuine range, so fall back to the most recent stored
            // forecast for the same target day that still had a real high/low spread — the last
            // genuine historical forecast. (Runs in a separate pass so the read ResultSet above is
            // already closed before issuing these lookups on the same connection.)
            for (i in result.indices) {
                val day = result[i]
                    if (day.highTemp != day.lowTemp) continue
                val targetEpoch = LocalDate.parse(day.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val genuineSql = """
                    SELECT highTemp, lowTemp, condition, nativeDailyIconToken,
                           precipProbability, precipAmountMm, isClimateNormal
                    FROM forecasts
                    WHERE ${LocationMatch.JDBC_WHERE} AND source = ? AND targetDate = ?
                        AND highTemp IS NOT NULL AND lowTemp IS NOT NULL AND highTemp <> lowTemp
                    ORDER BY batchFetchedAt DESC, fetchedAt DESC
                    LIMIT 1
                """.trimIndent()
                val genuine = conn.prepareStatement(genuineSql).use { stmt ->
                    stmt.setDouble(1, locationLat)
                    stmt.setDouble(2, locationLon)
                    stmt.setString(3, source)
                    stmt.setLong(4, targetEpoch)
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        day.copy(
                        highTemp = rs.getFloat("highTemp"),
                        lowTemp = rs.getFloat("lowTemp"),
                            condition = rs.getString("condition"),
                            iconToken = rs.getString("nativeDailyIconToken"),
                            precipProbability = rs.getNullableInt("precipProbability"),
                            precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                            isClimateNormal = rs.getInt("isClimateNormal") == 1,
                        )
                    } else {
                        null
                    }
                }
                if (genuine != null) result[i] = genuine
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
                WHERE ${LocationMatch.JDBC_WHERE} AND source = ?
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
                    precipProbability, daytimePrecipProbability, nighttimePrecipProbability,
                    precipAmountMm, fetchedAt, batchFetchedAt
                FROM forecasts
                WHERE ${LocationMatch.JDBC_WHERE} AND source = ?
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
                            // This query intentionally skips the newest batch, so it surfaces older
                            // rows — including any that predate the NWS sentinel filter.
                            highTemp = rs.getNullableFloat("highTemp").orNullIfImplausibleTempF(),
                            lowTemp = rs.getNullableFloat("lowTemp").orNullIfImplausibleTempF(),
                            condition = rs.getString("condition"),
                            iconToken = rs.getString("nativeDailyIconToken"),
                            precipProbability = rs.getNullableInt("precipProbability"),
                            precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                            fetchedAt = rs.getLong("fetchedAt"),
                            daytimePrecipProbability = rs.getNullableInt("daytimePrecipProbability"),
                            nighttimePrecipProbability = rs.getNullableInt("nighttimePrecipProbability"),
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
                            isWebFallback = rs.getInt("isWebFallback") == 1,
                            qcFailed = rs.getInt("qcFailed") == 1,
                            cloudCover = rs.getNullableInt("cloudCover"),
                            cloudCoverLow = rs.getNullableInt("cloudCoverLow"),
                            isMetar = rs.getInt("isMetar") == 1,
                            rawMetar = rs.getString("rawMetar"),
                            cloudCoverMid = rs.getNullableInt("cloudCoverMid"),
                            cloudCoverHigh = rs.getNullableInt("cloudCoverHigh"),
                            cloudBaseLowMeters = rs.getNullableInt("cloudBaseLowMeters"),
                            cloudBaseMidMeters = rs.getNullableInt("cloudBaseMidMeters"),
                            cloudBaseHighMeters = rs.getNullableInt("cloudBaseHighMeters"),
                            cloudEnvelopeBaseMeters = rs.getNullableInt("cloudEnvelopeBaseMeters"),
                            cloudEnvelopeTopMeters = rs.getNullableInt("cloudEnvelopeTopMeters"),
                            cloudVerticalKind = CloudVerticalKind.fromDbCode(rs.getInt("cloudVerticalKind")),
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
                WHERE ${LocationMatch.JDBC_WHERE} AND timestamp >= ? AND timestamp < ?
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
                        isWebFallback = rs.getInt("isWebFallback") == 1,
                        qcFailed = rs.getInt("qcFailed") == 1,
                        cloudCover = rs.getNullableInt("cloudCover"),
                        cloudCoverLow = rs.getNullableInt("cloudCoverLow"),
                        isMetar = rs.getInt("isMetar") == 1,
                        rawMetar = rs.getString("rawMetar"),
                        cloudCoverMid = rs.getNullableInt("cloudCoverMid"),
                        cloudCoverHigh = rs.getNullableInt("cloudCoverHigh"),
                        cloudBaseLowMeters = rs.getNullableInt("cloudBaseLowMeters"),
                        cloudBaseMidMeters = rs.getNullableInt("cloudBaseMidMeters"),
                        cloudBaseHighMeters = rs.getNullableInt("cloudBaseHighMeters"),
                        cloudEnvelopeBaseMeters = rs.getNullableInt("cloudEnvelopeBaseMeters"),
                        cloudEnvelopeTopMeters = rs.getNullableInt("cloudEnvelopeTopMeters"),
                        cloudVerticalKind = CloudVerticalKind.fromDbCode(rs.getInt("cloudVerticalKind")),
                    ))
                }
            }
        }
        return result
    }

    fun getExtremesInRange(startEpoch: Long, endEpoch: Long, locationLat: Double, locationLon: Double): List<DailyHistory> {
        val result = mutableListOf<DailyHistory>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT * FROM daily_history
                WHERE ${LocationMatch.JDBC_WHERE} AND date >= ? AND date <= ?
                ORDER BY date ASC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setLong(3, startEpoch)
                stmt.setLong(4, endEpoch)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    result.add(DailyHistory(
                        date = rs.getLong("date"),
                        source = rs.getString("source"),
                        locationLat = rs.getDouble("locationLat"),
                        locationLon = rs.getDouble("locationLon"),
                        computedHighTemp = rs.getNullableFloat("computedHighTemp"),
                        computedLowTemp = rs.getNullableFloat("computedLowTemp"),
                        condition = rs.getString("condition"),
                        updatedAt = rs.getLong("updatedAt"),
                        precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                        precipDayMm = rs.getNullableFloat("precipDayMm"),
                        precipNightMm = rs.getNullableFloat("precipNightMm"),
                        forecastDayPrecipChance = rs.getNullableInt("forecastDayPrecipChance"),
                        forecastNightPrecipChance = rs.getNullableInt("forecastNightPrecipChance"),
                        forecastHighTemp = rs.getNullableFloat("forecastHighTemp"),
                        forecastLowTemp = rs.getNullableFloat("forecastLowTemp"),
                        forecastPrecipAmountMm = rs.getNullableFloat("forecastPrecipAmountMm"),
                        noonCloudPercent = rs.getNullableInt("noonCloudPercent"),
                        apiHighTemp = rs.getNullableFloat("apiHighTemp"),
                        apiLowTemp = rs.getNullableFloat("apiLowTemp"),
                        apiStationId = rs.getString("apiStationId"),
                        apiStationDistanceKm = rs.getNullableFloat("apiStationDistanceKm"),
                        actualsSource = rs.getString("actualsSource"),
                        lastWriter = rs.getString("lastWriter"),
                    ))
                }
            }
        }
        return result
    }

    fun getDailyActuals(startEpoch: Long, endEpoch: Long, locationLat: Double, locationLon: Double, source: String): Map<String, DailyHistory> {
        return getExtremesInRange(startEpoch, endEpoch, locationLat, locationLon)
            .filter { it.source == source }
            .associateBy { LocalDate.ofEpochDay(it.date / 86_400_000L).toString() }
    }

    fun getForecastsInRangeBySource(startEpoch: Long, endEpoch: Long, locationLat: Double, locationLon: Double, source: String): List<DesktopForecastRow> {
        val result = mutableListOf<DesktopForecastRow>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT targetDate, dateOfPrediction, source, highTemp, lowTemp, fetchedAt,
                    condition, precipAmountMm, isClimateNormal FROM forecasts
                WHERE ${LocationMatch.JDBC_WHERE} AND source = ? AND targetDate >= ? AND targetDate <= ?
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
                        dateOfPrediction = rs.getLong("dateOfPrediction"),
                        source = rs.getString("source"),
                        highTemp = rs.getNullableFloat("highTemp").orNullIfImplausibleTempF(),
                        lowTemp = rs.getNullableFloat("lowTemp").orNullIfImplausibleTempF(),
                        fetchedAt = rs.getLong("fetchedAt"),
                        condition = rs.getString("condition"),
                        precipAmountMm = rs.getNullableFloat("precipAmountMm"),
                        isClimateNormal = rs.getInt("isClimateNormal") == 1,
                    ))
                }
            }
        }
        return result
    }

    /**
     * All stored forecast snapshots for a single target day (every source, every fetch), used by the
     * forecast-history screen to plot how the prediction evolved. Mirrors Android
     * `ForecastDao.getForecastEvolution`: ordered by when the forecast was made, then batch/fetch time.
     */
    fun getForecastEvolution(targetDate: Long, locationLat: Double, locationLon: Double): List<DesktopForecastRow> {
        val result = mutableListOf<DesktopForecastRow>()
        db.getConnection().use { conn ->
            val sql = """
                SELECT targetDate, dateOfPrediction, source, highTemp, lowTemp, fetchedAt FROM forecasts
                WHERE targetDate = ? AND ${LocationMatch.JDBC_WHERE}
                ORDER BY dateOfPrediction ASC, batchFetchedAt ASC, fetchedAt ASC
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, targetDate)
                stmt.setDouble(2, locationLat)
                stmt.setDouble(3, locationLon)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    result.add(DesktopForecastRow(
                        targetDate = rs.getLong("targetDate"),
                        dateOfPrediction = rs.getLong("dateOfPrediction"),
                        source = rs.getString("source"),
                        highTemp = rs.getNullableFloat("highTemp").orNullIfImplausibleTempF(),
                        lowTemp = rs.getNullableFloat("lowTemp").orNullIfImplausibleTempF(),
                        fetchedAt = rs.getLong("fetchedAt"),
                    ))
                }
            }
        }
        return result
    }

    /**
     * Overwrites the single resolved-current-status row for (location, source). Written by the
     * daemon after each fetch; read by the genmon panel and the UI popup so they consume one
     * published value instead of re-deriving it independently.
     */
    fun upsertCurrentStatus(status: CurrentStatus) {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO current_status
                (locationLat, locationLon, source, displayTempF, appliedDeltaF, deltaFromYesterdayF, observedAtMs, condition, updatedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setDouble(1, LocationMatch.quantize(status.locationLat))
                stmt.setDouble(2, LocationMatch.quantize(status.locationLon))
                stmt.setString(3, status.source)
                stmt.setNullableFloat(4, status.displayTempF)
                stmt.setNullableFloat(5, status.appliedDeltaF)
                stmt.setNullableFloat(6, status.deltaFromYesterdayF)
                stmt.setNullableLong(7, status.observedAtMs)
                stmt.setString(8, status.condition)
                stmt.setLong(9, status.updatedAt)
                stmt.executeUpdate()
            }
        }
    }

    /** Reads the latest published [CurrentStatus] for (location, source), or null when none yet. */
    fun getCurrentStatus(locationLat: Double, locationLon: Double, source: String): CurrentStatus? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT locationLat, locationLon, source, displayTempF, appliedDeltaF, deltaFromYesterdayF, observedAtMs, condition, updatedAt
                FROM current_status
                WHERE ${LocationMatch.JDBC_WHERE} AND source = ?
                ORDER BY updatedAt DESC LIMIT 1
                """.trimIndent()
            ).use { stmt ->
                stmt.setDouble(1, locationLat)
                stmt.setDouble(2, locationLon)
                stmt.setString(3, source)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return CurrentStatus(
                        locationLat = rs.getDouble("locationLat"),
                        locationLon = rs.getDouble("locationLon"),
                        source = rs.getString("source"),
                        displayTempF = rs.getNullableFloat("displayTempF"),
                        appliedDeltaF = rs.getNullableFloat("appliedDeltaF"),
                        deltaFromYesterdayF = rs.getNullableFloat("deltaFromYesterdayF"),
                        observedAtMs = rs.getNullableLong("observedAtMs"),
                        condition = rs.getString("condition"),
                        updatedAt = rs.getLong("updatedAt"),
                    )
                }
            }
        }
        return null
    }

    // Helper extensions for nullable types with JDBC
    private fun PreparedStatement.setNullableInt(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    private fun PreparedStatement.setNullableFloat(index: Int, value: Float?) {
        if (value == null) setNull(index, Types.REAL) else setFloat(index, value)
    }

    private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.INTEGER) else setLong(index, value)
    }

    private fun java.sql.ResultSet.getNullableInt(column: String): Int? {
        val v = getInt(column)
        return if (wasNull()) null else v
    }

    private fun java.sql.ResultSet.getNullableFloat(column: String): Float? {
        val v = getFloat(column)
        return if (wasNull()) null else v
    }

    private fun java.sql.ResultSet.getNullableLong(column: String): Long? {
        val v = getLong(column)
        return if (wasNull()) null else v
    }
}

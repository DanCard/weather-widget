package com.weatherwidget.data.local

import androidx.room.*
import com.weatherwidget.shared.actuals.DailyForecastSelector

@Dao
interface ForecastDao {
    @Query("SELECT * FROM forecasts ORDER BY batchFetchedAt DESC, fetchedAt DESC LIMIT 1")
    suspend fun getLatestWeather(): ForecastEntity?

    @Query(
        """
        SELECT * FROM forecasts
        WHERE source = :source
        AND ${LocationMatch.ROOM_WHERE}
        ORDER BY batchFetchedAt DESC, fetchedAt DESC
        LIMIT 1
    """,
    )
    suspend fun getLatestForecastBySource(
        source: String,
        lat: Double,
        lon: Double,
    ): ForecastEntity?

    @Query(
        """
        SELECT * FROM forecasts
        WHERE source = :source
        ORDER BY batchFetchedAt DESC, fetchedAt DESC
        LIMIT 1
    """,
    )
    suspend fun getLatestWeatherBySource(source: String): ForecastEntity?

    @Query(
        """
        SELECT * FROM forecasts
        WHERE targetDate = :targetDate
        AND ${LocationMatch.ROOM_WHERE}
        ORDER BY forecastDate DESC, batchFetchedAt DESC, fetchedAt DESC
        LIMIT 1
    """,
    )
    suspend fun getForecastForDate(
        targetDate: Long,
        lat: Double,
        lon: Double,
    ): ForecastEntity?

    @Query(
        """
        SELECT * FROM forecasts
        WHERE targetDate = :targetDate
        AND forecastDate = :forecastDate
        AND ${LocationMatch.ROOM_WHERE}
        ORDER BY batchFetchedAt DESC, fetchedAt DESC
        LIMIT 1
    """,
    )
    suspend fun getSpecificForecast(
        targetDate: Long,
        forecastDate: Long,
        lat: Double,
        lon: Double,
    ): ForecastEntity?

    @Query(
        """
        SELECT * FROM forecasts
        WHERE targetDate = :targetDate
        AND forecastDate = :forecastDate
        AND ${LocationMatch.ROOM_WHERE}
        AND source = :source
        ORDER BY fetchedAt DESC
        LIMIT 1
    """,
    )
    suspend fun getForecastForDateBySource(
        targetDate: Long,
        forecastDate: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): ForecastEntity?

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND batchFetchedAt = (
            SELECT MAX(batchFetchedAt) FROM forecasts f2
            WHERE f2.targetDate = f1.targetDate
            AND f2.source = f1.source
            AND f2.locationLat = f1.locationLat
            AND f2.locationLon = f1.locationLon
        )
        ORDER BY targetDate ASC
    """,
    )
    suspend fun getForecastsInRangeAllSites(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND source IN (:sources)
        AND batchFetchedAt = (
            SELECT MAX(batchFetchedAt) FROM forecasts f2
            WHERE f2.targetDate = f1.targetDate
            AND f2.source = f1.source
            AND f2.locationLat = f1.locationLat
            AND f2.locationLon = f1.locationLon
        )
        ORDER BY targetDate ASC
    """,
    )
    suspend fun getForecastsInRangeForSourcesAllSites(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        sources: List<String>
    ): List<ForecastEntity>

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE source = :source
        AND ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND batchFetchedAt = (
            SELECT MAX(batchFetchedAt) FROM forecasts f2
            WHERE f2.targetDate = f1.targetDate
            AND f2.source = f1.source
            AND f2.locationLat = f1.locationLat
            AND f2.locationLon = f1.locationLon
        )
        ORDER BY targetDate ASC
    """,
    )
    suspend fun getLatestForecastsInRangeBySourceAllSites(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): List<ForecastEntity>

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE source IN (:sources)
        AND ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND highTemp IS NOT NULL
        AND lowTemp IS NOT NULL
        AND batchFetchedAt = (
            SELECT MAX(batchFetchedAt) FROM forecasts f2
            WHERE f2.targetDate = f1.targetDate
            AND f2.source = f1.source
            AND f2.locationLat = f1.locationLat
            AND f2.locationLon = f1.locationLon
            AND f2.highTemp IS NOT NULL
            AND f2.lowTemp IS NOT NULL
        )
        ORDER BY targetDate ASC
    """,
    )
    suspend fun getLatestForecastsInRangeForSourcesAllSites(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        sources: List<String>,
    ): List<ForecastEntity>

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND highTemp IS NOT NULL
        AND lowTemp IS NOT NULL
        AND batchFetchedAt = (
            SELECT MAX(batchFetchedAt) FROM forecasts f2
            WHERE f2.targetDate = f1.targetDate
            AND f2.source = f1.source
            AND f2.locationLat = f1.locationLat
            AND f2.locationLon = f1.locationLon
            AND f2.highTemp IS NOT NULL
            AND f2.lowTemp IS NOT NULL
        )
        ORDER BY targetDate ASC
    """,
    )
    suspend fun getLatestForecastsInRangeAllSites(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        ORDER BY targetDate ASC, batchFetchedAt DESC, fetchedAt DESC
    """,
    )
    suspend fun getAllForecastsInRange(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        AND source IN (:sources)
        ORDER BY targetDate ASC, batchFetchedAt DESC, fetchedAt DESC
    """,
    )
    suspend fun getAllForecastsInRangeForSources(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        sources: List<String>
    ): List<ForecastEntity>

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE ${LocationMatch.ROOM_WHERE}
        AND source = :source
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        ORDER BY targetDate ASC, forecastDate DESC, batchFetchedAt DESC, fetchedAt DESC
    """,
    )
    suspend fun getForecastsInRangeBySource(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        source: String,
    ): List<ForecastEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForecast(forecast: ForecastEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(forecasts: List<ForecastEntity>)

    @Query("SELECT COUNT(*) FROM forecasts")
    suspend fun getCount(): Int

    @Query("DELETE FROM forecasts WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldForecasts(cutoffTime: Long)

    @Query("DELETE FROM forecasts WHERE fetchedAt < :cutoffTime AND source = :source")
    suspend fun deleteOldForecastsBySource(cutoffTime: Long, source: String)

    /**
     * Removes existing rows for the given targets whose fetchedAt falls in a snapshot bucket window
     * [bucketStart, bucketEnd). Used to cap daily forecast-history cadence (4h primary / 8h other):
     * delete the earlier in-bucket snapshot before inserting the latest, so at most one row per
     * bucket survives while the newest row keeps its real fetchedAt.
     */
    @Query(
        """
        DELETE FROM forecasts
        WHERE source = :source
        AND locationLat = :lat AND locationLon = :lon
        AND targetDate IN (:targetDates)
        AND fetchedAt >= :bucketStart AND fetchedAt < :bucketEnd
    """,
    )
    suspend fun deleteForecastsInBucket(
        source: String,
        lat: Double,
        lon: Double,
        targetDates: List<Long>,
        bucketStart: Long,
        bucketEnd: Long,
    )

    @Query(
        """
        SELECT * FROM forecasts
        WHERE targetDate = :targetDate
        AND ${LocationMatch.ROOM_WHERE}
        ORDER BY forecastDate ASC, batchFetchedAt ASC, fetchedAt ASC
    """,
    )
    suspend fun getForecastEvolution(
        targetDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>
}

/**
 * Site-collapsing wrappers over the `...AllSites` range queries. Those queries keep the freshest
 * batch per EXACT (locationLat, locationLon) key inside the [LocationMatch.ROOM_WHERE] box, so a
 * coordinate-jitter hop leaves the abandoned key's last batch alive alongside the live site — and
 * whichever row happens to sort first gets displayed (seen on-device as a days-stale "tomorrow"
 * high). The wrappers collapse to one row per (targetDate, source): same-site preferred, freshest
 * batch wins. History-preserving queries (`getAllForecastsInRange*`, `getForecastsInRangeBySource`,
 * `getForecastEvolution`) intentionally stay uncollapsed — they feed snapshot/evolution views.
 */
private fun collapseSites(rows: List<ForecastEntity>, lat: Double, lon: Double): List<ForecastEntity> =
    DailyForecastSelector.selectFreshestPerDaySource(
        rows,
        centerLat = lat,
        centerLon = lon,
        targetDate = { it.targetDate },
        source = { it.source },
        locationLat = { it.locationLat },
        locationLon = { it.locationLon },
        batchFetchedAt = { it.batchFetchedAt },
        fetchedAt = { it.fetchedAt },
    )

suspend fun ForecastDao.getForecastsInRange(startDate: Long, endDate: Long, lat: Double, lon: Double): List<ForecastEntity> =
    collapseSites(getForecastsInRangeAllSites(startDate, endDate, lat, lon), lat, lon)

suspend fun ForecastDao.getForecastsInRangeForSources(startDate: Long, endDate: Long, lat: Double, lon: Double, sources: List<String>): List<ForecastEntity> =
    collapseSites(getForecastsInRangeForSourcesAllSites(startDate, endDate, lat, lon, sources), lat, lon)

suspend fun ForecastDao.getLatestForecastsInRangeBySource(startDate: Long, endDate: Long, lat: Double, lon: Double, source: String): List<ForecastEntity> =
    collapseSites(getLatestForecastsInRangeBySourceAllSites(startDate, endDate, lat, lon, source), lat, lon)

suspend fun ForecastDao.getLatestForecastsInRangeForSources(startDate: Long, endDate: Long, lat: Double, lon: Double, sources: List<String>): List<ForecastEntity> =
    collapseSites(getLatestForecastsInRangeForSourcesAllSites(startDate, endDate, lat, lon, sources), lat, lon)

suspend fun ForecastDao.getLatestForecastsInRange(startDate: Long, endDate: Long, lat: Double, lon: Double): List<ForecastEntity> =
    collapseSites(getLatestForecastsInRangeAllSites(startDate, endDate, lat, lon), lat, lon)

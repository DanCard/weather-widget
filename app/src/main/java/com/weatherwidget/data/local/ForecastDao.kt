package com.weatherwidget.data.local

import androidx.room.*

@Dao
interface ForecastDao {
    @Query("SELECT * FROM forecasts ORDER BY batchFetchedAt DESC, fetchedAt DESC LIMIT 1")
    suspend fun getLatestWeather(): ForecastEntity?

    @Query(
        """
        SELECT * FROM forecasts
        WHERE source = :source
        AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
        AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
        AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
        AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
    suspend fun getForecastsInRange(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
    suspend fun getForecastsInRangeForSources(
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
        AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
    suspend fun getLatestForecastsInRangeBySource(
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
        AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
    suspend fun getLatestForecastsInRangeForSources(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
        sources: List<String>,
    ): List<ForecastEntity>

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
    suspend fun getLatestForecastsInRange(
        startDate: Long,
        endDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>

    @Query(
        """
        SELECT * FROM forecasts f1
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
        WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
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
        AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1
        AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1
        ORDER BY forecastDate ASC, batchFetchedAt ASC, fetchedAt ASC
    """,
    )
    suspend fun getForecastEvolution(
        targetDate: Long,
        lat: Double,
        lon: Double,
    ): List<ForecastEntity>
}

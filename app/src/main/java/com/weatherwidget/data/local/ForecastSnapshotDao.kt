package com.weatherwidget.data.local

import androidx.room.*

@Dao
interface ForecastSnapshotDao {

    @Query("""
        SELECT * FROM forecast_snapshots
        WHERE targetDate = :targetDate
        AND locationLat = :lat
        AND locationLon = :lon
        ORDER BY forecastDate DESC
        LIMIT 1
    """)
    suspend fun getForecastForDate(targetDate: String, lat: Double, lon: Double): ForecastSnapshotEntity?

    @Query("""
        SELECT * FROM forecast_snapshots
        WHERE targetDate = :targetDate
        AND forecastDate = :forecastDate
        AND locationLat = :lat
        AND locationLon = :lon
    """)
    suspend fun getSpecificForecast(
        targetDate: String,
        forecastDate: String,
        lat: Double,
        lon: Double
    ): ForecastSnapshotEntity?

    @Query("""
        SELECT * FROM forecast_snapshots
        WHERE targetDate = :targetDate
        AND forecastDate = :forecastDate
        AND locationLat = :lat
        AND locationLon = :lon
        AND source = :source
    """)
    suspend fun getForecastForDateBySource(
        targetDate: String,
        forecastDate: String,
        lat: Double,
        lon: Double,
        source: String
    ): ForecastSnapshotEntity?

    @Query("""
        SELECT * FROM forecast_snapshots
        WHERE locationLat = :lat
        AND locationLon = :lon
        AND targetDate >= :startDate
        AND targetDate <= :endDate
        ORDER BY targetDate ASC, forecastDate DESC
    """)
    suspend fun getForecastsInRange(
        startDate: String,
        endDate: String,
        lat: Double,
        lon: Double
    ): List<ForecastSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(snapshot: ForecastSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(snapshots: List<ForecastSnapshotEntity>)

    @Query("DELETE FROM forecast_snapshots WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldSnapshots(cutoffTime: Long)
}

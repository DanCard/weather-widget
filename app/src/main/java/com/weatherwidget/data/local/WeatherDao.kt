package com.weatherwidget.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WeatherDao {
    @Query(
        "SELECT * FROM weather_data WHERE date = :date AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1 AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1",
    )
    suspend fun getWeatherForDate(
        date: String,
        lat: Double,
        lon: Double,
    ): WeatherEntity?

    @Query(
        "SELECT * FROM weather_data WHERE date = :date AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1 AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1 AND source = :source ORDER BY fetchedAt DESC LIMIT 1",
    )
    suspend fun getWeatherForDateBySource(
        date: String,
        lat: Double,
        lon: Double,
        source: String,
    ): WeatherEntity?

    @Query(
        "SELECT * FROM weather_data WHERE date >= :startDate AND date <= :endDate AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1 AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1 AND source = :source ORDER BY date ASC",
    )
    suspend fun getWeatherRangeBySource(
        startDate: String,
        endDate: String,
        lat: Double,
        lon: Double,
        source: String,
    ): List<WeatherEntity>

    @Query(
        "SELECT * FROM weather_data WHERE locationLat BETWEEN :lat - 0.1 AND :lat + 0.1 AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1 ORDER BY date DESC",
    )
    fun getWeatherHistory(
        lat: Double,
        lon: Double,
    ): Flow<List<WeatherEntity>>

    @Query(
        "SELECT * FROM weather_data WHERE date >= :startDate AND date <= :endDate AND locationLat BETWEEN :lat - 0.1 AND :lat + 0.1 AND locationLon BETWEEN :lon - 0.1 AND :lon + 0.1 ORDER BY date ASC",
    )
    suspend fun getWeatherRange(
        startDate: String,
        endDate: String,
        lat: Double,
        lon: Double,
    ): List<WeatherEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeather(weather: WeatherEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(weatherList: List<WeatherEntity>)

    @Query("DELETE FROM weather_data WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldData(cutoffTime: Long)

    @Query("DELETE FROM weather_data WHERE fetchedAt < :cutoffTime AND source = :source")
    suspend fun deleteOldDataBySource(cutoffTime: Long, source: String)

    @Query("SELECT * FROM weather_data ORDER BY fetchedAt DESC LIMIT 1")
    suspend fun getLatestWeather(): WeatherEntity?
}

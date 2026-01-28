package com.weatherwidget.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WeatherDao {

    @Query("SELECT * FROM weather_data WHERE date = :date AND locationLat = :lat AND locationLon = :lon")
    suspend fun getWeatherForDate(date: String, lat: Double, lon: Double): WeatherEntity?

    @Query("SELECT * FROM weather_data WHERE locationLat = :lat AND locationLon = :lon ORDER BY date DESC")
    fun getWeatherHistory(lat: Double, lon: Double): Flow<List<WeatherEntity>>

    @Query("SELECT * FROM weather_data WHERE date >= :startDate AND date <= :endDate AND locationLat = :lat AND locationLon = :lon ORDER BY date ASC")
    suspend fun getWeatherRange(startDate: String, endDate: String, lat: Double, lon: Double): List<WeatherEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeather(weather: WeatherEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(weatherList: List<WeatherEntity>)

    @Query("DELETE FROM weather_data WHERE fetchedAt < :cutoffTime")
    suspend fun deleteOldData(cutoffTime: Long)

    @Query("SELECT * FROM weather_data ORDER BY fetchedAt DESC LIMIT 1")
    suspend fun getLatestWeather(): WeatherEntity?
}

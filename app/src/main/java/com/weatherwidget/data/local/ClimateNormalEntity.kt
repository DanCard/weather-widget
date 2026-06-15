package com.weatherwidget.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(tableName = "climate_normals", primaryKeys = ["monthDay", "locationKey"])
data class ClimateNormalEntity(
    /**
     * "MM-dd" format. Stored as a mid-month anchor ("MM-15", e.g. "06-15"): one row
     * per month holds that month's mean, expanded to per-day values by interpolation
     * when read.
     */
    val monthDay: String,
    /** Rounded lat/lon key, e.g. "37.4_-122.1". */
    val locationKey: String,
    val highTemp: Float,
    val lowTemp: Float,
    val fetchedAt: Long = System.currentTimeMillis(),
)

@Dao
interface ClimateNormalDao {
    @Query("SELECT * FROM climate_normals WHERE locationKey = :locationKey")
    suspend fun getNormalsForLocation(locationKey: String): List<ClimateNormalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(normals: List<ClimateNormalEntity>)

    @Query("DELETE FROM climate_normals WHERE locationKey != :currentLocationKey")
    suspend fun deleteOtherLocations(currentLocationKey: String)
}

package com.weatherwidget.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "current_temp",
    primaryKeys = ["date", "source"],
    indices = [Index(value = ["locationLat", "locationLon"])],
)
data class CurrentTempEntity(
    val date: String,
    val source: String,
    val locationLat: Double,
    val locationLon: Double,
    val temperature: Float,
    val observedAt: Long,
    val condition: String?,
    val fetchedAt: Long = System.currentTimeMillis(),
)

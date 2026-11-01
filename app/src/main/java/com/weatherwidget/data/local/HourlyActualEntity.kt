package com.weatherwidget.data.local

import androidx.room.Entity
import com.weatherwidget.data.model.HourlyActual

/** One archived blended hour ([HourlyActual]). Desktop declares the same table. */
@Entity(
    tableName = "hourly_actuals",
    primaryKeys = ["hourMs", "source", "locationLat", "locationLon"],
)
data class HourlyActualEntity(
    val hourMs: Long,
    val source: String,
    val locationLat: Double,
    val locationLon: Double,
    val temperature: Float? = null,
    val precipAmountMm: Float? = null,
    val cloudCover: Int? = null,
    val cloudCoverLow: Int? = null,
    val cloudCoverMid: Int? = null,
    val cloudCoverHigh: Int? = null,
    val archivedAt: Long,
)

fun HourlyActualEntity.toHourlyActual() = HourlyActual(
    hourMs, source, locationLat, locationLon, temperature, precipAmountMm,
    cloudCover, cloudCoverLow, cloudCoverMid, cloudCoverHigh, archivedAt,
)

fun HourlyActual.toEntity() = HourlyActualEntity(
    hourMs, source, locationLat, locationLon, temperature, precipAmountMm,
    cloudCover, cloudCoverLow, cloudCoverMid, cloudCoverHigh, archivedAt,
)

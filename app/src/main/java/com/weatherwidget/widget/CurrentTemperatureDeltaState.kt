package com.weatherwidget.widget

data class CurrentTemperatureDeltaState(
    val delta: Float,
    val lastObservedTemp: Float,
    val lastObservedFetchedAt: Long,
    val updatedAtMs: Long,
    val sourceId: String,
    val locationLat: Double,
    val locationLon: Double,
)

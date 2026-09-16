package com.weatherwidget.data.model

import kotlinx.serialization.Serializable

@Serializable
data class RecentLocation(
    val lat: Double,
    val lon: Double,
    val label: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toResolvedLocation(): ResolvedLocation =
        ResolvedLocation(
            lat = lat,
            lon = lon,
            label = label,
            source = "recent",
        )
}

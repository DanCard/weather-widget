package com.weatherwidget.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class RecentLocation(
    val lat: Double,
    val lon: Double,
    val label: String,
    // Always written: with encodeDefaults = false a timestamp equal to "now" at encode time (created
    // and saved in the same millisecond) was dropped, and decoding stamped the read time instead.
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
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

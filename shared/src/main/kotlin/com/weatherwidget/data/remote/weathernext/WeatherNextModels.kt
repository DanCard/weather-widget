package com.weatherwidget.data.remote.weathernext

import kotlinx.serialization.Serializable

@Serializable
data class WeatherNextVariable(
    val id: String,
    val label: String,
    val units: String? = null,
)

@Serializable
data class WeatherNextFrame(
    val lead: Int,
    val valid: String,
    val files: Map<String, String> = emptyMap(),
    val init: String? = null,
)

@Serializable
data class WeatherNextManifest(
    val run: String? = null,
    val init: String? = null,
    val source: String? = null,
    val variables: List<WeatherNextVariable> = emptyList(),
    val frames: List<WeatherNextFrame> = emptyList(),
)

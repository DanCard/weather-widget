package com.weatherwidget.widget

// Extracted from TemperatureGraphRenderer.kt
// A data class to hold candidates for temperature label placement

data class TempLabelCandidate(
    val index: Int,
    val role: TemperatureRole,
    val labelTemps: List<Float>,
    val rawTemperature: Float,
    val forceForecastSeries: Boolean
)


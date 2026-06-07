package com.weatherwidget.shared.graph

data class TempLabelCandidate(
    val index: Int,
    val role: TemperatureRole,
    val labelTemps: List<Float>,
    val rawTemperature: Float,
    val forceForecastSeries: Boolean
)

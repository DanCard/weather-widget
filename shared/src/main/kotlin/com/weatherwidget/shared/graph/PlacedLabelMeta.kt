package com.weatherwidget.shared.graph

data class PlacedLabelMeta(
    val bounds: GraphRect,
    val isValleyBelow: Boolean,
    val role: TemperatureRole,
    val temperature: Float,
)

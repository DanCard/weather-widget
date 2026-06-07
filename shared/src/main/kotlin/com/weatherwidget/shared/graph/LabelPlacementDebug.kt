package com.weatherwidget.shared.graph

data class LabelPlacementDebug(
    val index: Int,
    val role: TemperatureRole,
    val temperature: Float,
    val rawTemperature: Float,
    val x: Float,
    val y: Float,
    val placedAbove: Boolean,
    val series: String = "",
    val colorFamily: String = "",
    val hexColor: String = "",
    val reason: String = "",
    val displacementSteps: Int = 0,
)

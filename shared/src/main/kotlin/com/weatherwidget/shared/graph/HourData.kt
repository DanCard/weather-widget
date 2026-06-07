package com.weatherwidget.shared.graph

import java.time.LocalDateTime

data class HourData(
    val dateTime: LocalDateTime,
    val temperature: Float,          // Forecast temperature (drives the dashed forecast line)
    val label: String, // "12a", "1p", "2p"
    val iconRes: Int? = null,
    val isNight: Boolean = false,
    val isTwilight: Boolean = false,
    val isSunBoundary: Boolean = false,
    val isSunny: Boolean = false,
    val isRainy: Boolean = false,
    val isMixed: Boolean = false,
    val isCurrentHour: Boolean = false,
    val showLabel: Boolean = true, // Only at intervals
    val isActual: Boolean = false,           // True when actualTemperature is available
    val actualTemperature: Float? = null,    // Observed actual temp (past hours only)
    val isObservedActual: Boolean = false,   // Backed by a real blended/observed point, not carry-forward filler
)

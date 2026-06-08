package com.weatherwidget.data.model

data class ResolvedLocation(
    val lat: Double,
    val lon: Double,
    val label: String,
    val source: String,
    val detail: String? = null,
    val isFresh: Boolean = true,
)

package com.weatherwidget.shared.graph

interface LabelTextMetrics {
    fun width(text: String, isFuture: Boolean): Float
    val ascent: Float
    val descent: Float
    val height: Float get() = descent - ascent
}

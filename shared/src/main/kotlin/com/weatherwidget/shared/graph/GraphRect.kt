package com.weatherwidget.shared.graph

data class GraphRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersects(other: GraphRect): Boolean {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    companion object {
        fun intersects(a: GraphRect, b: GraphRect): Boolean = a.intersects(b)
    }
}

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

    fun inflate(padding: Float): GraphRect = GraphRect(
        left = left - padding,
        top = top - padding,
        right = right + padding,
        bottom = bottom + padding,
    )

    fun intersectionArea(other: GraphRect): Float {
        val intersectLeft = maxOf(left, other.left)
        val intersectTop = maxOf(top, other.top)
        val intersectRight = minOf(right, other.right)
        val intersectBottom = minOf(bottom, other.bottom)
        return if (intersectLeft < intersectRight && intersectTop < intersectBottom) {
            (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        } else {
            0f
        }
    }

    companion object {
        val Zero = GraphRect(0f, 0f, 0f, 0f)
        fun intersects(a: GraphRect, b: GraphRect): Boolean = a.intersects(b)
    }
}

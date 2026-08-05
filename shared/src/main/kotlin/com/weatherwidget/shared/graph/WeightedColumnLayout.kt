package com.weatherwidget.shared.graph

/** Pure weighted horizontal topology shared by Android Canvas and desktop Compose. */
data class WeightedColumnLayout(
    val lefts: List<Float>,
    val widths: List<Float>,
    val centers: List<Float>,
    val normalWidth: Float,
) {
    fun indexAt(x: Float): Int {
        if (widths.isEmpty()) return 0
        val matched = lefts.indices.firstOrNull { index -> x < lefts[index] + widths[index] }
        return (matched ?: widths.lastIndex).coerceAtLeast(0)
    }

    companion object {
        fun resolve(
            totalWidth: Float,
            columnCount: Int,
            todayColumnIndex: Int?,
            widenToday: Boolean,
            leftInset: Float = 0f,
            rightInset: Float = 0f,
        ): WeightedColumnLayout {
            if (columnCount <= 0) return WeightedColumnLayout(emptyList(), emptyList(), emptyList(), 0f)
            val todayIsWeighted = widenToday && todayColumnIndex in 0 until columnCount
            val weightedUnits =
                columnCount.toFloat() +
                    if (todayIsWeighted) LargeTodayOverlayPolicy.TODAY_WIDTH_MULTIPLIER - 1f else 0f
            val usableWidth = (totalWidth - leftInset - rightInset).coerceAtLeast(0f)
            val normalWidth = usableWidth / weightedUnits.coerceAtLeast(1f)
            val widths =
                (0 until columnCount).map { index ->
                    normalWidth *
                        if (todayIsWeighted && index == todayColumnIndex) {
                            LargeTodayOverlayPolicy.TODAY_WIDTH_MULTIPLIER
                        } else {
                            1f
                        }
                }
            val lefts = buildList {
                var left = leftInset
                widths.forEach { width ->
                    add(left)
                    left += width
                }
            }
            return WeightedColumnLayout(
                lefts = lefts,
                widths = widths,
                centers = lefts.indices.map { index -> lefts[index] + widths[index] / 2f },
                normalWidth = normalWidth,
            )
        }
    }
}

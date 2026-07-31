package com.weatherwidget.widget

import com.weatherwidget.data.remote.NwsTemperaturePlausibility.isPlausibleF
import com.weatherwidget.widget.DailyForecastGraphRenderer.DayData
import java.time.LocalDate

/**
 * Establishes the renderer boundary once: every downstream collaborator receives finite,
 * physically plausible temperatures and one unambiguous resolved column per day.
 */
internal object DailyGraphInputNormalizer {
    internal data class RejectedTemperature(
        val date: LocalDate,
        val field: String,
        val value: Float,
    )

    internal data class ColumnClamp(
        val date: LocalDate,
        val requestedColumn: Int,
        val resolvedColumn: Int,
    )

    internal data class ColumnCollision(
        val retainedDate: LocalDate,
        val skippedDate: LocalDate,
        val resolvedColumn: Int,
    )

    internal data class NormalizedDay(
        val day: DayData,
        val resolvedColumn: Int,
    )

    internal data class Result(
        val days: List<NormalizedDay>,
        val rejectedTemperatures: List<RejectedTemperature>,
        val columnClamps: List<ColumnClamp>,
        val columnCollisions: List<ColumnCollision>,
    )

    internal fun normalize(
        days: List<DayData>,
        columns: Int,
    ): Result {
        require(columns > 0) { "columns must be positive: $columns" }

        val rejected = mutableListOf<RejectedTemperature>()
        val clamps = mutableListOf<ColumnClamp>()
        val collisions = mutableListOf<ColumnCollision>()
        val occupied = mutableMapOf<Int, NormalizedDay>()
        val normalized = ArrayList<NormalizedDay>(days.size)

        days.forEachIndexed { index, original ->
            fun usable(field: String, value: Float?): Float? {
                if (value == null || isPlausibleF(value)) return value
                rejected += RejectedTemperature(original.date, field, value)
                return null
            }

            val rawColumn = original.columnIndex ?: index
            val resolvedColumn = rawColumn.coerceIn(0, columns - 1)
            if (rawColumn != resolvedColumn) {
                clamps += ColumnClamp(original.date, rawColumn, resolvedColumn)
            }

            val day = original.copy(
                solidLineHigh = usable("solidLineHigh", original.solidLineHigh),
                solidLineLow = usable("solidLineLow", original.solidLineLow),
                bottomStackLow = usable("bottomStackLow", original.bottomStackLow),
                dashedLineHigh = usable("dashedLineHigh", original.dashedLineHigh),
                dashedLineLow = usable("dashedLineLow", original.dashedLineLow),
                snapshotHigh = usable("snapshotHigh", original.snapshotHigh),
                snapshotLow = usable("snapshotLow", original.snapshotLow),
                ghostLineHigh = usable("ghostLineHigh", original.ghostLineHigh),
                columnIndex = resolvedColumn,
            )
            val normalizedDay = NormalizedDay(day, resolvedColumn)
            val retained = occupied.putIfAbsent(resolvedColumn, normalizedDay)
            if (retained != null) {
                collisions += ColumnCollision(
                    retainedDate = retained.day.date,
                    skippedDate = day.date,
                    resolvedColumn = resolvedColumn,
                )
            } else {
                normalized += normalizedDay
            }
        }

        return Result(
            days = normalized,
            rejectedTemperatures = rejected,
            columnClamps = clamps,
            columnCollisions = collisions,
        )
    }
}

package com.weatherwidget.widget

import android.graphics.RectF

enum class TemperatureGraphObstacleType {
    ICON,
    TEMPERATURE_LABEL,
    DAY_LABEL,
    FETCH_DOT_RING,
    FETCH_DOT_VALUE,
    FETCH_DOT_AGE,
    FETCH_DOT_AGE_RESERVATION,
    FORECAST_DELTA,
    GHOST_LABEL,
}

data class TemperatureGraphObstacle(
    val type: TemperatureGraphObstacleType,
    val bounds: RectF,
)

/**
 * Render-scoped collision state.
 *
 * Callers receive copied rectangles so a component cannot accidentally mutate another component's
 * obstacle. Provisional reservations have their own type and are explicitly removed or replaced
 * before final drawing.
 */
class TemperatureGraphObstacleRegistry {
    private val obstacles = mutableListOf<TemperatureGraphObstacle>()

    fun add(
        type: TemperatureGraphObstacleType,
        bounds: RectF,
    ) {
        obstacles += TemperatureGraphObstacle(type, RectF(bounds))
    }

    fun addAll(
        type: TemperatureGraphObstacleType,
        bounds: Iterable<RectF>,
    ) {
        bounds.forEach { add(type, it) }
    }

    fun remove(type: TemperatureGraphObstacleType) {
        obstacles.removeAll { it.type == type }
    }

    fun replace(
        removedType: TemperatureGraphObstacleType,
        finalType: TemperatureGraphObstacleType,
        bounds: RectF?,
    ) {
        remove(removedType)
        if (bounds != null) {
            add(finalType, bounds)
        }
    }

    fun bounds(
        excluding: Set<TemperatureGraphObstacleType> = emptySet(),
    ): List<RectF> =
        obstacles
            .asSequence()
            .filterNot { it.type in excluding }
            .map { RectF(it.bounds) }
            .toList()

    fun snapshot(): List<TemperatureGraphObstacle> =
        obstacles.map { TemperatureGraphObstacle(it.type, RectF(it.bounds)) }
}

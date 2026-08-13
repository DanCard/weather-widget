package com.weatherwidget.shared.graph

import java.time.LocalDateTime

data class ResolvedLabelGeometry(
    val index: Int,
    val role: TemperatureRole,
    val rawTemperature: Float,
    val displayTemperature: Float,
    val label: String,
    val isFuture: Boolean,
    val isValley: Boolean,
    val isEssential: Boolean,
    val sx: Float,
    val sy: Float,
    val textWidth: Float,
    val clampedX: Float,
)

/**
 * Public facade for the temperature-label pipeline: extremum computation, candidate collection,
 * candidate sorting, and per-candidate geometry resolution. The heavy lifting lives in
 * [LabelCandidateCollector], [LabelSuppression], and [LabelGeometryResolver]; this object keeps the
 * shared role classification and the stable public entry points the engine and renderers call.
 */
object TemperatureLabelResolver {
    private const val MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES = 2.5f

    val ESSENTIAL_LABEL_ROLES: Set<TemperatureRole> = setOf(
        TemperatureRole.LOW,
        TemperatureRole.HIGH,
        TemperatureRole.FORECAST_LOW,
        TemperatureRole.FORECAST_HIGH,
        TemperatureRole.ACTUAL_LOW,
        TemperatureRole.ACTUAL_HIGH,
        TemperatureRole.PAST_FORECAST_LOW,
        TemperatureRole.PAST_FORECAST_HIGH,
        TemperatureRole.LOCAL,
        TemperatureRole.START,
        TemperatureRole.END,
        TemperatureRole.ACTUAL_END,
    )

    fun formatTemp(value: Float, useCelsius: Boolean): String {
        val displayVal = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(value) else value
        val rounded = kotlin.math.round(displayVal * 10f) / 10f
        return if (rounded % 1f == 0f) {
            "%.0f".format(rounded)
        } else {
            "%.1f".format(rounded)
        }
    }

    // Shared role classification used by the candidate collector and the suppression passes.

    // Roles whose label shows the OBSERVED (actual) value rather than the forecast value.
    internal val ACTUAL_DISPLAY_ROLES = setOf(
        TemperatureRole.ACTUAL_HIGH, TemperatureRole.ACTUAL_LOW, TemperatureRole.ACTUAL_END,
    )

    // Roles whose label text is drawn from the forecast series (labelTemps), used to detect a
    // synthetic midpoint that merely repeats a value already shown on the forecast line. Excludes the
    // ACTUAL_* roles, which read actualLabelTemps (a different, differently-colored series).
    internal val FORECAST_VALUE_ROLES = setOf(
        TemperatureRole.HIGH, TemperatureRole.LOW,
        TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW,
        TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
        TemperatureRole.START, TemperatureRole.END, TemperatureRole.LOCAL,
    )

    internal val FORECAST_HIGH_ROLES = setOf(
        TemperatureRole.HIGH, TemperatureRole.FORECAST_HIGH, TemperatureRole.PAST_FORECAST_HIGH,
    )
    internal val FORECAST_LOW_ROLES = setOf(
        TemperatureRole.LOW, TemperatureRole.FORECAST_LOW, TemperatureRole.PAST_FORECAST_LOW,
    )

    internal fun resolveExtremaRole(
        idx: Int,
        extrema: TemperatureExtrema.ExtremaIndices,
        hours: List<HourData>,
    ): TemperatureRole = when (idx) {
        extrema.dailyHighIndex -> TemperatureRole.HIGH
        extrema.dailyLowIndex -> TemperatureRole.LOW
        // Actual extrema win over the START/END boundary roles: when the observed high/low lands on
        // the right-edge (NOW) or left-edge index, the label must show the observed value rather than
        // the forecast endpoint. Kept BELOW HIGH/LOW so forecast global extrema still drive the
        // dual-label injection in addCoincidentActuals.
        // The global observed high is a real reading when it is the graph's left boundary. Per-day
        // extrema still require an interior turning point, but that headline actual high must be
        // available as a label candidate.
        extrema.actualHighIndex.takeIf { it == 0 } -> TemperatureRole.ACTUAL_HIGH
        extrema.actualLowIndex.takeIf { it == extrema.actualEndIndex } -> TemperatureRole.ACTUAL_LOW
        in extrema.actualDailyHighIndices -> TemperatureRole.ACTUAL_HIGH
        in extrema.actualDailyLowIndices -> TemperatureRole.ACTUAL_LOW
        0 -> TemperatureRole.START
        hours.lastIndex -> TemperatureRole.END
        extrema.forecastHighIndex -> TemperatureRole.FORECAST_HIGH
        extrema.forecastLowIndex -> TemperatureRole.FORECAST_LOW
        extrema.pastForecastHighIndex -> TemperatureRole.PAST_FORECAST_HIGH
        extrema.pastForecastLowIndex -> TemperatureRole.PAST_FORECAST_LOW
        extrema.actualEndIndex -> TemperatureRole.ACTUAL_END
        else -> TemperatureRole.LOCAL
    }

    fun computeExtremaIndices(
        hours: List<HourData>,
        transitionX: Float?,
        effectiveActualEndIndex: Int,
        fetchTime: LocalDateTime?,
        useCelsius: Boolean,
    ): TemperatureExtrema.ExtremaIndices {
        val prominenceThreshold = when {
            hours.size <= 10 -> MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES
            hours.size <= 24 -> 1.5f // Narrow zoom: more detail, but still reject minor noise
            else -> MIN_LOCAL_EXTREMA_PROMINENCE_DEGREES
        }
        return TemperatureExtrema.compute(hours, transitionX, effectiveActualEndIndex, fetchTime, prominenceThreshold, useCelsius)
    }

    fun collectLabelCandidates(
        hours: List<HourData>,
        extrema: TemperatureExtrema.ExtremaIndices,
        effectiveActualEndIndex: Int,
        transitionX: Float?,
        observedAt: Long?,
        numColumns: Int = 0,
        widthPx: Int = 0,
        useCelsius: Boolean,
    ): List<TempLabelCandidate> =
        LabelCandidateCollector.collect(
            hours = hours,
            extrema = extrema,
            effectiveActualEndIndex = effectiveActualEndIndex,
            transitionX = transitionX,
            observedAt = observedAt,
            numColumns = numColumns,
            widthPx = widthPx,
            useCelsius = useCelsius,
        )

    fun sortLabelCandidates(candidates: MutableList<TempLabelCandidate>) =
        LabelGeometryResolver.sortCandidates(candidates)

    fun resolveCandidateGeometry(
        candidate: TempLabelCandidate,
        originalPoints: List<Pair<Float, Float>>,
        forecastPoints: List<Pair<Float, Float>>,
        transitionX: Float?,
        widthPx: Int,
        density: Float,
        fetchDotX: Float?,
        lastObservedTemp: Float?,
        tempToY: (Float) -> Float,
        metrics: LabelTextMetrics,
        useCelsius: Boolean,
    ): ResolvedLabelGeometry? =
        LabelGeometryResolver.resolve(
            candidate = candidate,
            originalPoints = originalPoints,
            forecastPoints = forecastPoints,
            transitionX = transitionX,
            widthPx = widthPx,
            density = density,
            fetchDotX = fetchDotX,
            lastObservedTemp = lastObservedTemp,
            tempToY = tempToY,
            metrics = metrics,
            useCelsius = useCelsius,
        )
}

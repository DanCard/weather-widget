package com.weatherwidget.widget

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.widget.handlers.CloudCoverDiagnosticRow
import com.weatherwidget.widget.handlers.HeaderConstants
import java.time.LocalDate

object DailyForecastGraphRenderer {
    private const val TAG = "DailyGraphRenderer"

    private inline fun debug(msg: () -> String) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, msg())
        }
    }

    private const val HEADER_RAIN_OVERLAP_TOLERANCE_DP = 4f
    // Edge margin (dp) used in three places: triple-bar spacing, today-panel horizontal padding,
    // and header-rain overlap detection. Named once so the three stay in sync.
    private const val COLUMN_EDGE_MARGIN_DP = 2f

    // Today-column emphasis (frosted-glass panel + touching triple bars) is shared with desktop via
    // com.weatherwidget.shared.graph.TodayColumnHighlight — see it for the geometry/styling constants.

    /**
     * Fired once for each bar drawn, for testing and debugging.
     */
    data class BarDrawnDebug(
        val date: LocalDate,
        val barType: String,
        val highY: Float,
        val lowY: Float,
        val centerX: Float,
        val color: Int,
        val adaptiveSegments: Boolean = false,
    )

    enum class RainLabelKind {
        DAY,
        NIGHT,
    }

    data class DailyRainLabelPlacement(
        val date: LocalDate,
        val text: String,
        val placement: String,
        val kind: RainLabelKind,
        val centerX: Float,
        val leftX: Float = Float.NaN,
        val rightX: Float = Float.NaN,
        val baselineY: Float,
        val topY: Float = Float.NaN,
        val bottomY: Float = Float.NaN,
        val anchorTopY: Float = Float.NaN,
        val anchorBaselineY: Float = Float.NaN,
    )

    data class DailyGraphRenderResult(
        val bitmap: Bitmap,
        val rainLabelPlacements: List<DailyRainLabelPlacement>,
        val todayOverlayPlacements: List<TodayOverlayPlacementDebug> = emptyList(),
    )

    data class TodayOverlayRenderData(
        val deltaValueText: String? = null,
        val deltaCaptionText: String? = null,
        val deltaColorArgb: Int = 0xE6FFFFFF.toInt(),
        val dominantTempText: String? = null,
        val dominantAgeText: String? = null,
        /**
         * Zone each overlay block occupied on this widget's previous render, keyed by block key.
         * Feeds the planner's hysteresis: the obstacles it avoids are labels that shift as
         * temperatures change, and without this a sub-pixel move can migrate a block between zones
         * between renders. Empty on the first render of a widget.
         */
        val previousZones: Map<String, com.weatherwidget.shared.graph.TodayColumnOverlayPlanner.Zone> =
            emptyMap(),
    )

    data class TodayOverlayPlacementDebug(
        val key: String,
        val text: String,
        val zone: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val mainTextSizePx: Float,
        val fromLastResort: Boolean = false,
    )

    data class HeaderDrawnDebug(
        val dateText: String?,
        val dateSuppressedForRainOverlap: Boolean,
    )

    data class DayLabelDrawnDebug(
        val date: LocalDate,
        val text: String,
        val centerX: Float,
        val baselineY: Float,
        val leftX: Float,
        val rightX: Float,
        val textSize: Float,
    )

    /** Renderer-owned precipitation inputs: visible label text and its sizing probability. */
    data class RainLabelData(
        /** Daytime precipitation probability, 0–100 (percentage). Divided by 100 internally for font scaling. */
        val dailyPrecipProbability: Int? = null,
        /** Nighttime precipitation probability, 0–100 (percentage). Divided by 100 internally for font scaling. */
        val nighttimePrecipProbability: Int? = null,
        val dailyRainLabelText: String? = null,
        val nightRainLabelText: String? = null,
    )

    data class HeaderRenderData(
        val iconRes: Int? = null,
        val currentTempText: String? = null,
        val deltaText: String? = null,
        val deltaColor: Int = 0xFFFF6B35.toInt(),
        /** "from yest" caption candidate after the delta; drawn only when it fits. */
        val deltaLabelText: String? = null,
        val precipText: String? = null,
        val precipColor: Int = 0xFF5AC8FA.toInt(),
        val precipTextSizeDp: Float = HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP,
        val dateText: String? = null,
        val apiSourceText: String? = null,
        val apiTextSizeDp: Float = 12.6f,
        val settingsIconRes: Int = 0,
        val showIcon: Boolean = true,
        val showDelta: Boolean = true,
        val showPrecip: Boolean = true,
        /** Scale applied to header icons and fonts on wide widgets (1.0 = normal, 1.35 = wide). */
        val headerScale: Float = 1f,
        /**
         * Width in **unscaled dp** of the centred RemoteViews icon pair (observations / forecast
         * history) overlaid on the daily header, or 0f when those icons are absent or placed
         * inline in the left cluster. Raw dp like every other metric here — the renderer applies
         * its own `labelScale`. Non-zero reserves the centre so the painted date steps aside
         * instead of drawing under them — see [DailyForecastHeaderRenderer.resolveDateDrawX].
         */
        val centerIconsWidthDp: Float = 0f,
        /**
         * When the date and the "from yest" caption cannot both fit, which one survives.
         * Alternates per render so neither is permanently starved; the default `true` reproduces
         * the historical fixed priority (date wins, caption dropped).
         * See [DailyForecastHeaderRenderer.resolveHeaderContention].
         */
        val preferDateOverLabel: Boolean = true,
    )

    data class DayData(
        override val date: LocalDate,
        val label: String,
        val solidLineHigh: Float?,
        val solidLineLow: Float?,
        val bottomStackLow: Float? = null,
        val iconRes: Int? = null,
        val isSunny: Boolean = false,
        val isRainy: Boolean = false,
        val isMixed: Boolean = false,
        val isToday: Boolean = false,
        val isPast: Boolean = false,
        val isClimateNormal: Boolean = false,
        val isSourceGapFallback: Boolean = false,
        val dashedLineHigh: Float? = null,
        val dashedLineLow: Float? = null,
        val rainData: RainLabelData = RainLabelData(),
        val columnIndex: Int? = null,
        val isTodayForecastFallback: Boolean = false,
        val snapshotHigh: Float? = null,
        val snapshotLow: Float? = null,
        val snapshotIconRes: Int? = null,
        val ghostLineHigh: Float? = null,
        override val cloudCoverRatioOverride: Float? = null,
        override val daysFromToday: Int = 0,
        /** Local hour-of-day (0–23) for the today column's actual-tracking cutoffs; null = legacy. */
        val nowHour: Int? = null,
    ) : CloudCoverDiagnosticRow

    fun renderGraph(
        context: Context,
        days: List<DayData>,
        widthPx: Int,
        heightPx: Int,
        bitmapScale: Float = 1f,
        numColumns: Int = 0,
        job: Job? = null,
        onBarDrawn: ((BarDrawnDebug) -> Unit)? = null,
        onRainLabelDrawn: ((DailyRainLabelPlacement) -> Unit)? = null,
        onDayLabelDrawn: ((DayLabelDrawnDebug) -> Unit)? = null,
        headerData: HeaderRenderData? = null,
        showErrorWatermark: Boolean = false,
        errorSourceLabel: String? = null,
        errorCode: String? = null,
        errorFailureTimeMs: Long? = null,
        onHeaderDrawn: ((HeaderDrawnDebug) -> Unit)? = null,
        useLargeTodayOverlay: Boolean = false,
        todayOverlayData: TodayOverlayRenderData? = null,
        useCelsius: Boolean,
    ): DailyGraphRenderResult {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (days.isEmpty()) {
            Log.w(TAG, "renderGraph: empty days list, returning blank bitmap (${widthPx}x${heightPx})")
            if (showErrorWatermark) {
                val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
                GraphFailureWatermarkRenderer.draw(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
            }
            return DailyGraphRenderResult(bitmap, emptyList())
        }

        val columns = if (numColumns > 0) numColumns else days.size
        val normalized = DailyGraphInputNormalizer.normalize(days, columns)
        val normalizedDays = normalized.days.map { it.day }
        normalized.rejectedTemperatures.firstOrNull()?.let { first ->
            Log.w(
                TAG,
                "renderGraph: rejected ${normalized.rejectedTemperatures.size} invalid temperature(s)" +
                    " firstDate=${first.date} firstField=${first.field} firstValue=${first.value}",
            )
        }
        normalized.columnClamps.firstOrNull()?.let { first ->
            Log.w(
                TAG,
                "renderGraph: clamped ${normalized.columnClamps.size} column index(es)" +
                    " firstDate=${first.date} requested=${first.requestedColumn} resolved=${first.resolvedColumn}" +
                    " columns=$columns",
            )
        }
        normalized.columnCollisions.firstOrNull()?.let { first ->
            Log.w(
                TAG,
                "renderGraph: skipped ${normalized.columnCollisions.size} duplicate resolved column(s)" +
                    " firstColumn=${first.resolvedColumn} retainedDate=${first.retainedDate}" +
                    " skippedDate=${first.skippedDate}",
            )
        }
        val layout =
            DailyGraphLayoutResolver.resolve(
                days = normalizedDays,
                widthPx = widthPx,
                heightPx = heightPx,
                columns = columns,
                bitmapScale = bitmapScale,
                density = context.resources.displayMetrics.density,
                useCelsius = useCelsius,
                todayColumnIndex =
                    normalized.days.firstOrNull { it.day.isToday }?.resolvedColumn,
                useLargeTodayOverlay = useLargeTodayOverlay,
            )
        job?.ensureActive()
        val paints = DailyGraphPaintCache.get(layout)

        debug { "renderGraph: days=${normalizedDays.size}, minTemp=${layout.minTemp}, maxTemp=${layout.maxTemp}, widthPx=$widthPx, heightPx=$heightPx" }

        val daysByColumn = normalized.days.associate { it.resolvedColumn to it.day }

        // Per-day cache of the daytime rain-label geometry populated by the typed placement
        // observer. Header collision checks reuse exactly what was drawn rather than measuring the
        // text and resolving placement a second time.
        val rainLabelPlacements = mutableListOf<DailyRainLabelPlacement>()
        val drawnDailyRainLabelByDate = mutableMapOf<java.time.LocalDate, DailyRainLabelPlacement>()
        val collectAndEmitRainLabel: (DailyRainLabelPlacement) -> Unit = { placement ->
            rainLabelPlacements += placement
            if (placement.kind == RainLabelKind.DAY) {
                drawnDailyRainLabelByDate[placement.date] = placement
            }
            onRainLabelDrawn?.invoke(placement)
        }
        val todayBars = mutableListOf<BarDrawnDebug>()
        val todayHighLabelBounds = mutableListOf<RectF>()
        var todayColumnBounds: DailyColumnRenderer.DrawnBounds? = null

        normalized.days.forEach { normalizedDay ->
            job?.ensureActive()
            val day = normalizedDay.day
            val columnIndex = normalizedDay.resolvedColumn
            val centerX = layout.columnCenter(columnIndex)
            val rightNeighbor = daysByColumn[columnIndex + 1]

            // Frosted-glass focal panel BEHIND today's triple-bar column (drawn before the bars so it
            // sits underneath them and their labels).
            if (day.isToday) {
                DailyBarRenderer.drawTodayHighlightPanel(canvas, centerX, layout, paints)
            }

            // Bars first, then column content (weather icon, low/day labels) so the icon
            // and labels render on top of any bar geometry that might overlap them.
            val highLabelBounds =
                DailyBarRenderer.drawDayBars(
                    canvas,
                    day,
                    centerX,
                    layout,
                    paints,
                ) { drawn ->
                    if (day.isToday) todayBars += drawn
                    onBarDrawn?.invoke(drawn)
                }
            if (day.isToday) todayHighLabelBounds += highLabelBounds
            val drawnColumnBounds = DailyColumnRenderer.draw(
                canvas,
                context,
                day,
                rightNeighbor,
                centerX,
                layout,
                paints,
                collectAndEmitRainLabel,
                onDayLabelDrawn,
            )
            if (day.isToday) todayColumnBounds = drawnColumnBounds
        }

        val todayOverlayPlacements =
            if (useLargeTodayOverlay && todayOverlayData != null) {
                val todayDay = normalizedDays.firstOrNull { it.isToday }
                val todayIndex = layout.todayColumnIndex
                val columnBounds = todayColumnBounds
                if (todayDay != null && todayIndex != null && columnBounds != null) {
                    TodayColumnOverlayRenderer.draw(
                        canvas = canvas,
                        data = todayOverlayData,
                        layout = layout,
                        todayColumnIndex = todayIndex,
                        todayBars = todayBars,
                        highLabelBounds = todayHighLabelBounds,
                        columnBounds = columnBounds,
                        rainPlacements = rainLabelPlacements.filter { it.date == todayDay.date },
                        // How far the header intrudes over THIS column specifically — measured from
                        // the pre-suppression header on purpose: the date may still be dropped
                        // below, and an item reported that is not drawn only pushes the overlay down.
                        headerInkBottom = headerData?.let {
                            DailyForecastHeaderRenderer.resolveHeaderInkBottom(
                                header = it,
                                widthPx = widthPx,
                                layout = layout,
                                xLeft = layout.columnLefts[todayIndex],
                                xRight = layout.columnLefts[todayIndex] + layout.columnWidth(todayIndex),
                            )
                        },
                    )
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

        val finalHeaderData = headerData?.let {
            suppressHeaderDateForRainOverlap(
                it,
                normalized.days,
                layout,
                paints,
                widthPx,
                drawnDailyRainLabelByDate,
            )
        }
        if (finalHeaderData != null) {
            DailyForecastHeaderRenderer.drawHeader(canvas, context, finalHeaderData, widthPx, layout)
            onHeaderDrawn?.invoke(
                HeaderDrawnDebug(
                    dateText = finalHeaderData.dateText,
                    dateSuppressedForRainOverlap = !headerData.dateText.isNullOrBlank() && finalHeaderData.dateText == null,
                ),
            )
        }

        if (showErrorWatermark) {
            val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
            GraphFailureWatermarkRenderer.draw(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
        }

        return DailyGraphRenderResult(
            bitmap,
            rainLabelPlacements.toList(),
            todayOverlayPlacements,
        )
    }

    private fun suppressHeaderDateForRainOverlap(
        headerData: HeaderRenderData,
        days: List<DailyGraphInputNormalizer.NormalizedDay>,
        layout: DailyGraphLayoutInfo,
        paints: DailyGraphPaintSet,
        widthPx: Int,
        // Daily rain labels actually drawn on the canvas, indexed by date. An empty map retains the
        // isolated-test fallback that resolves placement without a preceding draw pass.
        drawnDailyRainLabelByDate: Map<java.time.LocalDate, DailyRainLabelPlacement> = emptyMap(),
    ): HeaderRenderData {
        if (headerData.dateText.isNullOrBlank()) return headerData
        val padding = COLUMN_EDGE_MARGIN_DP.dp(layout.density)
        val dateBounds = DailyForecastHeaderRenderer.resolveHeaderDateBounds(
            header = headerData,
            widthPx = widthPx,
            layout = layout,
            extraPaddingPx = padding,
        ) ?: return headerData

        days.forEach { normalizedDay ->
            val day = normalizedDay.day
            val centerX = layout.columnCenter(normalizedDay.resolvedColumn)
            // Prefer the geometry that was actually drawn. Only re-resolve when an isolated caller
            // invokes this decision without a preceding draw pass.
            val rainLabel = drawnDailyRainLabelByDate[day.date]
                ?: DailyForecastRainLabelRenderer.resolveDailyRainLabelPlacement(
                    day = day,
                    centerX = centerX,
                    layout = layout,
                    paints = paints,
                )?.debug
                ?: return@forEach
            val rainBounds = RectF(rainLabel.leftX, rainLabel.topY, rainLabel.rightX, rainLabel.bottomY)
            if (hasMeaningfulHeaderRainOverlap(dateBounds, rainBounds, layout.density)) {
                Log.d(
                    TAG,
                    "suppressHeaderDateForRainOverlap: dateText=${headerData.dateText} rainDate=${day.date}" +
                        " rainText=${rainLabel.text} dateBounds=$dateBounds rainBounds=$rainBounds" +
                        " fromCache=${drawnDailyRainLabelByDate.containsKey(day.date)}",
                )
                return headerData.copy(dateText = null)
            }
        }
        return headerData
    }

    @VisibleForTesting
    internal fun hasMeaningfulHeaderRainOverlap(
        dateBounds: RectF,
        rainBounds: RectF,
        density: Float,
    ): Boolean {
        val overlapWidth = minOf(dateBounds.right, rainBounds.right) - maxOf(dateBounds.left, rainBounds.left)
        val overlapHeight = minOf(dateBounds.bottom, rainBounds.bottom) - maxOf(dateBounds.top, rainBounds.top)
        val tolerancePx = HEADER_RAIN_OVERLAP_TOLERANCE_DP.dp(density)
        return overlapWidth > tolerancePx && overlapHeight > tolerancePx
    }

    private fun Float.dp(density: Float): Float = this * density
}

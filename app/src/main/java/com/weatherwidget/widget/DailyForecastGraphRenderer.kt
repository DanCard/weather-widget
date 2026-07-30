package com.weatherwidget.widget

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.widget.handlers.CloudCoverDiagnosticRow
import com.weatherwidget.widget.handlers.HeaderConstants
import com.weatherwidget.data.remote.NwsTemperaturePlausibility.isPlausibleF
import com.weatherwidget.shared.graph.DualHighLabel
import com.weatherwidget.shared.graph.TodayColumnHighlight
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object DailyForecastGraphRenderer {
    private const val TAG = "DailyGraphRenderer"

    private inline fun debug(msg: () -> String) {
        Log.d(TAG, msg())
    }

    private const val DAY_LABEL_SIZE_MULTIPLIER = 1.15f
    private const val DAY_LABEL_TEXT_SCALE = 1.5f
    private const val BASE_DAY_WIDTH_DP = 70f
    private const val MIN_DAY_LABEL_WIDTH_SCALE = 0.96f
    private const val MAX_DAY_LABEL_WIDTH_SCALE = 1.04f
    private const val MIN_DYNAMIC_DAY_LABEL_SCALE = 0.72f
    private const val DAY_LABEL_HORIZONTAL_GAP_DP = 4f
    // Temp-label shrink-to-fit budget. Rather than enforce a gap, we ALLOW a small overlap into the
    // neighbouring column before shrinking, so labels stay as large as possible — a sliver of overlap
    // reads better than visibly smaller text. Only labels wider than (column + this allowance) shrink,
    // and only down to MIN_TEMP_LABEL_FIT_SCALE of their size (legibility floor).
    private const val TEMP_LABEL_OVERLAP_ALLOWANCE_DP = 6f
    private const val MIN_TEMP_LABEL_FIT_SCALE = 0.7f
    private const val MIN_BAR_HEIGHT_DP = 1.0f

    private val COLOR_LABEL_GRAY = 0xFFAAAAAA.toInt()
    private val COLOR_TODAY_TEXT = 0xFFFFEACC.toInt()
    private val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private val COLOR_SUNNY = 0xFFFFD60A.toInt()
    internal val HEADER_TEXT_COLOR = 0xAAFFFFFF.toInt()
    private const val RAIN_FONT_SCALE_K = 0.6f
    private const val RAIN_FONT_SCALE_MAX_DAYS = 7f
    private const val TEMP_LABEL_TEXT_SIZE_DP = 24f
    private const val TOP_PADDING_DP = 50f
    private const val ICON_BELOW_BAR_SPACING_DP = 3f
    private const val TEMP_LABEL_SPACING_DP = -1f
    private const val RAIN_TEXT_MARGIN_DP = 4f
    private const val RAIN_LABEL_EDGE_MARGIN_DP = 4f
    private const val ICON_STACK_SPACING_DP = 4f
    private const val DAY_LABEL_BASE_SIZE_DP = 17f
    private const val ICON_BASE_SIZE_DP = 36f
    private val RAIN_TEXT_SIZE_DP = HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP
    // Tiny margin in dp so day label baseline sits just inside bitmap bottom edge across densities.
    internal const val DAY_LABEL_BOTTOM_MARGIN_DP = 1f
    private const val PAST_TEMP_SCALE = 0.9f
    private const val LABEL_SHADOW_RADIUS_DP = 2.5f
    private const val LABEL_SHADOW_DY_DP = 1.0f
    // Thin black outline stroke (fraction of font size) for HISTORY temp labels only, so they stay
    // legible over same-colored bars. Much thinner than the reverted heavy outline; gated to past days.
    private const val LABEL_OUTLINE_STROKE_FRACTION = 0.12f
    private const val HEADER_RAIN_OVERLAP_TOLERANCE_DP = 4f
    // Edge margin (dp) used in three places: triple-bar spacing, today-panel horizontal padding,
    // and header-rain overlap detection. Named once so the three stay in sync.
    private const val COLUMN_EDGE_MARGIN_DP = 2f

    // Today-column emphasis (frosted-glass panel + touching triple bars) is shared with desktop via
    // com.weatherwidget.shared.graph.TodayColumnHighlight — see it for the geometry/styling constants.

    private data class PaintCache(
        val scaleFactor: Float,
        val dayLabelHeight: Float,
        val tempLabelHeight: Float,
        val set: PaintSet,
    )
    private const val PAINT_CACHE_LRU_SIZE = 3
    // @Volatile gives visibility but not atomicity for this read-modify-write. Two concurrent
    // renders can both miss the cache and both write — the loser's PaintSet is still returned to
    // its own caller (work duplicated, not lost), and the cache briefly carries one extra entry
    // before the LRU truncate. Acceptable: renders are short, concurrency is rare (resize during
    // fetch), and correctness doesn't depend on a single winner. Use a lock only if profiling
    // ever shows wasted Paint construction.
    @Volatile
    private var paintCaches: List<PaintCache> = emptyList()

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

    data class RainLabelDrawnDebug(
        val date: LocalDate,
        val text: String,
        val placement: String,
        val centerX: Float,
        val leftX: Float = Float.NaN,
        val rightX: Float = Float.NaN,
        val baselineY: Float,
        val topY: Float = Float.NaN,
        val bottomY: Float = Float.NaN,
        val anchorTopY: Float = Float.NaN,
        val anchorBaselineY: Float = Float.NaN,
        val isNightLabel: Boolean = false,
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

    /**
     * Groups all precipitation-related data for a day.
     * Used for rain labels, probability, and amount data.
     */
    data class RainData(
        val rainSummary: String? = null,
        /** Daytime precipitation probability, 0–100 (percentage). Divided by 100 internally for font scaling. */
        val dailyPrecipProbability: Int? = null,
        /** Nighttime precipitation probability, 0–100 (percentage). Divided by 100 internally for font scaling. */
        val nighttimePrecipProbability: Int? = null,
        val dailyPrecipAmountMm: Float? = null,
        val dailyRainLabelText: String? = null,
        val nightRainLabelText: String? = null,
        val hasRainForecast: Boolean = false,
    )

    data class HeaderRenderData(
        val iconRes: Int? = null,
        val currentTempText: String? = null,
        val deltaText: String? = null,
        val deltaColor: Int = 0xFFFF6B35.toInt(),
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
        val rainData: RainData = RainData(),
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

    data class LayoutInfo(
        val widthPx: Int,
        val heightPx: Int,
        val columns: Int,
        val minTemp: Float,
        val maxTemp: Float,
        val tempRange: Float,
        val scaleFactor: Float,
        val graphTop: Float,
        val graphBottom: Float,
        val graphHeight: Float,
        val dayWidth: Float,
        // Width budget for a single (centered) temp label before it overflows into the next column.
        val tempLabelMaxWidthPx: Float,
        val horizontalPadding: Float,
        val tripleBarOffset: Float,
        val forecastBarOffset: Float,
        val iconSize: Int,
        val dayLabelHeight: Float,
        val tempLabelHeight: Float,
        val bulbRadius: Float,
        val bitmapScale: Float,
        val minBarHeightPx: Float,
        val dayLabelTextByDate: Map<LocalDate, String>,
        val density: Float,
        val useCelsius: Boolean,
    ) {
        /**
         * Maps a temperature to its y position, clamped to the graph area.
         *
         * The clamp is an identity for every value that set the axis range — [minTemp]/[maxTemp] are
         * accumulated from the same seven fields every caller passes in, so an in-range temperature
         * always lands inside `[graphTop, graphBottom]` on its own. It bites only values that
         * computeLayout deliberately *excluded* from the range as implausible: without it, excluding
         * a -100 sentinel from the axis still leaves its own bar drawn at y≈1585 on a 400px canvas,
         * overdrawing the day labels and everything below it. Hardening the axis is only half the
         * job — a bad value has to stay inside its own cell too.
         */
        fun tempToY(temp: Float): Float =
            (graphTop + graphHeight * (1 - (temp - minTemp) / tempRange))
                .coerceIn(graphTop, graphBottom)
    }

    @VisibleForTesting
    data class DayLabelInput(
        val date: LocalDate,
        val label: String,
        val isToday: Boolean = false,
    )

    @VisibleForTesting
    data class DayLabelLayoutResult(
        val textSizePx: Float,
        val textByDate: Map<LocalDate, String>,
        val scale: Float,
        val shortenedLabels: Boolean,
    )

    @VisibleForTesting
    data class RainAboveHighPlacement(
        val baseline: Float,
        val top: Float,
        val bottom: Float,
        val highLabelTop: Float,
        val fits: Boolean,
    )

    @VisibleForTesting
    data class TextMetrics(
        val ascent: Float,
        val descent: Float,
    )

    internal class PaintSet(
        val barPaint: Paint,
        val todayObservedRedPaint: Paint,
        val todayObservedGhostPaint: Paint,
        val todayObservedRedBulbPaint: Paint,
        val todaySnapshotYellowPaint: Paint,
        val todayForecastBluePaint: Paint,
        val historyBarPaint: Paint,
        val forecastBarPaint: Paint,
        val climateOverlayBarPaint: Paint,
        val gapFallbackBarPaint: Paint,
        val textPaint: Paint,
        val todayTextPaint: Paint,
        val tempTextPaint: Paint,
        val pastTempTextPaint: Paint,
        val todayTempTextPaint: Paint,
        val rainTextPaint: Paint,
        val todayPanelFillPaint: Paint,
    ) {
        private val barByColor = ConcurrentHashMap<Int, Paint>()
        private val forecastByColor = ConcurrentHashMap<Int, Paint>()
        private val climateOverlayByColor = ConcurrentHashMap<Int, Paint>()
        private val todayForecastByColor = ConcurrentHashMap<Int, Paint>()

        fun barForColor(color: Int): Paint = barByColor.getOrPut(color) {
            Paint(barPaint).apply { this.color = color }
        }
        fun forecastForColor(color: Int): Paint = forecastByColor.getOrPut(color) {
            Paint(forecastBarPaint).apply { this.color = color }
        }
        fun climateOverlayForColor(color: Int): Paint = climateOverlayByColor.getOrPut(color) {
            // Paint.setColor overwrites alpha, so re-apply the climate overlay alpha after.
            Paint(climateOverlayBarPaint).apply {
                this.color = color
                alpha = DailyBarRenderer.CLIMATE_OVERLAY_ALPHA
            }
        }
        fun todayForecastForColor(color: Int): Paint = todayForecastByColor.getOrPut(color) {
            Paint(todayForecastBluePaint).apply { this.color = color }
        }
    }

    fun renderGraph(
        context: Context,
        days: List<DayData>,
        widthPx: Int,
        heightPx: Int,
        bitmapScale: Float = 1f,
        numColumns: Int = 0,
        job: Job? = null,
        onBarDrawn: ((BarDrawnDebug) -> Unit)? = null,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)? = null,
        onDayLabelDrawn: ((DayLabelDrawnDebug) -> Unit)? = null,
        headerData: HeaderRenderData? = null,
        showErrorWatermark: Boolean = false,
        errorSourceLabel: String? = null,
        errorCode: String? = null,
        errorFailureTimeMs: Long? = null,
        onHeaderDrawn: ((HeaderDrawnDebug) -> Unit)? = null,
        useCelsius: Boolean,
    ): Bitmap {
        job?.ensureActive()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (days.isEmpty()) {
            Log.w(TAG, "renderGraph: empty days list, returning blank bitmap (${widthPx}x${heightPx})")
            if (showErrorWatermark) {
                val watermarkDensity = context.resources.displayMetrics.density * bitmapScale
                GraphFailureWatermarkRenderer.draw(canvas, widthPx.toFloat(), heightPx.toFloat(), watermarkDensity, errorSourceLabel, errorCode, errorFailureTimeMs)
            }
            return bitmap
        }

        val columns = if (numColumns > 0) numColumns else days.size
        val layout = computeLayout(context, days, widthPx, heightPx, columns, bitmapScale, job, useCelsius = useCelsius)
        val paints = getPaintSet(layout.scaleFactor, layout)

        debug { "renderGraph: days=${days.size}, minTemp=${layout.minTemp}, maxTemp=${layout.maxTemp}, widthPx=$widthPx, heightPx=$heightPx" }

        val daysByColumn = days.withIndex().associate { (i, d) -> (d.columnIndex ?: i) to d }

        // Per-day cache of the DAILY (daytime) rain-label debug rect, populated as a side-effect
        // of drawDayColumn's onRainLabelDrawn callback. Night-rain draws go through the same
        // callback, so we filter by exact-match against day.rainData.dailyRainLabelText — the
        // cached version is then reused by suppressHeaderDateForRainOverlap to skip the second
        // text-measure + geometry pass per day (F1).
        val dailyRainTextByDate = days.associate { it.date to it.rainData.dailyRainLabelText }
        val drawnDailyRainLabelByDate = mutableMapOf<java.time.LocalDate, RainLabelDrawnDebug>()
        fun collectDailyRainLabel(dbg: RainLabelDrawnDebug) {
            val expectedText = dailyRainTextByDate[dbg.date]
            if (expectedText != null && dbg.text == expectedText) {
                drawnDailyRainLabelByDate[dbg.date] = dbg
            }
        }
        val collectAndEmitRainLabel: ((RainLabelDrawnDebug) -> Unit)? = if (onRainLabelDrawn != null) {
            { dbg -> collectDailyRainLabel(dbg); onRainLabelDrawn.invoke(dbg) }
        } else {
            { dbg -> collectDailyRainLabel(dbg) }
        }

        days.forEachIndexed { index, day ->
            job?.ensureActive()
            val rawColumnIndex = day.columnIndex ?: index
            val columnIndex = rawColumnIndex.coerceIn(0, layout.columns - 1)
            if (rawColumnIndex != columnIndex) {
                Log.w(TAG, "renderGraph: clamped out-of-range columnIndex date=${day.date} requested=$rawColumnIndex columns=${layout.columns}")
            }
            val centerX = layout.horizontalPadding + layout.dayWidth * columnIndex + layout.dayWidth / 2f
            val rightNeighbor = daysByColumn[columnIndex + 1]

            // Frosted-glass focal panel BEHIND today's triple-bar column (drawn before the bars so it
            // sits underneath them and their labels).
            if (day.isToday) {
                DailyBarRenderer.drawTodayHighlightPanel(canvas, centerX, layout, paints)
            }

            // Bars first, then column content (weather icon, low/day labels) so the icon
            // and labels render on top of any bar geometry that might overlap them.
            DailyBarRenderer.drawDayBars(canvas, day, centerX, layout, paints, onBarDrawn)
            drawDayColumn(canvas, context, day, rightNeighbor, centerX, layout, paints, collectAndEmitRainLabel, onDayLabelDrawn)
        }

        val finalHeaderData = headerData?.let {
            suppressHeaderDateForRainOverlap(it, days, layout, paints, widthPx, drawnDailyRainLabelByDate)
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

        return bitmap
    }

    private fun suppressHeaderDateForRainOverlap(
        headerData: HeaderRenderData,
        days: List<DayData>,
        layout: LayoutInfo,
        paints: PaintSet,
        widthPx: Int,
        // Reused from the render pass — daily rain labels actually drawn on the canvas, indexed by
        // day.date. When non-null for a day we use the cached geometry and SKIP the second
        // resolveDailyRainLabelPlacement call (the original cause of F1's duplicate text-measure +
        // geometry pass per day). Caller may pass an empty map to fall back to the re-resolve path
        // (used by tests and the legacy single-pass entry).
        drawnDailyRainLabelByDate: Map<java.time.LocalDate, RainLabelDrawnDebug> = emptyMap(),
    ): HeaderRenderData {
        if (headerData.dateText.isNullOrBlank()) return headerData
        val padding = COLUMN_EDGE_MARGIN_DP.dp(layout.density)
        val dateBounds = DailyForecastHeaderRenderer.resolveHeaderDateBounds(
            header = headerData,
            widthPx = widthPx,
            layout = layout,
            extraPaddingPx = padding,
        ) ?: return headerData

        days.forEachIndexed { index, day ->
            val rawColumnIndex = day.columnIndex ?: index
            val columnIndex = rawColumnIndex.coerceIn(0, layout.columns - 1)
            val centerX = layout.horizontalPadding + layout.dayWidth * columnIndex + layout.dayWidth / 2f
            // F1: prefer the geometry that was actually drawn (already cached during drawDayColumn's
            // onRainLabelDrawn callback). Only re-resolve when the cache missed (e.g. callers that
            // test this function in isolation with an empty cache).
            val rainLabel = drawnDailyRainLabelByDate[day.date]
                ?: DailyForecastRainLabelRenderer.resolveDailyRainLabelPlacement(
                    day = day,
                    centerX = centerX,
                    layout = layout,
                    paints = paints,
                )?.debug
                ?: return@forEachIndexed
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

    private fun computeLayout(
        context: Context,
        days: List<DayData>,
        widthPx: Int,
        heightPx: Int,
        columns: Int,
        bitmapScale: Float,
        job: Job? = null,
        useCelsius: Boolean,
    ): LayoutInfo {
        job?.ensureActive()
        // Single-pass running min/max — avoid the flatMap→7N-alloc→2-walk pattern that fired per
        // render during navigation/zoom. Inline the tempToY range math down the same path.
        var minTemp = Float.POSITIVE_INFINITY
        var maxTemp = Float.NEGATIVE_INFINITY
        // Axis backstop: snapshotHigh/snapshotLow/ghostLineHigh feed the y-axis range but are never
        // printed as text, so a bad value in one of them is invisible as a number while still
        // rescaling EVERY column through tempToY. That is the 2026-07-27 / 2026-07-28 failure mode —
        // a single -100 stretched today's bar past the day-label row and squashed the other nine
        // into stubs, while each label alongside read perfectly healthy. The DAO read guard keeps
        // known sentinels out of these values; this keeps any that still get through (from any
        // source) inside their own cell. The bounds are physical, not provider-specific, despite the
        // NWS-prefixed object name. Kept flat/inline rather than a local fun so the per-render
        // allocation win noted above isn't given back to closure capture.
        var rejected = 0
        var firstRejected = Float.NaN
        for (d in days) {
            val a = d.solidLineHigh
            if (a != null) { if (isPlausibleF(a)) { if (a < minTemp) minTemp = a; if (a > maxTemp) maxTemp = a } else { if (rejected == 0) firstRejected = a; rejected++ } }
            val b = d.solidLineLow
            if (b != null) { if (isPlausibleF(b)) { if (b < minTemp) minTemp = b; if (b > maxTemp) maxTemp = b } else { if (rejected == 0) firstRejected = b; rejected++ } }
            val c = d.dashedLineHigh
            if (c != null) { if (isPlausibleF(c)) { if (c < minTemp) minTemp = c; if (c > maxTemp) maxTemp = c } else { if (rejected == 0) firstRejected = c; rejected++ } }
            val e = d.dashedLineLow
            if (e != null) { if (isPlausibleF(e)) { if (e < minTemp) minTemp = e; if (e > maxTemp) maxTemp = e } else { if (rejected == 0) firstRejected = e; rejected++ } }
            val f = d.snapshotHigh
            if (f != null) { if (isPlausibleF(f)) { if (f < minTemp) minTemp = f; if (f > maxTemp) maxTemp = f } else { if (rejected == 0) firstRejected = f; rejected++ } }
            val g = d.snapshotLow
            if (g != null) { if (isPlausibleF(g)) { if (g < minTemp) minTemp = g; if (g > maxTemp) maxTemp = g } else { if (rejected == 0) firstRejected = g; rejected++ } }
            val h = d.ghostLineHigh
            if (h != null) { if (isPlausibleF(h)) { if (h < minTemp) minTemp = h; if (h > maxTemp) maxTemp = h } else { if (rejected == 0) firstRejected = h; rejected++ } }
        }
        if (rejected > 0) {
            // Permanent breadcrumb — the next occurrence should be one logcat query away rather than
            // another screenshot investigation.
            Log.w(TAG, "computeLayout: excluded $rejected implausible temp(s) from axis range, first=$firstRejected days=${days.size}")
        }
        if (!minTemp.isFinite()) minTemp = 0f
        if (!maxTemp.isFinite()) maxTemp = 100f
        val tempRange = (maxTemp - minTemp).coerceAtLeast(1f)

        val density = context.resources.displayMetrics.density
        val widthDp = widthPx / density
        val heightDp = heightPx / density

        val dayWidthDp = widthDp / columns
        val widthScaleFactor = (dayWidthDp / BASE_DAY_WIDTH_DP).coerceIn(1.0f, 1.2f)
        val dayLabelWidthScale = computeDayLabelWidthScale(dayWidthDp)

        // Only used for tempLabelHeight scaling, not the overall scaleFactor which is width-driven.
        val heightScaleFactor = when {
            heightDp < 150f -> 0.92f
            else -> 1.0f
        }

        val scaleFactor = widthScaleFactor
        val labelScale = bitmapScale.coerceAtMost(1f)
        val horizontalPadding = 0f
        val topPadding = (TOP_PADDING_DP * labelScale).dp(density)

        val dayLabelScale = labelScale * dayLabelWidthScale
        val baseDayLabelTextSizePx = (DAY_LABEL_BASE_SIZE_DP * dayLabelScale * DAY_LABEL_TEXT_SCALE).dp(density)
        val dayWidth = (widthPx - 2 * horizontalPadding) / columns
        val dayLabelLayout = resolveDayLabelLayout(
            labels = days.map { DayLabelInput(it.date, it.label, it.isToday) },
            baseTextSizePx = baseDayLabelTextSizePx,
            maxTextWidthPx = (dayWidth - (DAY_LABEL_HORIZONTAL_GAP_DP * labelScale).dp(density)).coerceAtLeast(1f),
            minScale = MIN_DYNAMIC_DAY_LABEL_SCALE,
        )
        val dayLabelHeight = dayLabelLayout.textSizePx * DAY_LABEL_SIZE_MULTIPLIER
        val tempLabelHeight = dailyForecastTempLabelSizePx(density, heightScaleFactor, bitmapScale)

        val iconSize = (ICON_BASE_SIZE_DP * labelScale).dp(density).toInt()
        val attachedStackHeight = tempLabelHeight + iconSize + (ICON_STACK_SPACING_DP * labelScale).dp(density)

        val graphTop = topPadding
        val requestedGraphBottom = heightPx - dayLabelHeight - attachedStackHeight
        val graphBottom = requestedGraphBottom.coerceAtLeast(graphTop + 1f)
        val graphHeight = (graphBottom - graphTop).coerceAtLeast(1f)

        if (requestedGraphBottom < graphTop + 1f) {
            Log.w(
                TAG,
                "computeLayout: clamping undersized graph area widthPx=$widthPx heightPx=$heightPx" +
                    " graphTop=$graphTop requestedGraphBottom=$requestedGraphBottom graphBottom=$graphBottom",
            )
        }

        val barWidth = dailyBarStrokeWidthPx(density, scaleFactor, bitmapScale)
        val tripleBarWidth = todayTripleBarStrokeWidthPx(density, scaleFactor, bitmapScale)

        // Spread of today's flanking bars from the centre (shared with desktop). Android draws all
        // three bars at tripleBarWidth, so centre and flank widths are equal here.
        val tripleBarOffset = TodayColumnHighlight.tripleBarSpacing(
            centerBarWidthPx = tripleBarWidth,
            flankBarWidthPx = tripleBarWidth,
            dayWidthPx = dayWidth,
            columnEdgeMarginPx = COLUMN_EDGE_MARGIN_DP.dp(density),
        )

        if (dayLabelLayout.scale < 0.999f || dayLabelLayout.shortenedLabels) {
            debug {
                "dayLabel layout adjusted: widthPx=$widthPx columns=$columns dayWidth=$dayWidth" +
                    " baseTextSize=$baseDayLabelTextSizePx finalTextSize=${dayLabelLayout.textSizePx}" +
                    " scale=${dayLabelLayout.scale} shortened=${dayLabelLayout.shortenedLabels}" +
                    " labels=${dayLabelLayout.textByDate.values.joinToString(",")}"
            }
        }

        return LayoutInfo(
            widthPx = widthPx,
            heightPx = heightPx,
            columns = columns,
            minTemp = minTemp,
            maxTemp = maxTemp,
            tempRange = tempRange,
            scaleFactor = scaleFactor,
            graphTop = graphTop,
            graphBottom = graphBottom,
            graphHeight = graphHeight,
            dayWidth = dayWidth,
            tempLabelMaxWidthPx = dayWidth + (TEMP_LABEL_OVERLAP_ALLOWANCE_DP * labelScale).dp(density),
            horizontalPadding = horizontalPadding,
            tripleBarOffset = tripleBarOffset,
            forecastBarOffset = barWidth * DailyBarRenderer.FORECAST_BAR_OFFSET_SCALE,
            iconSize = iconSize,
            dayLabelHeight = dayLabelHeight,
            tempLabelHeight = tempLabelHeight,
            bulbRadius = tripleBarWidth * DailyBarRenderer.BULB_RADIUS_SCALE,
            bitmapScale = bitmapScale,
            minBarHeightPx = (MIN_BAR_HEIGHT_DP).dp(density),
            dayLabelTextByDate = dayLabelLayout.textByDate,
            density = density,
            useCelsius = useCelsius,
        )
    }

    private fun getPaintSet(scaleFactor: Float, layout: LayoutInfo): PaintSet {
        val current = paintCaches
        current.firstOrNull {
            it.scaleFactor == scaleFactor &&
                it.dayLabelHeight == layout.dayLabelHeight &&
                it.tempLabelHeight == layout.tempLabelHeight
        }?.let { return it.set }

        val labelScale = layout.bitmapScale.coerceIn(0.5f, 1f)
        val barWidth = dailyBarStrokeWidthPx(layout.density, scaleFactor, layout.bitmapScale)
        val tripleBarWidth = todayTripleBarStrokeWidthPx(layout.density, scaleFactor, layout.bitmapScale)
        val shadowRadius = (LABEL_SHADOW_RADIUS_DP * labelScale).dp(layout.density)
        val shadowDy = (LABEL_SHADOW_DY_DP * labelScale).dp(layout.density)

        val set = PaintSet(
            barPaint = createBarPaint(DailyBarRenderer.COLOR_FORECAST, barWidth),
            todayObservedRedPaint = createBarPaint(DailyBarRenderer.COLOR_OBSERVED_RED, tripleBarWidth),
            todayObservedGhostPaint = createBarPaint(DailyBarRenderer.COLOR_OBSERVED_RED, tripleBarWidth).apply {
                alpha = DailyBarRenderer.GHOST_BAR_ALPHA
            },
            todayObservedRedBulbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = DailyBarRenderer.COLOR_OBSERVED_RED
                style = Paint.Style.FILL
            },
            todaySnapshotYellowPaint = createBarPaint(DailyBarRenderer.COLOR_TODAY_HIGHLIGHT, tripleBarWidth),
            todayForecastBluePaint = createBarPaint(DailyBarRenderer.COLOR_FORECAST, tripleBarWidth),
            historyBarPaint = createBarPaint(
                DailyBarRenderer.COLOR_OBSERVED_RED,
                barWidth * DailyBarRenderer.HISTORY_BAR_WIDTH_SCALE,
            ),
            forecastBarPaint = createBarPaint(
                DailyBarRenderer.COLOR_FORECAST,
                barWidth * DailyBarRenderer.FORECAST_OVERLAY_WIDTH_SCALE,
            ),
            climateOverlayBarPaint = createBarPaint(
                DailyBarRenderer.COLOR_FORECAST,
                barWidth * DailyBarRenderer.CLIMATE_OVERLAY_WIDTH_SCALE,
            ).apply {
                alpha = DailyBarRenderer.CLIMATE_OVERLAY_ALPHA
            },
            gapFallbackBarPaint = createBarPaint(DailyBarRenderer.COLOR_GAP_FALLBACK, barWidth),
            textPaint = createTextPaint(
                COLOR_LABEL_GRAY,
                layout.dayLabelHeight / DAY_LABEL_SIZE_MULTIPLIER
            ),
            todayTextPaint = createTextPaint(
                COLOR_TODAY_TEXT,
                layout.dayLabelHeight / DAY_LABEL_SIZE_MULTIPLIER,
                true
            ),
            // Temp labels carry no blur: history labels get a thin black outline (drawTempLabel
            // drawOutline=true), today/future labels get no shadow at all.
            tempTextPaint = createTextPaint(COLOR_WHITE, layout.tempLabelHeight),
            // History actuals (low label, and single high when not a dual-label mismatch) read as
            // the thermostat/observed color rather than plain white, matching the dual-high actual
            // label and today's settled-high recolor below.
            pastTempTextPaint = createTextPaint(
                DailyBarRenderer.COLOR_OBSERVED_RED,
                layout.tempLabelHeight * PAST_TEMP_SCALE,
            ),
            // Today temp labels are NOT bold (matches desktop's default weight); they stand out via
            // the COLOR_TODAY_TEXT highlight + outline, not weight.
            todayTempTextPaint = createTextPaint(COLOR_TODAY_TEXT, layout.tempLabelHeight),
            rainTextPaint = createTextPaint(
                DailyBarRenderer.COLOR_FORECAST,
                (RAIN_TEXT_SIZE_DP * scaleFactor * labelScale).dp(layout.density),
                shadowRadius = shadowRadius,
                shadowDy = shadowDy,
            ),
            todayPanelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = TodayColumnHighlight.PANEL_FILL_ARGB
            },
        )

        val newCache = PaintCache(scaleFactor, layout.dayLabelHeight, layout.tempLabelHeight, set)
        paintCaches = (listOf(newCache) + current).take(PAINT_CACHE_LRU_SIZE)
        return set
    }

    private fun createBarPaint(colorInt: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
    }

    private fun createTextPaint(
        colorInt: Int,
        size: Float,
        bold: Boolean = false,
        shadowRadius: Float = 0f,
        shadowDy: Float = 0f
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        textSize = size
        textAlign = Paint.Align.CENTER
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        if (shadowRadius > 0f) {
            setShadowLayer(shadowRadius, 0f, shadowDy, 0xFF000000.toInt())
        }
    }

    private fun drawDayColumn(
        canvas: Canvas,
        context: Context,
        day: DayData,
        rightNeighbor: DayData?,
        centerX: Float,
        layout: LayoutInfo,
        paints: PaintSet,
        onRainLabelDrawn: ((RainLabelDrawnDebug) -> Unit)?,
        onDayLabelDrawn: ((DayLabelDrawnDebug) -> Unit)?,
    ) {
        val labelPaint = if (day.isToday) paints.todayTextPaint else paints.textPaint
        val dayLabel = layout.dayLabelTextByDate[day.date] ?: day.label
        val dayLabelBaseline = layout.heightPx - DAY_LABEL_BOTTOM_MARGIN_DP.dp(layout.density)
        canvas.drawText(dayLabel, centerX, dayLabelBaseline, labelPaint)
        val labelWidth = measureTextWidth(labelPaint, dayLabel)
        onDayLabelDrawn?.invoke(
            DayLabelDrawnDebug(
                date = day.date,
                text = dayLabel,
                centerX = centerX,
                baselineY = dayLabelBaseline,
                leftX = centerX - labelWidth / 2f,
                rightX = centerX + labelWidth / 2f,
                textSize = labelPaint.textSize,
            ),
        )

        // Position the icon + low label under the LOWEST drawn bar (geometry), but print the
        // gated/observed low VALUE. Decoupling these keeps the icon under the deepest bar even
        // when the headline number tracks a higher actual (today) or the forecast overlay dips
        // below the observed bar (history). See DailyDayValueResolver.iconAnchorLow.
        val anchorLow = resolveIconAnchorLow(day)
        val displayLow = resolveBottomStackLow(day)
        val lowY = anchorLow?.let { layout.tempToY(it) }

        // Captured so the night rain label can treat this day's own low label (degree symbol
        // included) as an obstacle to avoid; null when no low label is drawn.
        var ownLowLabelBox: DailyForecastRainLabelRenderer.LowLabelBox? = null
        if (lowY != null) {
            val iconY = lowY + (ICON_BELOW_BAR_SPACING_DP).dp(layout.density)
            drawWeatherIcon(canvas, context, day, centerX, iconY, layout.iconSize)

            if (displayLow != null) {
                val lowTempY = iconY + layout.iconSize + layout.tempLabelHeight + (TEMP_LABEL_SPACING_DP).dp(layout.density)

                val isLowest = displayLow <= layout.minTemp + 0.01f
                val hasNightRain = day.rainData.nightRainLabelText != null
                val forceInteger = isLowest && hasNightRain
                val lowLabelText = formatTempLabel(displayLow, forceInteger = forceInteger, useCelsius = layout.useCelsius)

                val tempPaint = when {
                    day.isToday -> paints.todayTempTextPaint
                    day.isPast -> paints.pastTempTextPaint
                    else -> paints.tempTextPaint
                }
                // Mirrors the high label: once today's overnight low is settled (past the 9am
                // cutoff) the number tracks the observed actual, so it recolors to the thermostat
                // (observed) color instead of the today-highlight color.
                val todayLowSettled = com.weatherwidget.shared.util.DailyDayValueResolver.isLowTrackingActual(
                    isToday = day.isToday,
                    solidLow = day.solidLineLow,
                    nowHour = day.nowHour,
                )
                val lowColorOverride =
                    if (todayLowSettled) DailyBarRenderer.COLOR_OBSERVED_RED else null
                drawTempLabel(canvas, lowLabelText, centerX, lowTempY, tempPaint,
                    colorOverride = lowColorOverride, drawOutline = day.isPast,
                    maxWidthPx = layout.tempLabelMaxWidthPx)

                // Unscaled metrics are a safe (slight) over-estimate of the drawn glyph extent,
                // which only ever makes the obstacle larger — never missing a real collision. Read
                // ascent/descent via the null-safe helpers: Paint.fontMetrics is null under the
                // stubbed-Paint Robolectric tests (see twoLabelHeight below), and they fall back to
                // textSize there.
                val lowHalfWidth = measureTextWidth(tempPaint, lowLabelText) / 2f
                ownLowLabelBox = DailyForecastRainLabelRenderer.LowLabelBox(
                    left = centerX - lowHalfWidth,
                    top = lowTempY + TemperatureGraphStyle.fontAscent(tempPaint),
                    right = centerX + lowHalfWidth,
                    bottom = lowTempY + TemperatureGraphStyle.fontDescent(tempPaint),
                    baseline = lowTempY,
                )
            }
        }

        DailyForecastRainLabelRenderer.drawDailyRainLabel(day, centerX, layout, paints, onRainLabelDrawn, canvas)
        DailyForecastRainLabelRenderer.drawNightRainLabel(day, rightNeighbor, centerX, layout, paints, ownLowLabelBox, onRainLabelDrawn, canvas)
    }

    private fun drawWeatherIcon(canvas: Canvas, context: Context, day: DayData, centerX: Float, iconY: Float, iconSize: Int) {
        if (day.iconRes == null) return
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, day.iconRes)?.mutate() ?: return

        val iconX = centerX - iconSize / 2f
        drawable.setBounds(iconX.toInt(), iconY.toInt(), (iconX + iconSize).toInt(), (iconY + iconSize).toInt())

        // Tint palette intentionally diverges from DailyTextRenderer (file 1) and the hourly-graph
        // icon tint (file 3): this icon sits over a coloured weather bar against the widget's
        // surface, not against a text row or a temperature curve. The two-tone sunny/gray split
        // keeps the rain/mixed icons full-colour (their drawable already encodes severity) while
        // dimming the sun/cloud icons to match the muted	daily-bar palette. DailyTextRenderer
        // uses WeatherIconMapper.resolveDailyTextIconTint (text-row palette, more contrast against
        // zero-bar background); the hourly curve uses its own accent tint against the curve. Three
        // contexts, three palettes — don't fold this into a shared helper.
        if (!day.isRainy && !day.isMixed) {
            val tint = if (day.isSunny) COLOR_SUNNY else COLOR_LABEL_GRAY
            drawable.setTint(tint)
        }
        drawable.draw(canvas)
    }

    /**
     * Draws a centered temp label. [extraScale] shrinks the font (e.g. 0.92 for past-day dual highs);
     * wide 3+ digit temps (100°, 97.7°) shrink a further 5%. [colorOverride] recolors a copy of [base]
     * (thermostat / forecast color). The common full-size/no-recolor case reuses [base] without allocating.
     * When [drawOutline] is true (history labels), a thin black stroke is drawn behind the fill to keep
     * the label legible over a same-colored bar.
     */
    internal fun drawTempLabel(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        base: Paint,
        extraScale: Float = 1f,
        colorOverride: Int? = null,
        drawOutline: Boolean = false,
        maxWidthPx: Float = Float.MAX_VALUE,
    ) {
        // Start from the existing fixed wide-label step, then shrink-to-fit against the column width
        // so 3+ digit / decimal temps (e.g. "77.7°") never spill their degree symbol into the next
        // column on dense layouts. Keeps the ° and the tenths; only the font size adapts.
        val scale = tempLabelDrawScale(base, text, extraScale, maxWidthPx)
        val paint = if (colorOverride == null && scale == 1f) base else Paint(base).apply {
            if (colorOverride != null) color = colorOverride
            textSize = base.textSize * scale
        }
        if (drawOutline) {
            val outlinePaint = Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = paint.textSize * LABEL_OUTLINE_STROKE_FRACTION
                color = 0xFF000000.toInt()
                clearShadowLayer()
            }
            canvas.drawText(text, centerX, baselineY, outlinePaint)
        }
        canvas.drawText(text, centerX, baselineY, paint)
    }

    /**
     * Reduces [currentScale] just enough that a label measuring [measuredWidthAtScale] px fits
     * within [maxWidthPx], never going below [minScale]. Returns [currentScale] unchanged when the
     * label already fits (or no budget is given). Pure so the shrink-to-fit math is unit-testable.
     */
    internal fun fitScaleForWidth(
        measuredWidthAtScale: Float,
        maxWidthPx: Float,
        currentScale: Float,
        minScale: Float = MIN_TEMP_LABEL_FIT_SCALE,
    ): Float {
        if (maxWidthPx <= 0f || measuredWidthAtScale <= maxWidthPx) return currentScale
        return (currentScale * (maxWidthPx / measuredWidthAtScale)).coerceAtLeast(currentScale * minScale)
    }

    /**
     * The font scale a temp label is actually drawn at: the fixed wide-label step times any caller
     * [extraScale], then shrink-to-fit against the column width. Extracted from [drawTempLabel] so the
     * rain label can anchor to the high label as it is *rendered* (a wide "75.6°" squeezed into a
     * narrow column draws much smaller than its full-size metrics imply).
     */
    internal fun tempLabelDrawScale(
        base: Paint,
        text: String,
        extraScale: Float,
        maxWidthPx: Float,
    ): Float {
        val baseScale = extraScale * (if (DualHighLabel.isWideLabel(text)) DualHighLabel.WIDE_LABEL_FONT_SCALE else 1f)
        return fitScaleForWidth(measureTextWidth(base, text) * baseScale, maxWidthPx, baseScale)
    }

    // ── Baseline resolvers (shared by bars and rain labels) ────────────────
    //
    // HIGH-label resolution lives in DailyHighLabelPlanner (resolveHighLabelPlan /
    // resolveHighLabelBaseline / resolveHighLabelDrawScale / DayData.effectiveHigh()) — extracted as
    // split A of plans/260728b-dailyforecastgraphrenderer-code-review.md so the dense dual-high math
    // is one cohesive unit, callable from the bar drawer here AND from DailyForecastRainLabelRenderer.

    internal fun resolveLowLabelBaseline(
        day: DayData,
        layout: LayoutInfo,
    ): Float? {
        val lowTemp = resolveIconAnchorLow(day) ?: return null
        val lowY = layout.tempToY(lowTemp)
        val iconY = lowY + (ICON_BELOW_BAR_SPACING_DP).dp(layout.density)
        return iconY + layout.iconSize + layout.tempLabelHeight + (TEMP_LABEL_SPACING_DP).dp(layout.density)
    }

    // ── Utility ───────────────────────────────────────────────────────────

    /** The printed low VALUE (gated effective low on today; observed/forecast otherwise). */
    internal fun resolveBottomStackLow(day: DayData): Float? = day.bottomStackLow ?: day.solidLineLow

    /** The icon/low-label POSITION anchor: bottom of the lowest drawn bar for the column. */
    internal fun resolveIconAnchorLow(day: DayData): Float? =
        com.weatherwidget.shared.util.DailyDayValueResolver.iconAnchorLow(
            solidLow = day.solidLineLow,
            forecastLow = day.dashedLineLow,
            snapshotLow = day.snapshotLow,
        ) ?: day.bottomStackLow ?: day.solidLineLow

    private fun Float.dp(density: Float): Float = this * density
    private fun Int.dp(density: Float): Float = this.toFloat() * density

    private fun measureTextWidth(paint: Paint, text: String): Float {
        val measured = paint.measureText(text)
        if (measured > 0f) return measured
        return text.length * paint.textSize * 0.55f
    }

    @VisibleForTesting
    internal fun resolveDayLabelLayout(
        labels: List<DayLabelInput>,
        baseTextSizePx: Float,
        maxTextWidthPx: Float,
        minScale: Float = MIN_DYNAMIC_DAY_LABEL_SCALE,
    ): DayLabelLayoutResult {
        if (labels.isEmpty() || baseTextSizePx <= 0f) {
            return DayLabelLayoutResult(
                textSizePx = baseTextSizePx,
                textByDate = labels.associate { it.date to it.label },
                scale = 1f,
                shortenedLabels = false,
            )
        }

        val originalTextByDate = labels.associate { it.date to it.label }
        val originalScale = fittingScale(labels, originalTextByDate, baseTextSizePx, maxTextWidthPx)
        if (originalScale >= minScale) {
            return DayLabelLayoutResult(
                textSizePx = baseTextSizePx * originalScale,
                textByDate = originalTextByDate,
                scale = originalScale,
                shortenedLabels = false,
            )
        }

        val shortenedTextByDate = labels.associate { input ->
            val label = if (input.isToday) {
                input.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            } else {
                input.label
            }
            input.date to label
        }
        val shortenedScale = fittingScale(labels, shortenedTextByDate, baseTextSizePx, maxTextWidthPx)
            .coerceAtLeast(minScale)

        return DayLabelLayoutResult(
            textSizePx = baseTextSizePx * shortenedScale,
            textByDate = shortenedTextByDate,
            scale = shortenedScale,
            shortenedLabels = shortenedTextByDate != originalTextByDate,
        )
    }

    private fun fittingScale(
        labels: List<DayLabelInput>,
        textByDate: Map<LocalDate, String>,
        baseTextSizePx: Float,
        maxTextWidthPx: Float,
    ): Float {
        val regularPaint = dayLabelMeasurePaint(baseTextSizePx, bold = false)
        val todayPaint = dayLabelMeasurePaint(baseTextSizePx, bold = true)
        val widest = labels.maxOfOrNull { input ->
            val text = textByDate[input.date].orEmpty()
            val paint = if (input.isToday) todayPaint else regularPaint
            measureTextWidth(paint, text)
        } ?: 0f
        if (widest <= 0f || widest <= maxTextWidthPx) return 1f
        return (maxTextWidthPx / widest).coerceAtMost(1f)
    }

    private fun dayLabelMeasurePaint(textSizePx: Float, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            textAlign = Paint.Align.CENTER
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    internal fun computeDayLabelWidthScale(dayWidthDp: Float): Float {
        return (dayWidthDp / BASE_DAY_WIDTH_DP).coerceIn(MIN_DAY_LABEL_WIDTH_SCALE, MAX_DAY_LABEL_WIDTH_SCALE)
    }

    @VisibleForTesting
    internal fun dailyForecastTempLabelSizePx(
        density: Float,
        heightScaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float {
        val labelScale = bitmapScale.coerceIn(0.5f, 1f)
        return (TEMP_LABEL_TEXT_SIZE_DP * heightScaleFactor * labelScale).dp(density)
    }

    @VisibleForTesting
    internal fun dailyBarStrokeWidthPx(
        density: Float,
        scaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float = DailyBarRenderer.dailyBarStrokeWidthPx(density, scaleFactor, bitmapScale)

    @VisibleForTesting
    internal fun todayTripleBarStrokeWidthPx(
        density: Float,
        scaleFactor: Float = 1f,
        bitmapScale: Float = 1f,
    ): Float = DailyBarRenderer.todayTripleBarStrokeWidthPx(density, scaleFactor, bitmapScale)

    // Show the tenth (e.g. "77.5°") for any non-integer value, suppressing the ".0" case
    // so whole-degree sources (NWS integer forecasts) stay clean. forceInteger overrides
    // this for the low label when a night-rain label sits alongside it (collision avoidance).
    //
    // Two formatting paths on purpose: the non-integer branch reuses TempUtils.formatTemp so the
    // rounding rules (banker's rounding for .5, locale-aware minus sign) stay shared with the rest
    // of the app. The integer branch is a hand-rolled `roundToInt() + "°"` because formatTemp
    // currently doesn't take a force-integer flag — adding one would touch every caller, so a
    // local fast path is cheaper until/unless a second caller needs it. If a second force-integer
    // caller shows up, lift this into TempUtils.formatTemp instead of duplicating again.
    internal fun formatTempLabel(value: Float, forceInteger: Boolean = false, useCelsius: Boolean): String {
        val displayVal = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(value) else value
        if (forceInteger) return "${displayVal.roundToInt()}°"
        return com.weatherwidget.util.TempUtils.formatTemp(value, useCelsius) ?: ""
    }
}

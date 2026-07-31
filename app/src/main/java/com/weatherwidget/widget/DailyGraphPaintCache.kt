package com.weatherwidget.widget

import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.VisibleForTesting
import com.weatherwidget.shared.graph.TodayColumnHighlight
import com.weatherwidget.widget.handlers.HeaderConstants
import java.util.concurrent.ConcurrentHashMap

internal class DailyGraphPaintSet(
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

    fun barForColor(color: Int): Paint =
        barByColor.getOrPut(color) {
            Paint(barPaint).apply { this.color = color }
        }

    fun forecastForColor(color: Int): Paint =
        forecastByColor.getOrPut(color) {
            Paint(forecastBarPaint).apply { this.color = color }
        }

    fun climateOverlayForColor(color: Int): Paint =
        climateOverlayByColor.getOrPut(color) {
            Paint(climateOverlayBarPaint).apply {
                this.color = color
                alpha = DailyBarRenderer.CLIMATE_OVERLAY_ALPHA
            }
        }

    fun todayForecastForColor(color: Int): Paint =
        todayForecastByColor.getOrPut(color) {
            Paint(todayForecastBluePaint).apply { this.color = color }
        }
}

/** Thread-safe bounded cache for immutable daily graph paint templates. */
internal object DailyGraphPaintCache {
    private const val CACHE_SIZE = 3
    private const val PAST_TEMP_SCALE = 0.9f
    private const val LABEL_SHADOW_RADIUS_DP = 2.5f
    private const val LABEL_SHADOW_DY_DP = 1f
    private val COLOR_LABEL_GRAY = 0xFFAAAAAA.toInt()
    private val COLOR_TODAY_TEXT = 0xFFFFEACC.toInt()
    private val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private val RAIN_TEXT_SIZE_DP = HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP

    internal data class Key(
        val scaleFactor: Float,
        val dayLabelHeight: Float,
        val tempLabelHeight: Float,
        val density: Float,
        val bitmapScale: Float,
    )

    internal data class Entry(
        val key: Key,
        val set: DailyGraphPaintSet,
    )

    private var entries: List<Entry> = emptyList()

    @Synchronized
    internal fun get(layout: DailyGraphLayoutInfo): DailyGraphPaintSet {
        val key =
            Key(
                scaleFactor = layout.scaleFactor,
                dayLabelHeight = layout.dayLabelHeight,
                tempLabelHeight = layout.tempLabelHeight,
                density = layout.density,
                bitmapScale = layout.bitmapScale,
            )
        entries.firstOrNull { it.key == key }?.let { return it.set }

        val labelScale = layout.bitmapScale.coerceIn(0.5f, 1f)
        val barWidth =
            DailyBarRenderer.dailyBarStrokeWidthPx(
                layout.density,
                layout.scaleFactor,
                layout.bitmapScale,
            )
        val tripleBarWidth =
            DailyBarRenderer.todayTripleBarStrokeWidthPx(
                layout.density,
                layout.scaleFactor,
                layout.bitmapScale,
            )
        val shadowRadius = (LABEL_SHADOW_RADIUS_DP * labelScale).dp(layout.density)
        val shadowDy = (LABEL_SHADOW_DY_DP * labelScale).dp(layout.density)

        val set =
            DailyGraphPaintSet(
                barPaint = createBarPaint(DailyBarRenderer.COLOR_FORECAST, barWidth),
                todayObservedRedPaint =
                    createBarPaint(DailyBarRenderer.COLOR_OBSERVED_RED, tripleBarWidth),
                todayObservedGhostPaint =
                    createBarPaint(DailyBarRenderer.COLOR_OBSERVED_RED, tripleBarWidth).apply {
                        alpha = DailyBarRenderer.GHOST_BAR_ALPHA
                    },
                todayObservedRedBulbPaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = DailyBarRenderer.COLOR_OBSERVED_RED
                        style = Paint.Style.FILL
                    },
                todaySnapshotYellowPaint =
                    createBarPaint(DailyBarRenderer.COLOR_TODAY_HIGHLIGHT, tripleBarWidth),
                todayForecastBluePaint =
                    createBarPaint(DailyBarRenderer.COLOR_FORECAST, tripleBarWidth),
                historyBarPaint =
                    createBarPaint(
                        DailyBarRenderer.COLOR_OBSERVED_RED,
                        barWidth * DailyBarRenderer.HISTORY_BAR_WIDTH_SCALE,
                    ),
                forecastBarPaint =
                    createBarPaint(
                        DailyBarRenderer.COLOR_FORECAST,
                        barWidth * DailyBarRenderer.FORECAST_OVERLAY_WIDTH_SCALE,
                    ),
                climateOverlayBarPaint =
                    createBarPaint(
                        DailyBarRenderer.COLOR_FORECAST,
                        barWidth * DailyBarRenderer.CLIMATE_OVERLAY_WIDTH_SCALE,
                    ).apply {
                        alpha = DailyBarRenderer.CLIMATE_OVERLAY_ALPHA
                    },
                gapFallbackBarPaint =
                    createBarPaint(DailyBarRenderer.COLOR_GAP_FALLBACK, barWidth),
                textPaint =
                    createTextPaint(
                        COLOR_LABEL_GRAY,
                        layout.dayLabelHeight / DailyGraphLayoutResolver.DAY_LABEL_SIZE_MULTIPLIER,
                    ),
                todayTextPaint =
                    createTextPaint(
                        COLOR_TODAY_TEXT,
                        layout.dayLabelHeight / DailyGraphLayoutResolver.DAY_LABEL_SIZE_MULTIPLIER,
                        bold = true,
                    ),
                tempTextPaint = createTextPaint(COLOR_WHITE, layout.tempLabelHeight),
                pastTempTextPaint =
                    createTextPaint(
                        DailyBarRenderer.COLOR_OBSERVED_RED,
                        layout.tempLabelHeight * PAST_TEMP_SCALE,
                    ),
                todayTempTextPaint = createTextPaint(COLOR_TODAY_TEXT, layout.tempLabelHeight),
                rainTextPaint =
                    createTextPaint(
                        DailyBarRenderer.COLOR_FORECAST,
                        (RAIN_TEXT_SIZE_DP * layout.scaleFactor * labelScale).dp(layout.density),
                        shadowRadius = shadowRadius,
                        shadowDy = shadowDy,
                    ),
                todayPanelFillPaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = TodayColumnHighlight.PANEL_FILL_ARGB
                    },
            )

        entries = (listOf(Entry(key, set)) + entries).take(CACHE_SIZE)
        return set
    }

    @VisibleForTesting
    @Synchronized
    internal fun clearForTesting() {
        entries = emptyList()
    }

    @VisibleForTesting
    @Synchronized
    internal fun entriesForTesting(): List<Entry> = entries.toList()

    private fun createBarPaint(
        colorInt: Int,
        width: Float,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
        }

    private fun createTextPaint(
        colorInt: Int,
        size: Float,
        bold: Boolean = false,
        shadowRadius: Float = 0f,
        shadowDy: Float = 0f,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            textSize = size
            textAlign = Paint.Align.CENTER
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (shadowRadius > 0f) {
                setShadowLayer(shadowRadius, 0f, shadowDy, 0xFF000000.toInt())
            }
        }

    private fun Float.dp(density: Float): Float = this * density
}

package com.weatherwidget.widget

import android.graphics.RectF
import android.os.SystemClock
import java.time.LocalDate

/**
 * Cached base paints shared by same-density/same-scale renders.
 *
 * Every member is immutable after construction. A renderer that needs to change color, alignment,
 * alpha, shader, path effect, stroke, or typeface must mutate a render-local [android.graphics.Paint]
 * copy instead.
 */
data class PaintSet(
    val density: Float,
    val labelScale: Float,
    val actualLinePaint: android.graphics.Paint,
    val forecastDashedPaint: android.graphics.Paint,
    val ghostPaint: android.graphics.Paint,
    val expectedFillPaint: android.graphics.Paint,
    val currentTimePaint: android.graphics.Paint,
    val hourLabelTextPaint: android.graphics.Paint,
    val actualTempLabelTextPaint: android.graphics.Paint,
    val forecastTempLabelTextPaint: android.graphics.Paint,
    val nowLabelTextPaint: android.graphics.Paint,
    val dayLabelTextPaint: android.graphics.Paint,
    val todayDayLabelPaint: android.graphics.Paint,
    val ringPaint: android.graphics.Paint,
    val outerRingPaint: android.graphics.Paint,
    val fetchDotValueTextPaint: android.graphics.Paint,
    val stalenessTextPaint: android.graphics.Paint,
    val dominantTempTextPaint: android.graphics.Paint,
    val dominantStationTextPaint: android.graphics.Paint,
    val dominantTimeTextPaint: android.graphics.Paint,
    val actualLeaderLinePaint: android.graphics.Paint,
    val forecastLeaderLinePaint: android.graphics.Paint,
    val dotPaint: android.graphics.Paint,
    /**
     * Italic translucent-white paint for the ghost-line label ("at 6 PM → 69.4°"). Hoisted into
     * PaintSet so it isn't re-allocated (Typeface.create + Paint copy) every render in
     * placeGhostLineLabel.
     */
    val ghostLineLabelPaint: android.graphics.Paint,
)

data class FetchDotDebug(
    val observedAt: Long,
    val fetchDotX: Float?,
    val fetchY: Float? = null,
    val withinWindow: Boolean,
    val ageText: String? = null,
    val valueColor: Int? = null,
    val stalenessColor: Int? = null,
    val stalenessLabelY: Float? = null,
)

data class GhostLineDebug(
    val startX: Float,
    val startY: Float,
)

data class ActualLineDebug(
    val endX: Float?,
    val endY: Float?,
    val pointCount: Int,
    val anchoredToFetchDot: Boolean,
)

/**
 * Where the dominant-station label ("knuq 73.4° @ 5:15 pm") ended up, or why it was suppressed.
 *
 * Exists because that placement was previously observable only through a VERBOSE log line, so no
 * test could assert it — and none did. [reason] is load-bearing in assertions: a label that failed
 * to place at all trivially "does not overlap the nav arrow", so a placement test must require
 * `reason == "drawn"` before believing its own geometry check.
 */
data class DominantStationDebug(
    /** "drawn", "no_text", "too_few_hours", "span_too_wide", or "no_empty_band". */
    val reason: String,
    val text: String?,
    val box: RectF?,
    val centerX: Float?,
    val baselineY: Float?,
    /**
     * The nav-arrow rectangles this render actually reserved. Reported rather than left for the
     * caller to reconstruct: the plot the renderer vetoes against is
     * `(0, graphTop, widthPx, graphBottom)`, and a test that rebuilds it from the bitmap size
     * instead would assert against a band the renderer never used.
     */
    val navArrowBounds: List<RectF> = emptyList(),
)

data class ActualsSourceDebug(
    /** "drawn", "no_text", "too_few_hours", "span_too_wide", or "no_empty_band". */
    val reason: String,
    val text: String?,
    val box: RectF?,
    val centerX: Float?,
    val baselineY: Float?,
    /** The nav-arrow rectangles this render actually reserved. */
    val navArrowBounds: List<RectF> = emptyList(),
)

data class DayLabelPlacementDebug(
    val side: String,       // "LEFT" or "RIGHT"
    val dayText: String,
    val date: LocalDate,
    val x: Float,
    val y: Float,
    val placement: String,  // "TOP", "MIDDLE", "BOTTOM"
    val isToday: Boolean,
)

data class PointsDebug(
    val original: List<Pair<Float, Float>>,
    val forecast: List<Pair<Float, Float>>,
    val expected: List<Pair<Float, Float>>,
)

data class ValueLabelLayout(
    val x: Float,
    val y: Float,
    val bounds: RectF,
    val align: android.graphics.Paint.Align,
)

data class StalenessInitialLayout(
    val baselineY: Float,
    val bounds: RectF,
    val placeAbove: Boolean,
)

class RenderTimings {
    private val marks = mutableListOf<Pair<String, Long>>()
    fun mark(label: String) { marks.add(label to SystemClock.elapsedRealtime()) }
    fun log(widthPx: Int, heightPx: Int, hoursSize: Int, tag: String) {
        if (
            marks.size < 2 ||
            !android.util.Log.isLoggable(tag, android.util.Log.VERBOSE)
        ) {
            return
        }
        val parts = marks.zipWithNext().map { (a, b) -> "${a.first}=${b.second - a.second}ms" }
        android.util.Log.v(tag, "RENDER_BREAKDOWN size=${widthPx}x${heightPx} hours=$hoursSize ${parts.joinToString(" ")} total=${marks.last().second - marks.first().second}ms")
    }
}

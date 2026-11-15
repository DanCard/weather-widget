package com.weatherwidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.shared.util.HourSpan
import com.weatherwidget.shared.util.NoHourlyChecker
import com.weatherwidget.widget.handlers.NoHourlyDayClickCoordinator
import java.time.LocalDateTime

/**
 * "No hourly forecast for {day} — data ends {end}" drawn on an hourly graph (temperature,
 * precipitation, cloud) whose view of future hours the shown source has no data for — the graph
 * would otherwise be blank with no word (Fold 4, 2026-11-15; plans/261115-hourly-empty-window-message.md).
 * When to draw is :shared [NoHourlyChecker.emptyViewDay], as on desktop; this side words and paints it.
 * Not while a banner with the same news is up (a tap's or pan's): the notice follows once it clears.
 */
internal object HourlyEmptyNoticeRenderer {
    private const val TEXT_SIZE_DP = 15f
    private const val MIN_TEXT_SIZE_DP = 10f
    private const val MAX_WIDTH_FRACTION = 0.86f
    private const val LINE_GAP_DP = 4f
    private const val TEXT_ARGB = 0xFFB8B8B8.toInt()
    private const val SEPARATOR = " — "

    /**
     * The notice for this paint, or null when the view draws something, starts before the current
     * hour, or a banner speaks for it ("Fetching…", or this same text).
     */
    suspend fun message(
        context: Context,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        hourlyForecasts: List<HourlyForecastEntity>,
        sourceId: String,
        view: HourSpan,
        now: LocalDateTime,
        lat: Double,
        lon: Double,
    ): String? {
        val day = NoHourlyChecker.emptyViewDay(
            hourlyForecasts.map { it.toHourlyForecast() }, view, setOf(sourceId), now,
        ) ?: return null
        val dayLabel = NoHourlyChecker.formatDayLabel(day)
        val pending = NoHourlyDayClickCoordinator.buildPendingMessage(context, dayLabel)
        val banner = stateManager.getActiveTransientMessage(appWidgetId)
        if (banner == pending) return null
        val database = WeatherDatabase.getDatabase(context)
        // NaN (no rows to take it from) → 0.0, which the lookup reads as "the latest weather's site".
        val endLabel = NoHourlyDayClickCoordinator.lastHourlyEndLabelForSource(
            database, stateManager, appWidgetId, lat.takeUnless { it.isNaN() } ?: 0.0, lon.takeUnless { it.isNaN() } ?: 0.0,
        )
        val message = NoHourlyDayClickCoordinator.buildNoDataMessage(context, dayLabel, endLabel)
        // The banner already says it: the notice waits until it clears (its expiry repaints).
        if (banner == message) return null
        database.appLogDao().log(
            NoHourlyChecker.EMPTY_NOTICE_TAG,
            NoHourlyChecker.emptyNoticeLine(sourceId, view, message, prefix = "widget=$appWidgetId"),
        )
        return message
    }

    /** Paints [text] centred on [bitmap]; split at the dash onto two lines, then shrunk, to fit. */
    fun draw(bitmap: Bitmap, text: String, density: Float) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_ARGB
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val maxWidth = bitmap.width * MAX_WIDTH_FRACTION
        val lines = layoutLines(text, maxWidth, density) { line, size ->
            paint.textSize = size
            paint.measureText(line)
        }
        paint.textSize = lines.textSize
        val lineHeight = paint.fontSpacing + LINE_GAP_DP * density
        val blockHeight = lineHeight * (lines.lines.size - 1)
        val firstBaseline = bitmap.height / 2f - blockHeight / 2f - (paint.ascent() + paint.descent()) / 2f
        lines.lines.forEachIndexed { i, line ->
            canvas.drawText(line, bitmap.width / 2f, firstBaseline + i * lineHeight, paint)
        }
    }

    data class Lines(val lines: List<String>, val textSize: Float)

    /** One line if it fits at full size, else split at the dash; shrink the widest line to [maxWidth], not below the minimum. */
    fun layoutLines(text: String, maxWidth: Float, density: Float, measure: (String, Float) -> Float): Lines {
        val full = TEXT_SIZE_DP * density
        val min = MIN_TEXT_SIZE_DP * density
        if (measure(text, full) <= maxWidth) return Lines(listOf(text), full)
        val lines = text.indexOf(SEPARATOR).takeIf { it > 0 }?.let { at ->
            listOf(text.substring(0, at), text.substring(at + SEPARATOR.length).replaceFirstChar { it.uppercase() })
        } ?: listOf(text)
        val widest = lines.maxOf { measure(it, full) }
        val size = if (widest <= maxWidth) full else (full * maxWidth / widest).coerceAtLeast(min)
        return Lines(lines, size)
    }
}

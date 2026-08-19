package com.weatherwidget.widget

import android.content.SharedPreferences
import android.util.Log
import com.weatherwidget.shared.graph.HourlyZoomRules
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal data class WidgetPresentationState(
    val dateOffset: Int,
    val viewMode: ViewMode,
    val hourlyOffset: Int,
    val zoom: ZoomStage,
    val graphAnchorMs: Long?,
)

/**
 * Owns all per-widget navigation, mode, zoom, transient banner, and last-render preferences.
 *
 * [narrowSpanHours] supplies the app-wide NARROW span so navigation can resolve a [ZoomWindow]; it
 * is a lambda rather than a value because the setting can change while widgets are live.
 * [multiDayZoomEnabled] is a lambda for the same reason, and gates both the tap cycle and the
 * coercion of already-persisted state — see [decodeZoom].
 */
internal class WidgetPresentationStateStore(
    private val prefs: SharedPreferences,
    private val narrowSpanHours: () -> Int = { HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS },
    private val multiDayZoomEnabled: () -> Boolean = { false },
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    fun state(widgetId: Int): WidgetPresentationState {
        val values = prefs.all
        val modeKey = viewModeKey(widgetId)
        val zoomKey = zoomKey(widgetId)
        val rawMode = values[modeKey]
        val rawZoom = values[zoomKey]
        val mode = decodeViewMode(rawMode)
        val zoom = decodeZoom(rawZoom)

        val editor = prefs.edit()
        var needsNormalization = false
        if (rawMode != null && rawMode != mode.name) {
            editor.putString(modeKey, mode.name)
            needsNormalization = true
        }
        if (rawZoom != null && rawZoom != zoom.name) {
            editor.putString(zoomKey, zoom.name)
            needsNormalization = true
        }
        if (needsNormalization) editor.apply()

        return WidgetPresentationState(
            dateOffset = (values[dateOffsetKey(widgetId)] as? Number)
                ?.toInt()
                ?.coerceIn(WidgetStateManager.MIN_DATE_OFFSET, WidgetStateManager.MAX_DATE_OFFSET)
                ?: 0,
            viewMode = mode,
            hourlyOffset = (values[hourlyOffsetKey(widgetId)] as? Number)
                ?.toInt()
                ?.coerceIn(WidgetStateManager.MIN_HOURLY_OFFSET, WidgetStateManager.MAX_HOURLY_OFFSET)
                ?: 0,
            zoom = zoom,
            graphAnchorMs = (values[graphAnchorKey(widgetId)] as? Number)?.toLong(),
        )
    }

    /**
     * Advances and returns the per-widget header-label swap counter.
     *
     * Drives which of the header date / "from yest" caption survives when they cannot both fit
     * (see `DailyForecastHeaderRenderer.resolveHeaderContention`). Per-widget so two widgets do not
     * lock in step, persisted so the alternation survives process death, and advanced once per
     * daily render — the widget repaints on nav taps, source toggles, unlock and each UI tick, so
     * both values stay reachable rather than one being permanently starved.
     */
    fun nextHeaderLabelSwap(widgetId: Int): Int {
        val key = headerLabelSwapKey(widgetId)
        val next = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, next).apply()
        return next
    }

    fun dateOffset(widgetId: Int): Int =
        prefs.getInt(dateOffsetKey(widgetId), 0)
            .coerceIn(WidgetStateManager.MIN_DATE_OFFSET, WidgetStateManager.MAX_DATE_OFFSET)

    fun setDateOffset(widgetId: Int, offset: Int) {
        prefs.edit()
            .putInt(
                dateOffsetKey(widgetId),
                offset.coerceIn(WidgetStateManager.MIN_DATE_OFFSET, WidgetStateManager.MAX_DATE_OFFSET),
            )
            .apply()
    }

    fun navigateDate(widgetId: Int, delta: Int): Int {
        val next = (dateOffset(widgetId) + delta)
            .coerceIn(WidgetStateManager.MIN_DATE_OFFSET, WidgetStateManager.MAX_DATE_OFFSET)
        setDateOffset(widgetId, next)
        return next
    }

    fun viewMode(widgetId: Int): ViewMode {
        val key = viewModeKey(widgetId)
        val raw = prefs.all[key]
        val decoded = decodeViewMode(raw)
        if (raw != null && raw != decoded.name) prefs.edit().putString(key, decoded.name).apply()
        return decoded
    }

    fun setViewMode(widgetId: Int, mode: ViewMode) {
        prefs.edit().putString(viewModeKey(widgetId), mode.name).apply()
    }

    fun toggleViewMode(widgetId: Int): ViewMode {
        val current = viewMode(widgetId)
        val newMode = if (current == ViewMode.DAILY) ViewMode.TEMPERATURE else ViewMode.DAILY
        val editor = prefs.edit().putString(viewModeKey(widgetId), newMode.name)
        when {
            newMode == ViewMode.TEMPERATURE && current == ViewMode.DAILY ->
                putHourlyPosition(editor, widgetId, 0, ZoomStage.WIDE)
            newMode == ViewMode.DAILY ->
                editor.putString(zoomKey(widgetId), ZoomStage.WIDE.name)
        }
        editor.apply()
        return newMode
    }

    fun togglePrecipitationMode(widgetId: Int): ViewMode {
        val current = viewMode(widgetId)
        val newMode = if (current == ViewMode.PRECIPITATION) ViewMode.DAILY else ViewMode.PRECIPITATION
        val editor = prefs.edit().putString(viewModeKey(widgetId), newMode.name)
        when {
            newMode == ViewMode.PRECIPITATION && current == ViewMode.DAILY ->
                putHourlyPosition(editor, widgetId, 0, ZoomStage.WIDE)
            newMode == ViewMode.DAILY ->
                editor.putString(zoomKey(widgetId), ZoomStage.WIDE.name)
        }
        editor.apply()
        return newMode
    }

    fun toggleCloudCoverMode(widgetId: Int): ViewMode {
        val current = viewMode(widgetId)
        val newMode = if (current == ViewMode.CLOUD_COVER) ViewMode.TEMPERATURE else ViewMode.CLOUD_COVER
        val editor = prefs.edit().putString(viewModeKey(widgetId), newMode.name)
        if (newMode == ViewMode.CLOUD_COVER && current == ViewMode.DAILY) {
            putHourlyPosition(editor, widgetId, 0, ZoomStage.WIDE)
        }
        editor.apply()
        return newMode
    }

    fun hourlyOffset(widgetId: Int): Int =
        prefs.getInt(hourlyOffsetKey(widgetId), 0)
            .coerceIn(WidgetStateManager.MIN_HOURLY_OFFSET, WidgetStateManager.MAX_HOURLY_OFFSET)

    fun setHourlyOffset(widgetId: Int, offset: Int) {
        val clamped = offset.coerceIn(
            WidgetStateManager.MIN_HOURLY_OFFSET,
            WidgetStateManager.MAX_HOURLY_OFFSET,
        )
        val editor = prefs.edit()
        putHourlyPosition(editor, widgetId, clamped, null)
        editor.apply()
    }

    fun navigateHourly(widgetId: Int, direction: Int): Int {
        val current = state(widgetId)
        val navJump = current.zoom.window(narrowSpanHours()).navJump
        val next = (current.hourlyOffset + direction * navJump)
            .coerceIn(WidgetStateManager.MIN_HOURLY_OFFSET, WidgetStateManager.MAX_HOURLY_OFFSET)
        setHourlyOffset(widgetId, next)
        return next
    }

    fun resolveHourlyCenterTime(
        widgetId: Int,
        now: LocalDateTime,
        zoom: ZoomWindow,
    ): LocalDateTime {
        val state = state(widgetId)
        val offset = state.hourlyOffset
        val liveCenter = now.plusHours(offset.toLong())
        val includesNow = offset.toLong() in -zoom.forwardHours..zoom.backHours
        val anchorMs = state.graphAnchorMs
        Log.v(
            TAG,
            "HOURLY_CENTER_TRACE: widget=$widgetId offset=$offset zoom=${zoom.stage} back=${zoom.backHours} " +
                "fwd=${zoom.forwardHours} includesNow=$includesNow hasAnchor=${anchorMs != null} " +
                "branch=${if (includesNow || anchorMs == null) "liveCenter" else "anchor"}",
        )
        if (includesNow || anchorMs == null) return liveCenter
        return Instant.ofEpochMilli(anchorMs).atZone(zoneId()).toLocalDateTime()
    }

    fun zoom(widgetId: Int): ZoomStage {
        val key = zoomKey(widgetId)
        val raw = prefs.all[key]
        val decoded = decodeZoom(raw)
        if (raw != null && raw != decoded.name) prefs.edit().putString(key, decoded.name).apply()
        return decoded
    }

    fun setZoom(widgetId: Int, zoom: ZoomStage) {
        prefs.edit().putString(zoomKey(widgetId), zoom.name).apply()
    }

    fun cycleZoom(widgetId: Int): ZoomStage {
        val next = zoom(widgetId).next(multiDayZoomEnabled())
        setZoom(widgetId, next)
        return next
    }

    fun setTransientMessage(widgetId: Int, message: String, expiresAtMs: Long) {
        prefs.edit()
            .putString(transientMessageKey(widgetId), message)
            .putLong(transientExpiresKey(widgetId), expiresAtMs)
            .apply()
    }

    fun activeTransientMessage(widgetId: Int, nowMs: Long = clock.millis()): String? {
        val expiresAt = prefs.getLong(transientExpiresKey(widgetId), 0L)
        if (expiresAt <= 0L || nowMs >= expiresAt) {
            if (expiresAt != 0L) clearTransientMessage(widgetId)
            return null
        }
        return prefs.getString(transientMessageKey(widgetId), null)
    }

    fun clearTransientMessage(widgetId: Int) {
        prefs.edit()
            .remove(transientMessageKey(widgetId))
            .remove(transientExpiresKey(widgetId))
            .apply()
    }

    fun hasTransientMessagePending(widgetId: Int, graceMs: Long, nowMs: Long = clock.millis()): Boolean {
        val expiresAt = prefs.getLong(transientExpiresKey(widgetId), 0L)
        return expiresAt != 0L && nowMs < expiresAt + graceMs
    }

    fun wasRainShownToday(widgetId: Int, today: String): Boolean =
        prefs.getString(rainShownKey(widgetId), null) == today

    fun markRainShown(widgetId: Int, today: String) {
        prefs.edit().putString(rainShownKey(widgetId), today).apply()
    }

    fun lastGraphRender(widgetId: Int): WidgetStateManager.LastGraphRenderState? {
        val msKey = lastGraphRenderKey(widgetId)
        if (!prefs.contains(msKey)) return null
        val watermarkKey = lastDataWatermarkKey(widgetId)
        return WidgetStateManager.LastGraphRenderState(
            renderMs = prefs.getLong(msKey, 0L),
            displayedTemp = prefs.getString(lastDisplayedTempKey(widgetId), null),
            // contains(), not a 0L default: a render that predates watermark tracking must read back
            // as null (force one rebuild), not as ObservationWatermark.NONE (never rebuild).
            dataWatermarkMs = if (prefs.contains(watermarkKey)) prefs.getLong(watermarkKey, 0L) else null,
        )
    }

    fun setLastGraphRender(widgetId: Int, state: WidgetStateManager.LastGraphRenderState) {
        val editor = prefs.edit()
            .putLong(lastGraphRenderKey(widgetId), state.renderMs)
            .putString(lastDisplayedTempKey(widgetId), state.displayedTemp)
        val watermarkKey = lastDataWatermarkKey(widgetId)
        if (state.dataWatermarkMs != null) {
            editor.putLong(watermarkKey, state.dataWatermarkMs)
        } else {
            editor.remove(watermarkKey)
        }
        editor.apply()
    }

    fun clearWidget(widgetId: Int, editor: SharedPreferences.Editor) {
        editor
            .remove(dateOffsetKey(widgetId))
            .remove(viewModeKey(widgetId))
            .remove(hourlyOffsetKey(widgetId))
            .remove(graphAnchorKey(widgetId))
            .remove(rainShownKey(widgetId))
            .remove(zoomKey(widgetId))
            .remove(transientMessageKey(widgetId))
            .remove(transientExpiresKey(widgetId))
            .remove(lastGraphRenderKey(widgetId))
            .remove(lastDisplayedTempKey(widgetId))
            .remove(lastDataWatermarkKey(widgetId))
            .remove("$KEY_DAILY_COLUMN_COUNT_PREFIX$widgetId")
            .remove("widget_single_day_epoch_$widgetId")
    }

    private fun putHourlyPosition(
        editor: SharedPreferences.Editor,
        widgetId: Int,
        offset: Int,
        zoom: ZoomStage?,
    ) {
        val anchorMs = clock.instant().plusSeconds(offset.toLong() * 60L * 60L).toEpochMilli()
        editor
            .putInt(hourlyOffsetKey(widgetId), offset)
            .putLong(graphAnchorKey(widgetId), anchorMs)
        zoom?.let { editor.putString(zoomKey(widgetId), it.name) }
    }

    private fun decodeViewMode(raw: Any?): ViewMode =
        when (raw) {
            is String -> ViewMode.entries.find { it.name == raw }
            is Number -> ViewMode.entries.getOrNull(raw.toInt())
            else -> null
        } ?: ViewMode.DAILY

    /**
     * Decodes a persisted stage, then coerces it against the current 2-day setting so a widget can
     * never sit on a stage the tap cycle cannot reach (see [ZoomStage.resolve]).
     *
     * Two decode notes worth keeping:
     * - Legacy state written as the string `"THREE_DAY"` no longer matches any entry and falls
     *   through to WIDE. That is the intended migration, not a gap.
     * - The *ordinal* branch still maps `2` onto TWO_DAY, which is why [ZoomStage]'s declaration
     *   order is load-bearing — and why the coercion below matters: without it, very old
     *   ordinal-encoded state could restore a stage the user has disabled.
     *
     * Callers that normalize will persist the coerced value, so disabling the setting and
     * re-enabling it returns the widget to WIDE rather than its old multi-day position.
     */
    private fun decodeZoom(raw: Any?): ZoomStage {
        val decoded = when (raw) {
            is String -> ZoomStage.entries.find { it.name == raw }
            is Number -> ZoomStage.entries.getOrNull(raw.toInt())
            else -> null
        } ?: ZoomStage.WIDE
        return ZoomStage.resolve(decoded, multiDayZoomEnabled())
    }

    private fun headerLabelSwapKey(widgetId: Int) = "$KEY_HEADER_LABEL_SWAP_PREFIX$widgetId"
    private fun dateOffsetKey(widgetId: Int) = "$KEY_DATE_OFFSET_PREFIX$widgetId"
    private fun viewModeKey(widgetId: Int) = "$KEY_VIEW_MODE_PREFIX$widgetId"
    private fun hourlyOffsetKey(widgetId: Int) = "$KEY_HOURLY_OFFSET_PREFIX$widgetId"
    private fun graphAnchorKey(widgetId: Int) = "$KEY_GRAPH_ANCHOR_MS_PREFIX$widgetId"
    private fun rainShownKey(widgetId: Int) = "$KEY_RAIN_SHOWN_DATE_PREFIX$widgetId"
    private fun zoomKey(widgetId: Int) = "$KEY_ZOOM_LEVEL_PREFIX$widgetId"
    private fun transientMessageKey(widgetId: Int) = "$KEY_TRANSIENT_MESSAGE_PREFIX$widgetId"
    private fun transientExpiresKey(widgetId: Int) = "$KEY_TRANSIENT_MESSAGE_EXPIRES_PREFIX$widgetId"
    private fun lastGraphRenderKey(widgetId: Int) = "$KEY_LAST_GRAPH_RENDER_MS_PREFIX$widgetId"
    private fun lastDisplayedTempKey(widgetId: Int) = "$KEY_LAST_DISPLAYED_TEMP_PREFIX$widgetId"
    private fun lastDataWatermarkKey(widgetId: Int) = "$KEY_LAST_DATA_WATERMARK_PREFIX$widgetId"

    private companion object {
        const val TAG = "WidgetStateManager"
        const val KEY_DATE_OFFSET_PREFIX = "widget_date_offset_"
        const val KEY_VIEW_MODE_PREFIX = "widget_view_mode_"
        const val KEY_HOURLY_OFFSET_PREFIX = "widget_hourly_offset_"
        const val KEY_GRAPH_ANCHOR_MS_PREFIX = "widget_graph_anchor_ms_"
        const val KEY_RAIN_SHOWN_DATE_PREFIX = "widget_rain_shown_date_"
        const val KEY_HEADER_LABEL_SWAP_PREFIX = "widget_header_label_swap_"
        const val KEY_ZOOM_LEVEL_PREFIX = "widget_zoom_level_"
        const val KEY_TRANSIENT_MESSAGE_PREFIX = "widget_transient_msg_"
        const val KEY_TRANSIENT_MESSAGE_EXPIRES_PREFIX = "widget_transient_msg_expires_"
        const val KEY_DAILY_COLUMN_COUNT_PREFIX = "widget_daily_col_count_"
        const val KEY_LAST_GRAPH_RENDER_MS_PREFIX = "widget_last_graph_render_ms_"
        const val KEY_LAST_DISPLAYED_TEMP_PREFIX = "widget_last_displayed_temp_"
        const val KEY_LAST_DATA_WATERMARK_PREFIX = "widget_last_data_watermark_"
    }
}

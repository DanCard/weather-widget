package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.shared.util.RefreshOutcome
import com.weatherwidget.ui.ForecastHistoryActivity
import kotlinx.coroutines.CancellationException

/**
 * The widget banner when a sync that told the user it was queued ends: "Tap to refresh"
 * and any refresh a tap started (user, 2026-11-01). Other syncs stay silent. A sync can finish without its
 * source answering, so the source's own last success decides what the banner says.
 */
internal object SyncDoneNotice {
    const val WIDGET_TAP_REASON = "widget_tap_to_refresh"
    const val TAP_REASON = "viewing_forecast_stale"

    /** A source switch's stale refresh ([WidgetIntentActionHandler]); a browsing tap's starts `stale_on_`. */
    private val TAP_STARTED = setOf(TAP_REASON, "toggle_api_stale", "reset_source_stale")

    fun owed(reason: String): Boolean =
        reason == WIDGET_TAP_REASON || reason in TAP_STARTED || reason.startsWith("stale_on_")

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))

    /** How a fetch of [source] started at [startedAtMs] ended, from its recorded quota and rate-limit state. */
    fun outcome(stateManager: WidgetStateManager, source: WeatherSource, ok: Boolean, startedAtMs: Long): RefreshOutcome {
        val nowMs = System.currentTimeMillis()
        return RefreshOutcome.of(
            ok = ok,
            hourlyBlockedUntilMs = stateManager.getProductQuota(source, ForecastProduct.HOURLY, nowMs)?.untilMs,
            dailyBlockedUntilMs = stateManager.getProductQuota(source, ForecastProduct.DAILY, nowMs)?.untilMs,
            partialRateLimitAtMs = stateManager.getRateLimit(source, nowMs)?.takeIf { it.partial }?.atMs,
            startedAtMs = startedAtMs,
        )
    }

    suspend fun announce(context: Context, stateManager: WidgetStateManager, input: WorkInput, syncOk: Boolean) {
        if (!owed(input.currentTempReason)) return
        try {
            val source = input.targetSourceId?.let(WeatherSource::fromId) ?: stateManager.getPrimarySource()
            val answered = syncOk && ActiveLocationResolver.current(context)?.let { (lat, lon) ->
                FetchMetadata.getLastForecastSourceSuccessTime(context, source.id, lat, lon) >= input.requestedAtMs
            } == true
            val outcome = outcome(stateManager, source, answered, input.requestedAtMs)
            RefreshToast.showOutcome(
                context,
                input.currentTempReason,
                outcome,
                ForecastHistoryActivity.refreshOutcomeText(context, outcome, source.shortDisplayName),
                input.targetSourceId?.let { target ->
                    widgetIds(context).filter { stateManager.getCurrentDisplaySource(it).id == target }.toIntArray()
                } ?: widgetIds(context),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SyncDoneNotice", "Refresh-done notice failed", e)
        }
    }
}

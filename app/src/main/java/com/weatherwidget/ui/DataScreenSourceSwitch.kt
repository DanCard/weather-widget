package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.SourceViewRecorder
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.handlers.WidgetIntentActionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A data screen's source pill (Forecast Comparison, Stations) switches the widget's source the way
 * the API button does ([WidgetIntentActionHandler.switchFromScreen], the shared `SourceSwitchRunner`).
 * Desktop's windows do the same through `DesktopSourceSwitch`.
 * plans/261119-sync-data-screen-sources-with-main.md
 *
 * Runs on [scope] (the application IO scope), not the screen's: a back press right after the tap
 * must not cancel the persist, paint or count. A screen opened without a widget has no source to
 * switch, so only the view is counted, as Stations always did.
 */
internal object DataScreenSourceSwitch {
    private const val TAG = "DataScreenSourceSwitch"

    fun launch(
        appContext: Context,
        scope: CoroutineScope,
        appWidgetId: Int,
        repository: WeatherRepository?,
        kind: WidgetIntentActionHandler.SwitchKind,
        target: WeatherSource,
    ): Job = scope.launch {
        try {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                Log.d(TAG, "no widget: counting only trigger=${kind.trigger} source=${target.id}")
                SourceViewRecorder.record(appContext, target, ViewMode.DAILY, kind.trigger)
            } else {
                WidgetIntentActionHandler.switchFromScreen(appContext, appWidgetId, repository, kind, target)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "screen source switch failed widget=$appWidgetId trigger=${kind.trigger} source=${target.id}", e)
        }
    }
}

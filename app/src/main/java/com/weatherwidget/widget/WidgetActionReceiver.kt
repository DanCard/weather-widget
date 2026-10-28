package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Non-exported receiver for app-owned widget maintenance commands (such as ACTION_REFRESH)
 * and backward compatibility with legacy PendingIntents.
 */
@AndroidEntryPoint
class WidgetActionReceiver : BaseWidgetActionReceiver() {
    override val tag: String = TAG

    @VisibleForTesting
    override var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val actionHandlers: Map<String, (Context, Intent, Long) -> Unit> =
        interactionHandlers + (WidgetActions.ACTION_REFRESH to { context, intent, _ ->
            val pendingResult = goAsync()
            val finished = AtomicBoolean(false)
            fun finishOnce(reason: String) {
                if (finished.compareAndSet(false, true)) {
                    BroadcastAsyncRunner.finishPendingResultSafely(pendingResult, "$TAG:$reason")
                }
            }

            val requestedWidgetId =
                intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                ).takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
            val uiOnly = intent.getBooleanExtra(WidgetActions.EXTRA_UI_ONLY, false)
            val isPassive = intent.getBooleanExtra(WidgetActions.EXTRA_PASSIVE, false)
            val userInitiated = !uiOnly && !isPassive
            if (userInitiated) {
                com.weatherwidget.widget.handlers.WidgetInteractionCache.clear()
            }

            scope.launch {
                try {
                    WidgetRefreshCoordinator.refresh(
                        context = context,
                        uiOnly = uiOnly,
                        repository = repository,
                        requestedWidgetId = requestedWidgetId,
                        userInitiated = userInitiated,
                    )
                } catch (e: CancellationException) {
                    Log.d(TAG, "Refresh cancelled: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Refresh failed", e)
                } finally {
                    finishOnce("completed")
                }
            }

            // For passive / UI-only background refreshes (e.g. from ScreenOnReceiver or Settings),
            // release the broadcast transaction early so AMS does not queue user interaction taps.
            if (!userInitiated) {
                finishOnce("passive_dispatched")
            }
        })

    companion object {
        const val TAG = "WidgetActionReceiver"
    }
}

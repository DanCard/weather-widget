package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages clean settlement of widgets whose background updates were preempted by a user interaction.
 *
 * Preempted widgets are recorded and repainted from cache after a debounced delay (3s) once
 * user taps have settled.
 */
internal object WidgetPreemptionSettler {
    private const val TAG = "WidgetPreemptionSettler"
    const val DEFAULT_SETTLEMENT_DELAY_MS = 3000L

    @VisibleForTesting
    internal var settlementDelayMs: Long = DEFAULT_SETTLEMENT_DELAY_MS

    @VisibleForTesting
    internal var renderWidget: suspend (Context, Int, WeatherRepository?) -> Unit = { ctx, id, repo ->
        WidgetIntentRouter.renderWidgetFromCache(ctx, id, repo)
    }

    @VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val pendingSettlementWidgets = ConcurrentHashMap.newKeySet<Int>()
    private var settlementJob: Job? = null
    private val lock = Any()

    fun recordPreempted(
        context: Context,
        widgetIds: Collection<Int>,
        repository: WeatherRepository?,
    ) {
        if (widgetIds.isEmpty()) return
        pendingSettlementWidgets.addAll(widgetIds)
        WidgetStateManager(context).setPaintOwed(true)

        synchronized(lock) {
            settlementJob?.cancel()
            val appContext = context.applicationContext
            settlementJob = scope.launch {
                try {
                    delay(settlementDelayMs)
                    settle(appContext, repository)
                } catch (_: CancellationException) {
                    // Interaction extended; next tap or settlement will handle it
                }
            }
        }
    }

    suspend fun settle(context: Context, repository: WeatherRepository?) {
        val toSettle = synchronized(lock) {
            val list = pendingSettlementWidgets.toList()
            pendingSettlementWidgets.clear()
            list
        }
        if (toSettle.isEmpty()) return

        runCatching {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    WeatherDatabase.getDatabase(context).appLogDao().log(
                        "WIDGET_PREEMPTION_SETTLE",
                        "widgets=${toSettle.joinToString(",")}",
                        "INFO",
                    )
                }
            }
        }

        for (widgetId in toSettle) {
            try {
                renderWidget(context, widgetId, repository)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Preemption settlement render failed for widget $widgetId", e)
            }
        }
    }

    @VisibleForTesting
    internal fun pendingWidgets(): Set<Int> = pendingSettlementWidgets.toSet()

    @VisibleForTesting
    internal fun clearForTesting() {
        synchronized(lock) {
            settlementJob?.cancel()
            settlementJob = null
            pendingSettlementWidgets.clear()
            settlementDelayMs = DEFAULT_SETTLEMENT_DELAY_MS
            renderWidget = { ctx, id, repo -> WidgetIntentRouter.renderWidgetFromCache(ctx, id, repo) }
        }
    }
}

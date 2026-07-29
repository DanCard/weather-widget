package com.weatherwidget.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Exactly-once PendingResult lifetime management shared by widget broadcast receivers. */
internal object BroadcastAsyncRunner {
    const val WATCHDOG_MS = 8_000L

    fun launch(
        context: Context,
        pendingResult: BroadcastReceiver.PendingResult?,
        scope: CoroutineScope,
        caller: String,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        watchdogLogger: suspend (Context, String) -> Unit = ::logWatchdog,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val finished = AtomicBoolean(false)
        fun finishOnce(reason: String) {
            if (finished.compareAndSet(false, true)) {
                finishPendingResultSafely(pendingResult, "$caller:$reason")
            }
        }

        val job =
            scope.launch(start = start) {
                val watchdog =
                    launch {
                        delay(WATCHDOG_MS)
                        Log.w(
                            TAG,
                            "$caller watchdog fired after ${WATCHDOG_MS}ms; releasing broadcast",
                        )
                        // Release first. A blocked Room open/write must not consume the ANR margin.
                        finishOnce("watchdog")
                        try {
                            watchdogLogger(
                                context,
                                "$caller watchdog fired after ${WATCHDOG_MS}ms; broadcast released, " +
                                    "work continues",
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "$caller failed to log watchdog event", e)
                        }
                    }
                try {
                    block()
                } catch (e: CancellationException) {
                    Log.d(TAG, "$caller cancelled: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "$caller failed", e)
                } finally {
                    watchdog.cancel()
                    finishOnce("completed")
                }
            }
        // A LAZY action can be cancelled by deletion before its body starts; still release goAsync.
        job.invokeOnCompletion {
            finishOnce("job_completion")
        }
        return job
    }

    fun finishPendingResultSafely(
        pendingResult: BroadcastReceiver.PendingResult?,
        caller: String,
    ) {
        if (pendingResult == null) {
            Log.w(TAG, "$caller: goAsync returned null; no pending result to finish")
            return
        }
        try {
            pendingResult.finish()
        } catch (e: Exception) {
            Log.e(TAG, "$caller: failed to finish pending result", e)
        }
    }

    private suspend fun logWatchdog(
        context: Context,
        message: String,
    ) {
        WeatherDatabase.getDatabase(context).appLogDao().log(
            "CLICK_WATCHDOG",
            message,
            "WARN",
        )
    }

    private const val TAG = "BroadcastAsyncRunner"
}

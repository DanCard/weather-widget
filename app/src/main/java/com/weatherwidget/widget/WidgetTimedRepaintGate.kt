package com.weatherwidget.widget

import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.util.TimedRepaintGate

/** The process's [TimedRepaintGate]: the elapsed clock, `app_logs`, and which worker reasons are timed. */
internal object WidgetTimedRepaintGate {
    private const val TAG = "WidgetTimedRepaintGate"

    const val REASON_UI_TICK = "ui_update_alarm"
    const val REASON_NOTICE_EXPIRY = "clear_refresh_notice"

    val gate = TimedRepaintGate(SystemClock::elapsedRealtime)

    fun triggerFor(workerReason: String?): TimedRepaintGate.Trigger? = when (workerReason) {
        REASON_UI_TICK -> TimedRepaintGate.Trigger.UI_TICK
        REASON_NOTICE_EXPIRY -> TimedRepaintGate.Trigger.NOTICE_EXPIRY
        else -> null
    }

    suspend fun admit(appLogDao: AppLogDao, trigger: TimedRepaintGate.Trigger): Boolean =
        gate.admit(trigger) { tag, message ->
            Log.i(TAG, "$tag $message")
            appLogDao.log(tag, message, "INFO")
        }
}

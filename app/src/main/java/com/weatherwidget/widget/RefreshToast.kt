package com.weatherwidget.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.util.RefreshNotice
import com.weatherwidget.shared.util.RefreshOutcome
import java.util.Date

/**
 * The three-second notice after the user asks for a refresh: what it queued, or why nothing
 * ([RefreshNotice]; plans/261015-refresh-queued-toast-all-surfaces.md). Refresh requests (Refresh
 * buttons, "Tap to refresh"), any refresh a tap started, plus enabling a new primary source and an actuals-provider pick — never
 * a background fetch or a browsing tap (plans/261016-refresh-notice-only-on-refresh-requests.md).
 *
 * App screens get a Toast, `LENGTH_SHORT` (2 s). Widget taps ([widgetIds] set) get the widget's own message banner instead
 * (user, 2026-10-15): Android drops a toast from a background app without notification permission
 * (`NotificationService: Suppressing toast … by user request`, emulator and Fold 4), and a widget tap
 * is background. The banner never covers a different message already up ("Getting weather for…").
 *
 * At most one per [RefreshNotice.DURATION_MS] ([RefreshNotice.shouldShow]). Every call writes
 * `REFRESH_TOAST`, shown or suppressed: a notice alone leaves no trace after the fact. Source changes
 * and browsing taps (arrows, zoom, view switches) that start no refresh show nothing ([logSilent]).
 */
object RefreshToast {
    @VisibleForTesting
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    private var gate = RefreshNotice.Gate { clock() }

    @VisibleForTesting
    internal fun resetForTesting() {
        clock = SystemClock::elapsedRealtime
        gate = RefreshNotice.Gate { clock() }
    }

    suspend fun show(context: Context, origin: String, notice: RefreshNotice, widgetIds: IntArray? = null) {
        val appContext = context.applicationContext
        val shown = gate.admit()
        val outcome = when {
            !shown -> "suppressed"
            widgetIds != null -> showOnWidgets(appContext, text(appContext, notice), widgetIds)
            else -> {
                val text = text(appContext, notice)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
                }
                "shown"
            }
        }
        WeatherDatabase.getDatabase(appContext).appLogDao().log(RefreshNotice.TAG, RefreshNotice.line(origin, notice, outcome), "INFO")
    }

    /**
     * How a refresh ended: a Toast, or the widget banner when [widgetIds] is set. It answers the same
     * request as the "queued" notice, so it replaces that one rather than being suppressed, and restarts
     * the window — as desktop's `RefreshNoticeState.showOutcome`.
     */
    suspend fun showOutcome(context: Context, origin: String, outcome: RefreshOutcome, text: String, widgetIds: IntArray? = null) {
        val appContext = context.applicationContext
        gate.restart()
        val shown = if (widgetIds != null) {
            showOnWidgets(appContext, text, widgetIds, replaceable = { isQueuedText(appContext, it) })
        } else {
            Handler(Looper.getMainLooper()).post { Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show() }
            "shown"
        }
        WeatherDatabase.getDatabase(appContext).appLogDao()
            .log(RefreshNotice.TAG, RefreshNotice.outcomeLine(origin, outcome, shown), "INFO")
    }

    /** Whether [message] is a "queued" notice, for any source: an ending may take its place. */
    private fun isQueuedText(context: Context, message: String): Boolean =
        RefreshNotice.Kind.entries.any { message.endsWith(text(context, RefreshNotice.Queued(it))) }

    /**
     * The `REFRESH_TOAST` row alone, for actions that announce nothing: source changes (toggle, the
     * daily home button) and browsing taps, which the user makes to look, not to refresh (user,
     * 2026-10-15 / 2026-10-16).
     */
    suspend fun logSilent(context: Context, origin: String, notice: RefreshNotice) {
        WeatherDatabase.getDatabase(context.applicationContext).appLogDao()
            .log(RefreshNotice.TAG, RefreshNotice.line(origin, notice, "silent"), "INFO")
    }

    /** "shown banner=N", or "suppressed busy" when every widget already shows another message. */
    private fun showOnWidgets(context: Context, text: String, widgetIds: IntArray, replaceable: (String) -> Boolean = { false }): String {
        val stateManager = WidgetStateManager(context)
        val free = widgetIds.filter { id -> stateManager.getActiveTransientMessage(id).let { it == null || it == text || replaceable(it) } }
        if (free.isEmpty()) return "suppressed busy"
        val ids = free.toIntArray()
        FetchBanner.show(context, text, ids, reason = WidgetTimedRepaintGate.REASON_NOTICE_EXPIRY, showMs = RefreshNotice.DURATION_MS)
        // Hidden explicitly: the delayed repaint does not hide an expired banner (visibility is sticky).
        Handler(Looper.getMainLooper()).postDelayed({ FetchBanner.expire(context, text, ids) }, RefreshNotice.DURATION_MS)
        return "shown banner=${ids.size}"
    }

    fun text(context: Context, notice: RefreshNotice): String = when (notice) {
        is RefreshNotice.Queued -> RefreshNotice.withSource(
            notice,
            context.getString(
                when (notice.kind) {
                    RefreshNotice.Kind.FORECAST -> R.string.refresh_queued_forecast
                    RefreshNotice.Kind.OBSERVATIONS -> R.string.refresh_queued_observations
                    RefreshNotice.Kind.BOTH -> R.string.refresh_queued_both
                },
            ),
        )
        is RefreshNotice.NotQueued -> when (notice.reason) {
            RefreshNotice.Reason.UP_TO_DATE -> context.getString(
                if (notice.subject == RefreshNotice.Kind.OBSERVATIONS) {
                    R.string.refresh_up_to_date_observations
                } else {
                    R.string.refresh_up_to_date_forecast
                },
                notice.sourceName.orEmpty(),
                RefreshNotice.formatAge(notice.ageMs ?: 0L),
            ).trim()
            RefreshNotice.Reason.ALREADY_QUEUED -> context.getString(R.string.refresh_already_queued)
            RefreshNotice.Reason.ALL_FRESH -> context.getString(R.string.refresh_all_fresh)
            RefreshNotice.Reason.RATE_LIMITED -> {
                val x = notice.elapsedMinutes ?: 0
                val y = notice.remainingMinutes ?: 1
                val xStr = com.weatherwidget.shared.util.UserRefreshThrottle.formatMinutes(x)
                val yStr = com.weatherwidget.shared.util.UserRefreshThrottle.formatMinutes(y)
                context.getString(R.string.refresh_rate_limited, xStr, yStr)
            }
            RefreshNotice.Reason.IN_PROGRESS -> context.getString(R.string.refresh_in_progress)
            RefreshNotice.Reason.QUOTA -> context.getString(
                R.string.refresh_quota_blocked,
                notice.sourceName.orEmpty(),
                notice.untilMs?.let { android.text.format.DateFormat.getTimeFormat(context).format(Date(it)) }.orEmpty(),
            )
            RefreshNotice.Reason.NO_LOCATION -> context.getString(R.string.obs_no_location_to_refresh)
        }
    }
}

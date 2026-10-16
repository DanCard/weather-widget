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
import java.util.Date

/**
 * The four-second notice after the user asks for a refresh: what it queued, or why nothing
 * ([RefreshNotice]; plans/261015-refresh-queued-toast-all-surfaces.md). Refresh requests (Refresh
 * buttons, "Tap to refresh"), plus enabling a new primary source and an actuals-provider pick — never
 * a background fetch or a browsing tap (plans/261016-refresh-notice-only-on-refresh-requests.md).
 *
 * App screens get a Toast, `LENGTH_LONG` (3.5 s, the longest Android allows). Widget taps ([widgetIds] set) get the widget's own message banner instead
 * (user, 2026-10-15): Android drops a toast from a background app without notification permission
 * (`NotificationService: Suppressing toast … by user request`, emulator and Fold 4), and a widget tap
 * is background. The banner never covers a different message already up ("Getting weather for…").
 *
 * At most one per [RefreshNotice.DURATION_MS] ([RefreshNotice.shouldShow]). Every call writes
 * `REFRESH_TOAST`, shown or suppressed: a notice alone leaves no trace after the fact. Source changes
 * and browsing taps (arrows, zoom, view switches) show nothing ([logSilent]).
 */
object RefreshToast {
    @Volatile
    private var lastShownElapsedMs: Long? = null

    @VisibleForTesting
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    @VisibleForTesting
    internal fun resetForTesting() {
        lastShownElapsedMs = null
        clock = SystemClock::elapsedRealtime
    }

    suspend fun show(context: Context, origin: String, notice: RefreshNotice, widgetIds: IntArray? = null) {
        val appContext = context.applicationContext
        val now = clock()
        val shown = synchronized(this) {
            RefreshNotice.shouldShow(lastShownElapsedMs, now).also { if (it) lastShownElapsedMs = now }
        }
        val outcome = when {
            !shown -> "suppressed"
            widgetIds != null -> showOnWidgets(appContext, text(appContext, notice), widgetIds)
            else -> {
                val text = text(appContext, notice)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
                }
                "shown"
            }
        }
        WeatherDatabase.getDatabase(appContext).appLogDao().log(
            "REFRESH_TOAST",
            "origin=$origin ${RefreshNotice.logDetail(notice)} $outcome",
            "INFO",
        )
    }

    /**
     * The `REFRESH_TOAST` row alone, for actions that announce nothing: source changes (toggle, the
     * daily home button) and browsing taps, which the user makes to look, not to refresh (user,
     * 2026-10-15 / 2026-10-16).
     */
    suspend fun logSilent(context: Context, origin: String, notice: RefreshNotice) {
        WeatherDatabase.getDatabase(context.applicationContext).appLogDao().log(
            "REFRESH_TOAST",
            "origin=$origin ${RefreshNotice.logDetail(notice)} silent",
            "INFO",
        )
    }

    /** "shown banner=N", or "suppressed busy" when every widget already shows another message. */
    private fun showOnWidgets(context: Context, text: String, widgetIds: IntArray): String {
        val stateManager = WidgetStateManager(context)
        val free = widgetIds.filter { id -> stateManager.getActiveTransientMessage(id).let { it == null || it == text } }
        if (free.isEmpty()) return "suppressed busy"
        val ids = free.toIntArray()
        FetchBanner.show(context, text, ids, reason = "clear_refresh_notice", showMs = RefreshNotice.DURATION_MS)
        // Hidden explicitly: the delayed repaint does not hide an expired banner (visibility is sticky).
        Handler(Looper.getMainLooper()).postDelayed({ FetchBanner.expire(context, text, ids) }, RefreshNotice.DURATION_MS)
        return "shown banner=${ids.size}"
    }

    fun text(context: Context, notice: RefreshNotice): String = when (notice) {
        is RefreshNotice.Queued -> context.getString(
            when (notice.kind) {
                RefreshNotice.Kind.FORECAST -> R.string.refresh_queued_forecast
                RefreshNotice.Kind.OBSERVATIONS -> R.string.refresh_queued_observations
                RefreshNotice.Kind.BOTH -> R.string.refresh_queued_both
            },
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

package com.weatherwidget.widget.handlers

import android.content.Context
import com.weatherwidget.shared.util.FailureBannerStage
import com.weatherwidget.widget.WidgetWorkScheduler
import java.util.concurrent.ConcurrentHashMap

/**
 * A widget is a static bitmap, so a banner that shrinks at 8 s and fades at 24 s needs a repaint at
 * each boundary. Best-effort: a deferred or lost repaint only delays the change — every paint draws
 * the stage its own clock says ([com.weatherwidget.widget.GraphFailureWatermarkRenderer.stageFor]).
 */
internal object FailureBannerRepaint {
    /** Boundary already enqueued per widget, so the burst of paints in one stage enqueues it once. */
    private val scheduledAtMs = ConcurrentHashMap<Int, Long>()

    fun scheduleNextStage(
        context: Context,
        appWidgetId: Int,
        bannerSinceMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val delay = nextStageDelayMs(bannerSinceMs, nowMs) ?: return
        val target = nowMs + delay
        if (scheduledAtMs.put(appWidgetId, target) == target) return
        WidgetWorkScheduler.enqueueFailureBannerStageRepaint(context, appWidgetId, delay)
    }

    /** A late stage repaint still needs to paint; past this it has long since landed (or never will). */
    private const val STAGE_REPAINT_SLACK_MS = 60_000L

    /**
     * True while a stage repaint may still be due: views that skip UI-only repaints when nothing
     * time-dependent changed (the daily view) must paint through this window.
     */
    fun stageChangeMayBePending(bannerSinceMs: Long?, nowMs: Long): Boolean {
        bannerSinceMs ?: return false
        val age = nowMs - bannerSinceMs
        return age >= 0L && age < FailureBannerStage.FADED_AFTER_MS + STAGE_REPAINT_SLACK_MS
    }

    /** Time until the next stage boundary, or null when there is none left (or no anchor). */
    internal fun nextStageDelayMs(bannerSinceMs: Long?, nowMs: Long): Long? {
        bannerSinceMs ?: return null
        val age = nowMs - bannerSinceMs
        if (age < 0L) return null
        return FailureBannerStage.STAGE_CHANGE_DELAYS_MS.firstOrNull { it > age }?.let { it - age }
    }
}

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

    /**
     * [expiresAfterMs]: when the banner comes down on its own, measured like the stages — a rate-limit
     * banner at [com.weatherwidget.data.remote.RateLimitNotice.SHOW_MS] needs a repaint then too.
     */
    fun scheduleNextStage(
        context: Context,
        appWidgetId: Int,
        bannerSinceMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        expiresAfterMs: Long? = null,
    ) {
        val delay = nextStageDelayMs(bannerSinceMs, nowMs, expiresAfterMs) ?: return
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

    /** Time until the next stage boundary (or [expiresAfterMs]), or null when none is left (or no anchor). */
    internal fun nextStageDelayMs(bannerSinceMs: Long?, nowMs: Long, expiresAfterMs: Long? = null): Long? {
        bannerSinceMs ?: return null
        val age = nowMs - bannerSinceMs
        if (age < 0L) return null
        val boundaries = (FailureBannerStage.STAGE_CHANGE_DELAYS_MS.toList() + listOfNotNull(expiresAfterMs)).sorted()
        return boundaries.firstOrNull { it > age }?.let { it - age }
    }
}

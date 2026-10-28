package com.weatherwidget.widget

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks and manages active widget update jobs to prevent redundant parallel updates.
 * Allows canceling an existing update for a specific widget before starting a new one.
 *
 * Ownership split with WidgetActionJobRegistry: this registry covers provider-side (system
 * lifecycle) work — onUpdate startup, resize, and paint jobs — using replace-by-cancel semantics.
 * WidgetActionJobRegistry covers receiver-side user PendingIntents (nav, toggles, day clicks),
 * which are serialized by WidgetInteractionCoordinator's per-widget mutex and are only cancelled
 * wholesale on widget deletion. Both are cleaned up in WeatherWidgetProvider.onDeleted.
 */
object WidgetUpdateTracker {
    
    enum class JobType {
        UI_PAINT,        // Quick render from cache (onUpdate, package replaced)
        BACKGROUND_SYNC, // Heavy network fetch + render
        INTERACTION      // User initiated change (toggle API, nav)
    }

    private data class TrackedJob(val job: Job, val type: JobType)

    private val activeJobs = ConcurrentHashMap<Int, TrackedJob>()

    /**
     * Track a new update job for a specific widget.
     * Cancels any existing job for that widget ID before storing the new one,
     * UNLESS the new job is a background sync and the existing one is a UI paint.
     */
    fun trackJob(appWidgetId: Int, job: Job, type: JobType = JobType.UI_PAINT) {
        val existing = activeJobs[appWidgetId]
        
        // Strategy: Don't let background syncs cancel an active UI paint.
        // UI paints are fast and we want them to finish so the user sees something.
        // Background syncs will eventually overwrite the UI once they finish.
        val shouldCancel = when {
            existing == null -> false
            type == JobType.BACKGROUND_SYNC && existing.type == JobType.UI_PAINT -> false
            else -> true
        }

        if (shouldCancel) {
            existing?.job?.cancel()
            activeJobs[appWidgetId] = TrackedJob(job, type)
        } else if (existing == null) {
            activeJobs[appWidgetId] = TrackedJob(job, type)
        }
        
        // Remove job from map when it completes (normally or by cancellation)
        job.invokeOnCompletion { 
            activeJobs.remove(appWidgetId, TrackedJob(job, type))
        }
    }

    /**
     * Cancel any active update job for a specific widget.
     */
    fun cancelJob(appWidgetId: Int) {
        activeJobs.remove(appWidgetId)?.job?.cancel()
    }

    /**
     * Cancel active update jobs for all other widgets except [activeAppWidgetId].
     * Defaults to cancelling background syncs and UI paints so interaction on the active widget
     * has unobstructed access to device resources.
     *
     * @return List of widget IDs whose active jobs were cancelled.
     */
    fun cancelOtherJobs(
        activeAppWidgetId: Int,
        typesToCancel: Set<JobType> = setOf(JobType.UI_PAINT, JobType.BACKGROUND_SYNC),
    ): List<Int> {
        val cancelled = mutableListOf<Int>()
        for ((widgetId, tracked) in activeJobs) {
            if (widgetId != activeAppWidgetId && tracked.type in typesToCancel) {
                if (activeJobs.remove(widgetId, tracked)) {
                    tracked.job.cancel()
                    cancelled.add(widgetId)
                }
            }
        }
        return cancelled
    }

    /**
     * Query the currently active job type for a widget, if any.
     */
    fun getActiveJobType(appWidgetId: Int): JobType? = activeJobs[appWidgetId]?.type

    /**
     * Test helper to cancel all active jobs and reset tracker state.
     */
    internal fun clearForTesting() {
        for ((_, tracked) in activeJobs) {
            tracked.job.cancel()
        }
        activeJobs.clear()
    }
}

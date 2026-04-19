package com.weatherwidget.widget

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks and manages active widget update jobs to prevent redundant parallel updates.
 * Allows canceling an existing update for a specific widget before starting a new one.
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
}

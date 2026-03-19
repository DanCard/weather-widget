package com.weatherwidget.widget

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks and manages active widget update jobs to prevent redundant parallel updates.
 * Allows canceling an existing update for a specific widget before starting a new one.
 */
object WidgetUpdateTracker {
    private val activeJobs = ConcurrentHashMap<Int, Job>()

    /**
     * Track a new update job for a specific widget.
     * Cancels any existing job for that widget ID before storing the new one.
     */
    fun trackJob(appWidgetId: Int, job: Job) {
        activeJobs[appWidgetId]?.cancel()
        activeJobs[appWidgetId] = job
        
        // Remove job from map when it completes (normally or by cancellation)
        job.invokeOnCompletion { 
            activeJobs.remove(appWidgetId, job)
        }
    }

    /**
     * Cancel any active update job for a specific widget.
     */
    fun cancelJob(appWidgetId: Int) {
        activeJobs.remove(appWidgetId)?.cancel()
    }
}

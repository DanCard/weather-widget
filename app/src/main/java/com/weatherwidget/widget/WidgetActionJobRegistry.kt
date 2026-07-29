package com.weatherwidget.widget

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

/** Tracks every active action for a widget without cancelling earlier serialized actions. */
internal object WidgetActionJobRegistry {
    private val jobsByWidget = ConcurrentHashMap<Int, MutableSet<Job>>()

    fun track(appWidgetId: Int, job: Job) {
        val jobs =
            jobsByWidget.computeIfAbsent(appWidgetId) {
                ConcurrentHashMap.newKeySet()
            }
        jobs += job
        job.invokeOnCompletion {
            jobs.remove(job)
            if (jobs.isEmpty()) {
                jobsByWidget.remove(appWidgetId, jobs)
            }
        }
    }

    fun cancelAll(appWidgetId: Int) {
        jobsByWidget.remove(appWidgetId)?.forEach(Job::cancel)
    }

    internal fun clearForTesting() {
        jobsByWidget.values.flatten().forEach(Job::cancel)
        jobsByWidget.clear()
    }
}

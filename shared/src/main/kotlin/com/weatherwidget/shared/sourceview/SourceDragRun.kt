package com.weatherwidget.shared.sourceview

import com.weatherwidget.shared.util.Log

/**
 * The rule for one drag of a Settings source row, for both platforms: remember the order when it
 * starts, save on drop only if the order changed, and log the same `SOURCE_DRAG` lines. Each platform
 * keeps its own row movement (Compose gesture, `ItemTouchHelper`) and calls [start] and [finish].
 */
class SourceDragRun(private val platform: String, private val host: Host) {
    interface Host {
        fun save(order: List<String>)

        /** Into `app_logs`, so a drag is queryable on either platform. */
        fun persistLog(tag: String, message: String)
    }

    private class Started(val sourceId: String, val order: List<String>)

    private var started: Started? = null

    fun start(sourceId: String, order: List<String>) {
        started = Started(sourceId, order)
        log("phase=start id=$sourceId order=$order")
    }

    /** True when the drop saved a new order. Does nothing without a [start], so a repeat call is safe. */
    fun finish(order: List<String>): Boolean {
        val drag = started ?: return false
        started = null
        val moved = order != drag.order
        log("phase=end id=${drag.sourceId} outcome=${if (moved) "moved" else "unchanged"} from=${drag.order} to=$order")
        if (moved) host.save(order)
        return moved
    }

    private fun log(detail: String) {
        val line = "platform=$platform $detail"
        Log.d(TAG, line)
        host.persistLog(TAG, line)
    }

    companion object {
        const val TAG = "SOURCE_DRAG"
    }
}

package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.SourceLastViewEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.sourceview.SourceViewTally
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import java.time.ZoneId

/**
 * Counts a user's switch to [WeatherSource] in `source_view_days` (`SourceViewTally` in `:shared`)
 * and stamps it as the source's last view in `source_last_view` (`SourceLastView`).
 * Best-effort: a failed write is logged and never blocks the toggle or the paint.
 * plans/261010-source-view-tracking-table.md
 */
object SourceViewRecorder {
    private const val TAG = "SourceViewRecorder"

    suspend fun record(
        context: Context,
        toSource: WeatherSource,
        viewMode: ViewMode,
        trigger: SourceViewTrigger,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        try {
            val primary = WidgetStateManager(context).getPrimarySource().id
            val switch = SourceViewTally.switchOf(toSource.id, primary, viewMode, trigger, nowMs, ZoneId.systemDefault())
            // Called from `finally` blocks: a cancelled interaction still counts the switch it made.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val dao = WeatherDatabase.getDatabase(context).sourceViewDao()
                dao.record(switch)
                // The one timestamp kept: when this source was last viewed (HourlyCadence's view age).
                dao.upsertLastView(SourceLastViewEntity(toSource.id, nowMs))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "source view record failed trigger=$trigger source=${toSource.id}", e)
        }
    }
}

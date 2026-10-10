package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.sourceview.SourceViewProbability
import com.weatherwidget.shared.sourceview.SourceViewTally
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.PreferredSourceHome
import com.weatherwidget.widget.ViewMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Desktop's half of `source_view_days` (`SourceViewTally` in `:shared`): counts the user's source
 * switches and logs the shared estimator once a day. Installed by the UI process; until then (tests,
 * the daemon) recording is a no-op. Writes run off the UI thread and never throw.
 * plans/261010-source-view-tracking-table.md
 */
internal object DesktopSourceViews {
    private const val TAG = "DesktopSourceViews"
    const val PROBABILITY_TAG = "SOURCE_VIEW_PROBABILITY"

    @Volatile
    private var dao: DesktopWeatherDao? = null

    @Volatile
    private var executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "source-views").apply { isDaemon = true }
    }

    fun install(dao: DesktopWeatherDao?, executor: Executor? = null) {
        this.dao = dao
        if (executor != null) this.executor = executor
    }

    fun record(
        toSourceId: String,
        effectiveSources: List<String>,
        viewMode: ViewMode,
        trigger: SourceViewTrigger,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val target = dao ?: return
        val switch = SourceViewTally.switchOf(
            toSourceId,
            PreferredSourceHome.preferredSourceId(effectiveSources),
            viewMode,
            trigger,
            nowMs,
            ZoneId.systemDefault(),
        )
        executor.execute {
            runCatching { target.recordSourceSwitch(switch) }
                .onFailure { Log.w(TAG, "source view record failed trigger=$trigger source=$toSourceId: ${it.message}") }
        }
    }

    /** Once a local day: `SOURCE_VIEW_PROBABILITY toggle=… NWS=1.00 …` into app_logs. */
    fun logDailyIfDue(effectiveSources: List<String>, nowMs: Long = System.currentTimeMillis()) {
        val target = dao ?: return
        executor.execute {
            runCatching {
                val zone = ZoneId.systemDefault()
                val last = target.getLatestLogByTagAndMessagePrefix(PROBABILITY_TAG, "")?.timestamp
                if (!SourceViewProbability.isDailyLogDue(last, nowMs, zone)) return@runCatching
                val line = SourceViewProbability.summaryLine(
                    rows = target.sourceViewDaysSince(SourceViewTally.retentionCutoffMs(nowMs, zone)),
                    trackingSince = target.sourceViewTrackingStartMs()?.let { LocalDate.ofEpochDay(it / SourceViewTally.DAY_MS) },
                    today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(),
                    primarySourceId = PreferredSourceHome.preferredSourceId(effectiveSources),
                    sourceIds = effectiveSources,
                )
                target.log(PROBABILITY_TAG, line, "INFO")
            }.onFailure { Log.w(TAG, "source view probability log failed: ${it.message}") }
        }
    }
}

/**
 * The API button: show the next usable source, and count the switch. The one copy — the header and
 * popup used to carry three, and a copy that skipped [DesktopSourceViews.record] would silently
 * undercount. No-op with one usable source.
 */
internal fun cycleDisplaySource(config: DesktopConfig, onUpdateConfig: (DesktopConfig) -> Unit) {
    val visibleSources = config.effectiveSources
    if (visibleSources.size <= 1) return
    val next = visibleSources[(visibleSources.indexOf(config.displaySource) + 1) % visibleSources.size]
    onUpdateConfig(config.copy(settings = config.settings.copy(weatherSource = next)))
    DesktopSourceViews.record(next, visibleSources, config.viewMode, SourceViewTrigger.TOGGLE)
}

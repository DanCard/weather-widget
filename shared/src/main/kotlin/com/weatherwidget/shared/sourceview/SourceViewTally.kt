package com.weatherwidget.shared.sourceview

import com.weatherwidget.widget.ViewMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which user action switched the displayed source. Stored by [name] in `source_view_days.triggerKind`.
 * Settings reorders, coverage fallbacks and the daemon's `source_change` refresh are not switches the
 * user made to look at a source, and are never recorded.
 */
enum class SourceViewTrigger {
    /** The API button on the widget / desktop header. */
    TOGGLE,

    /** The daily view's home button, back to the preferred source. */
    HOME,

    /** The source cycle in the Observations screen. */
    OBSERVATIONS,
}

/** The view a source was switched to, stored by [name] in `source_view_days.viewKind`. */
enum class SourceViewKind {
    DAILY,

    /** Every graph view (temperature, precipitation, cloud) and the Observations screen. */
    HOURLY,
    ;

    companion object {
        fun of(viewMode: ViewMode): SourceViewKind = if (viewMode.isGraphMode) HOURLY else DAILY
    }
}

/** One counted switch: the row it increments in `source_view_days`. */
data class SourceViewSwitch(
    val dateMs: Long,
    val sourceId: String,
    val viewKind: SourceViewKind,
    val trigger: SourceViewTrigger,
    val wasPrimary: Boolean,
)

/** A `source_view_days` row as both platforms read it back. */
data class SourceViewDayRow(
    val dateMs: Long,
    val sourceId: String,
    val viewKind: String,
    val trigger: String,
    val wasPrimary: Boolean,
    val switches: Int,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(Math.floorDiv(dateMs, SourceViewTally.DAY_MS))
}

/**
 * How often the user switches the displayed source, per local day — ONE rule for Android and desktop.
 *
 * Daily counts only: no timestamps, no switch order, no time of day (user, 2026-10-10: privacy; every
 * estimator in [SourceViewProbability] works at day granularity). Every switch counts as a view: a
 * glance at one day's rain chance is as short as passing a source on the way round the cycle, so no
 * dwell threshold separates them, and over-counting errs toward fetching.
 * See plans/261010-source-view-tracking-table.md.
 */
object SourceViewTally {
    const val DAY_MS = 86_400_000L

    /** The local day as epoch-day ms — the same day key `api_usage_stats` uses for local-day sources. */
    fun dayMs(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toEpochDay() * DAY_MS

    fun dayMs(date: LocalDate): Long = date.toEpochDay() * DAY_MS

    /**
     * Retention cutoff, day-aligned: keeps today and the [RetentionPolicy.SOURCE_VIEW_DAYS] days before
     * it — exactly what [SourceViewProbability] reads. (`now − 30 d` in ms would drop the 30th day.)
     */
    fun retentionCutoffMs(nowMs: Long, zone: ZoneId): Long =
        dayMs(nowMs, zone) - com.weatherwidget.data.local.RetentionPolicy.SOURCE_VIEW_DAYS * DAY_MS

    /**
     * The row a switch to [toSourceId] increments. [primarySourceId] is the preferred (first usable)
     * source at that moment; it is frozen into the row so a later reorder can't change what the day meant.
     * The Observations screen is always [SourceViewKind.HOURLY], whatever the widget's view.
     */
    fun switchOf(
        toSourceId: String,
        primarySourceId: String?,
        viewMode: ViewMode,
        trigger: SourceViewTrigger,
        nowMs: Long,
        zone: ZoneId,
    ): SourceViewSwitch =
        SourceViewSwitch(
            dateMs = dayMs(nowMs, zone),
            sourceId = toSourceId,
            viewKind = if (trigger == SourceViewTrigger.OBSERVATIONS) SourceViewKind.HOURLY else SourceViewKind.of(viewMode),
            trigger = trigger,
            wasPrimary = toSourceId == primarySourceId,
        )
}

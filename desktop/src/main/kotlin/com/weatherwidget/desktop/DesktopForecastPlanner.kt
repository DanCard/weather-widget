package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.data.remote.ForecastSchedule
import com.weatherwidget.data.remote.SourceQuotaBlocks

/**
 * The forecast loop's view of one source: what to fetch now ([parts] of a [horizon] fetch; null when
 * nothing is due), when it is next due, and its `FETCH_PLAN` fragment.
 */
internal data class SourcePlan(
    val parts: ForecastParts?,
    val horizon: ForecastHorizonCadence.Horizon?,
    val due: ForecastSchedule.Due,
    val horizonLog: String,
)

/**
 * The desktop forecast loop's per-source inputs to the shared rule: [DesktopForecastHorizon]
 * ([ForecastHorizonCadence], the same for every source) with the quota blocks ([SourceQuotaBlocks]);
 * and the next due time for [ForecastSchedule.sleep], held past a block that refuses every product.
 * One power/screen reading per instance, so a plan and its sleep agree.
 * performance/261016-unified-long-short-forecast-horizons.md
 */
internal class DesktopForecastPlanner(
    private val dao: DesktopWeatherDao,
    private val latitude: Double,
    private val longitude: Double,
    private val power: PowerDetector.PowerState,
    private val screenOn: Boolean,
) {
    fun plan(source: String, nowMs: Long): SourcePlan {
        val decision = requireNotNull(
            DesktopForecastHorizon(dao, source, latitude, longitude, { power }, { screenOn }).decision(nowMs),
        )
        val parts = decision.due?.let { ForecastParts.unblocked(SourceQuotaBlocks.blockedProducts(source, nowMs)) }
        // A source refused every product is next due when the refusal ends, not every minute until then.
        val heldUntil = SourceQuotaBlocks.fullyBlockedUntil(source, nowMs)
        val nextHorizon = decision.shortDueAtMs?.takeIf { it < decision.longDueAtMs }
            ?.let { ForecastHorizonCadence.Horizon.SHORT } ?: ForecastHorizonCadence.Horizon.LONG
        val due = ForecastSchedule.Due(
            source,
            nextHorizon,
            ForecastSchedule.heldPast(decision.nextDueAtMs, heldUntil) ?: decision.nextDueAtMs,
        )
        return SourcePlan(
            parts = parts,
            horizon = decision.due.takeIf { parts != null },
            due = due,
            horizonLog = ForecastHorizonCadence.logFragment(source, decision, nowMs, heldUntil),
        )
    }

    /** The earliest next-due fetch across [sources]. */
    fun next(sources: List<String>, nowMs: Long): ForecastSchedule.Due? =
        ForecastSchedule.next(sources.map { plan(it, nowMs).due })
}

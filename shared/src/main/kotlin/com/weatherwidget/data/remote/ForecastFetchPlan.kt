package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastParts

/**
 * Which forecasts a scheduled check fetches, for every candidate source, and its one
 * [ForecastParts.PLAN_TAG] line — the same step on both platforms. The platform names the candidates
 * (its visible sources the 8-day [com.weatherwidget.shared.sourceview.SourceFetchGate] lets through, plus a
 * targeted one) and supplies each source's [ForecastHorizonCadence] decision from its own power,
 * screen and marker reads. Was Android's `planFetch` + `logFetchPlan` and desktop's
 * `DesktopForecastPlanner`: Android's line judged every visible source (gated-off ones included),
 * desktop's only the candidates and only when one was due
 * (audit plans/261023-duplicate-logic-audit-different-names.md, open item).
 */
object ForecastFetchPlan {
    /** One source's plan: what to fetch now ([parts] of a [horizon] fetch; null when nothing is due) and when it is next due. */
    data class Source(
        val sourceId: String,
        val parts: ForecastParts?,
        val horizon: ForecastHorizonCadence.Horizon?,
        /** Null without a decision (no context or site: not scheduled). */
        val nextDue: ForecastSchedule.Due?,
        val logFragment: String?,
    )

    data class Plan(val sources: List<Source>) {
        /** The sources fetched now, in candidate order. */
        val due: Map<String, ForecastParts> =
            LinkedHashMap<String, ForecastParts>().apply { sources.forEach { s -> s.parts?.let { put(s.sourceId, it) } } }

        /** The [ForecastParts.PLAN_TAG] message: what is fetched, and every candidate's horizon decision. */
        val line: String = ForecastParts.planLogLine(due, sources.mapNotNull { it.logFragment })

        operator fun get(sourceId: String): Source? = sources.firstOrNull { it.sourceId == sourceId }

        /**
         * This plan less the due sources tried within [minIntervalMs] of [nowMs] ([lastTriedMs]), so a
         * scheduled check never repeats a source too soon. Per source: another source's recent fetch
         * must not hold back one that is due (Pixel 2026-10-30: OWM, due and 26 days old, waited on the
         * other six). Their [Source.nextDue] stands.
         */
        fun withoutRecentlyTried(lastTriedMs: (String) -> Long, nowMs: Long, minIntervalMs: Long): Plan =
            copy(
                sources = sources.map { s ->
                    if (s.parts != null && nowMs - lastTriedMs(s.sourceId) < minIntervalMs) s.copy(parts = null, horizon = null) else s
                },
            )
    }

    /**
     * [forced]: parts a user-forced fetch takes regardless of cadence (Android's targeted Refresh /
     * source switch); null for a scheduled check. [dueWithoutDecision]: whether a source with no
     * [decide] answer (Android without a fetch context) is due by the older staleness rule.
     */
    fun plan(
        candidates: List<String>,
        nowMs: Long,
        decide: (String) -> ForecastHorizonCadence.Decision?,
        forced: (String) -> ForecastParts? = { null },
        dueWithoutDecision: (String) -> Boolean = { false },
    ): Plan = Plan(candidates.map { source(it, decide(it), nowMs, forced(it), dueWithoutDecision) })

    fun source(
        sourceId: String,
        decision: ForecastHorizonCadence.Decision?,
        nowMs: Long,
        forced: ForecastParts? = null,
        dueWithoutDecision: (String) -> Boolean = { false },
    ): Source {
        // A source refused every product is next due when the refusal ends, not every minute until then.
        val heldUntil = SourceQuotaBlocks.fullyBlockedUntil(sourceId, nowMs)
        val scheduledDue = if (decision != null) decision.due != null else dueWithoutDecision(sourceId)
        val parts = forced ?: if (scheduledDue) ForecastParts.unblocked(SourceQuotaBlocks.blockedProducts(sourceId, nowMs)) else null
        val nextDue = decision?.let { ForecastSchedule.Due(sourceId, ForecastSchedule.heldPast(it.dueAtMs, heldUntil) ?: it.dueAtMs) }
        return Source(
            sourceId = sourceId,
            parts = parts,
            horizon = decision?.due?.takeIf { parts != null },
            nextDue = nextDue,
            logFragment = decision?.let { ForecastHorizonCadence.logFragment(sourceId, it, nowMs, heldUntil) },
        )
    }
}

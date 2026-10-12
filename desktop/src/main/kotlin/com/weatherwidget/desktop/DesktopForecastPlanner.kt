package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.data.remote.ForecastSchedule
import com.weatherwidget.data.remote.HourlyCadence
import com.weatherwidget.data.remote.HourlyWindowPolicy
import com.weatherwidget.data.remote.SourceQuotaBlocks

/** The forecast loop's view of one source: what to fetch now, when each part is next due, the hourly log fragment. */
internal data class SourcePlan(
    val parts: ForecastParts?,
    val dues: List<ForecastSchedule.Due>,
    val hourlyLog: String?,
)

/**
 * The desktop forecast loop's per-source inputs to the shared rules: daily due from the `REFRESH` row
 * and [DesktopFetchStrategy] (`ForecastCadence`), hourly from [DesktopHourlyWindow] (`HourlyCadence`),
 * combined by [ForecastParts.forRoutine] with the quota blocks ([SourceQuotaBlocks]); and each part's
 * next due time for [ForecastSchedule.sleep], held past a block's end.
 * One power/screen reading per instance, so a plan and its sleep agree.
 * performance/261011-desktop-forecast-loop-sleep-until-due.md
 */
internal class DesktopForecastPlanner(
    private val dao: DesktopWeatherDao,
    private val latitude: Double,
    private val longitude: Double,
    private val power: PowerDetector.PowerState,
    private val screenOn: Boolean,
) {
    fun plan(source: String, isDisplayed: Boolean, nowMs: Long): SourcePlan {
        val dailyIntervalMs = DesktopFetchStrategy.getForecastRefreshDelayMs(
            power.isCharging, power.batteryLevel, isActiveSource = isDisplayed, screenOn = screenOn,
        )
        val lastDaily = dao.getLastSuccessfulFetch(source)
        val window = DesktopHourlyWindow(dao, source, latitude, longitude) { power }
        val hourly = window.hourlyDecision(isDisplayed, nowMs)
        val parts = ForecastParts.forRoutine(
            dailyDue = ForecastSchedule.isDue(lastDaily, dailyIntervalMs, nowMs),
            hourlyDue = hourly?.due,
            isFull = window.current(nowMs) == HourlyWindowPolicy.Window.FULL,
            blocked = SourceQuotaBlocks.blockedProducts(source, nowMs),
        )
        // A part refused by a quota is next due when the refusal ends, not every minute until then.
        fun blockEnd(part: ForecastSchedule.Part) = SourceQuotaBlocks.blockedUntil(source, part.product, nowMs)
        val dues = listOfNotNull(
            ForecastSchedule.heldPast(ForecastSchedule.dueAtMs(lastDaily, dailyIntervalMs, nowMs), blockEnd(ForecastSchedule.Part.DAILY))
                ?.let { ForecastSchedule.Due(source, ForecastSchedule.Part.DAILY, it) },
            hourly?.let { ForecastSchedule.dueAtMs(it.lastHourlyFetchMs, HourlyCadence.intervalMs(it.intervalHours), nowMs) }
                ?.let { ForecastSchedule.heldPast(it, blockEnd(ForecastSchedule.Part.HOURLY)) }
                ?.let { ForecastSchedule.Due(source, ForecastSchedule.Part.HOURLY, it) },
        )
        val fragment = hourly?.let {
            HourlyCadence.logFragment(
                source, it.intervalHours, it.viewAgeMs, it.lastHourlyFetchMs, nowMs, it.due,
                hourlyBlockedUntilMs = blockEnd(ForecastSchedule.Part.HOURLY),
            )
        }
        return SourcePlan(parts, dues, fragment)
    }

    /** The earliest next-due part across [sources] (source id to is-displayed). */
    fun next(sources: List<Pair<String, Boolean>>, nowMs: Long): ForecastSchedule.Due? =
        ForecastSchedule.next(sources.flatMap { (source, isDisplayed) -> plan(source, isDisplayed, nowMs).dues })
}

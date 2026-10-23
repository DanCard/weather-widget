package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.ForecastFetchPlan
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.data.remote.ForecastSchedule

/**
 * The desktop forecast loop's host for `:shared` [ForecastFetchPlan] (the plan step Android's sync
 * also runs): each source's [ForecastHorizonCadence] decision from [DesktopForecastHorizon]'s markers
 * and one power/screen reading per instance, so a plan and its sleep agree.
 * performance/261016-unified-long-short-forecast-horizons.md
 */
internal class DesktopForecastPlanner(
    private val dao: DesktopWeatherDao,
    private val latitude: Double,
    private val longitude: Double,
    private val power: PowerDetector.PowerState,
    private val screenOn: Boolean,
) {
    private fun decision(source: String, nowMs: Long): ForecastHorizonCadence.Decision = requireNotNull(
        DesktopForecastHorizon(dao, source, latitude, longitude, { power }, { screenOn }).decision(nowMs),
    )

    fun plan(sources: List<String>, nowMs: Long): ForecastFetchPlan.Plan =
        ForecastFetchPlan.plan(sources, nowMs, decide = { decision(it, nowMs) })

    fun plan(source: String, nowMs: Long): ForecastFetchPlan.Source = ForecastFetchPlan.source(source, decision(source, nowMs), nowMs)

    /** The earliest next-due fetch across [sources]. */
    fun next(sources: List<String>, nowMs: Long): ForecastSchedule.Due? =
        ForecastSchedule.next(sources.mapNotNull { plan(it, nowMs).nextDue })
}

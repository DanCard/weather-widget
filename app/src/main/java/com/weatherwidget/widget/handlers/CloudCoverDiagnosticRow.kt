package com.weatherwidget.widget.handlers

import java.time.LocalDate

/**
 * Projection of a daily bar/row needed only for the app_logs cloud-cover diagnostic in
 * [DailyViewHandler.buildCloudCoverDiagnostic]. Declared in `handlers/` (not in
 * `DailyForecastGraphRenderer`) so the handler/module-facade does not depend on the
 * concrete renderer's `DayData` type — the dependency direction flips and both
 * `DailyForecastGraphRenderer.DayData` and any future alternative implement it.
 */
internal interface CloudCoverDiagnosticRow {
    val date: LocalDate
    val daysFromToday: Int
    val cloudCoverRatioOverride: Float?
}

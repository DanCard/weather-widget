package com.weatherwidget.widget

/**
 * Converts a dp value to pixels for the given display density.
 *
 * This was declared privately and identically in eight daily-view files
 * (`DailyGraphPaintCache`, `DailyHighLabelPlanner`, `DailyColumnRenderer`,
 * `DailyForecastGraphRenderer`, `DailyGraphLayoutResolver`, `DailyForecastHeaderRenderer`,
 * `TodayColumnOverlayRenderer`, `DailyBarRenderer`); hoisted here in Phase 4 of
 * plans/260909-desktop-android-duplication-and-complexity-review.md.
 */
internal fun Float.dp(density: Float): Float = this * density

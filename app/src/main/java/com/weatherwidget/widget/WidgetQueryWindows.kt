package com.weatherwidget.widget

/** Shared query windows used by startup and interactive widget render paths. */
object WidgetQueryWindows {
    /** Covers yesterday's observations and rain-analysis context. */
    const val HOURLY_LOOKBACK_HOURS = 72L

    /** Covers current interactive graph observation context. */
    const val HOURLY_LOOKAHEAD_HOURS = 60L

    /** Covers the full seven-day hourly graph horizon. */
    const val HOURLY_GRAPH_LOOKAHEAD_HOURS = 168L

    /**
     * Future horizon for daily forecast rows AND for the `ClimateGapFiller` gap-row fill, shared by
     * the startup, worker, and interactive render paths.
     *
     * This MUST cover the daily render horizon, which is **width-derived, not fixed**: the daily
     * view draws `numColumns` columns (`WidgetSizeCalculator.columnsForWidthDp`, uncapped) reaching
     * `today + numColumns - 2` at offset 0, and navigation reaches further still. When startup and
     * the worker used `7` here while a 10-column widget rendered `today+8`, that last column got
     * neither a real row nor a gap row: the icon fell back to `ic_weather_unknown` (a grey cloud)
     * and the bar to slate-grey `FORECAST_CLOUDY`, so a climate-normal day rendered as "cloudy"
     * until an interactive repaint — which used 30 — corrected it. Keep every path on this constant.
     */
    const val DAILY_FORECAST_DAYS = 30L
}

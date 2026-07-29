package com.weatherwidget.widget

/** Shared query windows used by startup and interactive widget render paths. */
object WidgetQueryWindows {
    /** Covers yesterday's observations and rain-analysis context. */
    const val HOURLY_LOOKBACK_HOURS = 72L

    /** Covers current interactive graph observation context. */
    const val HOURLY_LOOKAHEAD_HOURS = 60L

    /** Covers the full seven-day hourly graph horizon. */
    const val HOURLY_GRAPH_LOOKAHEAD_HOURS = 168L
}

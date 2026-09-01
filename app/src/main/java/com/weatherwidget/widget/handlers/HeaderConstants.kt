package com.weatherwidget.widget.handlers

/**
 * Shared constants for the widget header to ensure consistency across all views.
 */
object HeaderConstants {
    const val CURRENT_TEMP_TEXT_SIZE_DP = 18f
    // The daily forecast view runs its header temperature 20% larger than the other views.
    // Used both for the canvas paint (DailyForecastHeaderRenderer) and for the daily header
    // fit math (disclosure / header scale / date placement) so measurement matches what's drawn.
    const val DAILY_CURRENT_TEMP_TEXT_SIZE_DP = CURRENT_TEMP_TEXT_SIZE_DP * 1.2f
    const val WEATHER_ICON_SIZE_DP = 24f
    const val DELTA_TEXT_SIZE_DP = 14f
    // "from yest" caption after the delta: deliberately smaller than the delta itself.
    // Reduced ~20% (10 -> 8) so the opportunistic caption stays out of the way on
    // crowded narrow headers.
    const val DELTA_LABEL_TEXT_SIZE_DP = 8f
    const val WEATHER_ICON_END_MARGIN_DP = 2f
    const val DELTA_MARGIN_START_DP = 4f
    const val DELTA_LABEL_MARGIN_START_DP = 2f
    const val PRECIP_MARGIN_START_DP = 8f
    const val API_SOURCE_MARGIN_END_DP = 32f
    // Extra right margin applied only when the API label is a single source (no " - ")
    // — keeps long dual-source labels at the tight 32dp while giving "Meteo" / "NWS"
    // breathing room from the gear.
    const val API_SINGLE_SOURCE_EXTRA_MARGIN_DP = 12f
    const val API_SOURCE_CONTAINER_PADDING_DP = 14f
    const val DATE_TEXT_SIZE_DP = 20f
    /**
     * `layout_marginTop` applied to the shared centre-icon container in the DAILY view, in dp.
     *
     * The XML value (-10dp) is tuned for the hourly view, whose `current_temp` TextView carries the
     * same -10dp so icons and text share a line. The daily view PAINTS its header text instead, at
     * `upOffset` (-2dp), leaving the buttons ~6dp high beside the date. Lifting the date instead
     * was tried and clipped it against the bitmap's top edge — the painted row is already close to
     * the ceiling, so the buttons are the side with room to move.
     *
     * Set explicitly in both view modes ([positionDailyIcons] / [positionCenterIcons]) rather than
     * relying on the XML default, because partial RemoteViews updates can carry a previously
     * applied margin over into the other mode.
     */
    const val DAILY_ICON_CONTAINER_MARGIN_TOP_DP = -4f
    const val HOURLY_ICON_CONTAINER_MARGIN_TOP_DP = -10f
    const val DATE_HORIZONTAL_GAP_DP = 6f
    const val DATE_RIGHT_MARGIN_DP = 112f
    const val DATE_MIN_COLUMNS = 6
    const val SETTINGS_ICON_SIZE_DP = 18f
    const val SETTINGS_ICON_MARGIN_END_DP = 0f
    const val PRECIP_TEXT_BASE_SIZE_DP = 18f
    /**
     * Extra downward nudge for the header rain chance, in dp, relative to the rest of the header row.
     *
     * The `%` runs larger than the temperature beside it, so sharing the row's top anchor left it
     * reading high against the other header items. Applied in BOTH Android header paths so the label
     * does not shift when the view mode changes: the daily view's painted baseline
     * (`DailyForecastHeaderRenderer`, draw and ink-bounds walk alike) and the hourly view's
     * `precip_probability` TextView, whose `layout_marginTop` is this much less negative than the
     * -10dp the rest of the row carries.
     */
    const val PRECIP_EXTRA_DROP_DP = 2f
    const val CENTER_ICON_SIZE_DP = 20f
    // Daily-view header buttons (current observations / forecast history). Zone widths are FIXED
    // rather than following the hourly view's width-dependent 32/40/48 ladder, so one constant
    // feeds both the fit math (HeaderWidthChecker.resolveDailyIconPlacement) and the layout
    // (positionDailyIcons).
    //
    // The hourly header packs FOUR icons into 24dp zones, which reads fine as a row. Two icons at
    // that pitch read as a single glued-together control (reported on Samsung), so the daily pair
    // gets a wider zone — 20dp icon in a 40dp zone leaves ~20dp of air between them instead of ~4.
    // Applied via setViewLayoutWidth, so it needs API 31+; below that the XML width stands and the
    // fit math must measure THAT, hence the two constants.
    // Wide headers can afford the airy zone; narrow ones cannot — every dp of zone is a dp taken
    // from the gap the date has to fit into on the right, and 40dp zones cost that gap 16dp, which
    // is the difference between the date showing and being dropped on a ~350dp widget.
    // Three rungs, not two. The 24dp zone leaves a 20dp icon just 4dp of air, which reads as one
    // control rather than several — reported on Samsung for the pair, and again on a Pixel 7 Pro
    // (~412dp, one dp bracket below the airy cutoff) once the home button made it three. The middle
    // rung buys 12dp of air there without spending the 16dp per zone the airy rung costs the date.
    const val DAILY_CENTER_ICON_ZONE_WIDE_DP = 40f
    const val DAILY_CENTER_ICON_ZONE_MEDIUM_DP = 32f
    const val DAILY_CENTER_ICON_ZONE_NARROW_DP = 24f
    const val DAILY_CENTER_ICON_ZONE_XML_WIDTH_DP = 24f
    /** At or above this width the daily buttons use the airy zone. Mirrors the inline-nav cutoff. */
    const val DAILY_WIDE_HEADER_MIN_WIDTH_DP = 420
    /** At or above this width they get the middle zone; below it the header cannot spare the air. */
    const val DAILY_MEDIUM_HEADER_MIN_WIDTH_DP = 360
    const val DAILY_INLINE_ICON_ZONE_WIDTH_DP = 32f
    const val DAILY_INLINE_FIRST_ZONE_MARGIN_DP = 1f
    const val GRAPH_SELECTOR_TEXT_SIZE_DP = 16f
    const val API_TEXT_SIZE_LARGE_DP = 12.6f
    const val API_TEXT_SIZE_MEDIUM_DP = 11.2f
    const val API_TEXT_SIZE_SMALL_DP = 9.8f

    fun apiTextSizeDp(numRows: Int): Float = when {
        numRows >= 3 -> API_TEXT_SIZE_LARGE_DP
        numRows >= 2 -> API_TEXT_SIZE_MEDIUM_DP
        else -> API_TEXT_SIZE_SMALL_DP
    }
}

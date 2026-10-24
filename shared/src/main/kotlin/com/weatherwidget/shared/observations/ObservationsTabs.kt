package com.weatherwidget.shared.observations

/**
 * The Observations screen's tabs, both platforms. The ids are persisted (Android pref
 * `last_selected_obs_tab`, desktop `obsSelectedTab`), so they never change.
 */
object ObservationsTabs {
    // Blend leads: it is the tab that explains the graph's observed dot, which is what the screen is
    // usually opened to investigate.
    const val BLEND = 0
    const val OBSERVATIONS = 1
    const val FETCH_LOGS = 2

    /**
     * The Blend tab shows only the CURRENT blended point. History was dropped deliberately: the
     * question the tab answers is "why does the dot read what it reads right now", and a scrolling
     * backlog of past timestamps buried it. One table also buys the room for a much larger font.
     */
    const val BLEND_TABLE_POINTS = 1
}

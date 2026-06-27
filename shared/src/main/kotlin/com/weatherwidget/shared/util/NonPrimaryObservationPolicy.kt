package com.weatherwidget.shared.util

object NonPrimaryObservationPolicy {
    const val CHARGING_SCREEN_ON_MINUTES = 30L

    /** Interval for refreshing non-primary sources' actuals, or null to not run this cycle. */
    fun intervalMinutes(isCharging: Boolean, screenOn: Boolean): Long? =
        if (isCharging && screenOn) CHARGING_SCREEN_ON_MINUTES else null
}

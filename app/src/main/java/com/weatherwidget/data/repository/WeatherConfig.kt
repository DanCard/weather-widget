package com.weatherwidget.data.repository

object WeatherConfig {
    const val ACTUALS_HISTORY_DAYS = 3
    const val NWS_BACKFILL_DAYS = 2 // Kept at 2 days per user feedback to avoid large backfills
}

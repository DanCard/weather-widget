package com.weatherwidget.data.repository

object WeatherConfig {
    const val ACTUALS_HISTORY_DAYS = 3
    const val NWS_BACKFILL_DAYS = 3 // Increased to 3 days to match hourly graph historical lookback
}

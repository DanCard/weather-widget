package com.weatherwidget.data.model

data class HourlyForecast(
    val dateTime: Long,
    val temperature: Float,
    val condition: String,
    val precipProbability: Int? = null,
    val precipAmountMm: Float? = null,
    val cloudCover: Int? = null,
)

data class DailyForecast(
    val date: String,
    val highTemp: Float,
    val lowTemp: Float,
    val condition: String,
    val iconToken: String? = null,
    val precipProbability: Int? = null,
    val precipAmountMm: Float? = null,
)

data class ForecastResult(
    val currentTemp: Float? = null,
    val currentCondition: String? = null,
    val currentObservedAt: Long? = null,
    val daily: List<DailyForecast> = emptyList(),
    val hourly: List<HourlyForecast> = emptyList(),
)

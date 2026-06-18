package com.weatherwidget.desktop

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ViewMode {
    @SerialName("DAILY") DAILY,
    @SerialName("HOURLY") HOURLY,
    @SerialName("TEMPERATURE") TEMPERATURE,
    @SerialName("CLOUD_COVER") CLOUD_COVER,
    @SerialName("PRECIPITATION") PRECIPITATION;

    val isHourly get() = this != DAILY

    companion object {
        fun fromConfig(value: String): ViewMode =
            entries.find { it.name == value } ?: DAILY
    }
}

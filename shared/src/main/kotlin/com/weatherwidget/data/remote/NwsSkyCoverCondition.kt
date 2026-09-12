package com.weatherwidget.data.remote

/**
 * The sky-condition words NWS's forecast text uses for a sky-cover percentage, for rows assembled
 * from the raw grid (which has no `shortForecast`). Bands are NWS's own (NWS Directive 10-503):
 * ≤12 clear, ≤37 mostly clear, ≤62 partly cloudy, ≤87 mostly cloudy, else cloudy. The day/night
 * variants ("Sunny"/"Partly Sunny") need a daytime flag the grid does not carry; the night-neutral
 * words map to the same colours and icons via `WeatherCodeMapper`'s vocabulary.
 */
object NwsSkyCoverCondition {
    const val UNKNOWN = "Unknown"

    fun shortForecastFor(skyCover: Int?): String = when {
        skyCover == null -> UNKNOWN
        skyCover <= 12 -> "Clear"
        skyCover <= 37 -> "Mostly Clear"
        skyCover <= 62 -> "Partly Cloudy"
        skyCover <= 87 -> "Mostly Cloudy"
        else -> "Cloudy"
    }
}

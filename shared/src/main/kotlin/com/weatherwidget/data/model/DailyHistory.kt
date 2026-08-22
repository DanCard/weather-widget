package com.weatherwidget.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily high/low temperature extremes and precipitation actuals per (date, source, location).
 */
data class DailyHistory(
    val date: Long,             // UTC midnight epoch millis
    val source: String,         // WeatherSource.id (NWS, OPEN_METEO, etc.)
    val locationLat: Double,
    val locationLon: Double,
    // Blended extreme from the IDW observation pipeline ("Location actual"). Null when the row is
    // a forecast-only row (DailyHistoryWriter.FORECAST_ONLY_ROW): sources with
    // WeatherSource.supportsTemperatureActuals == false never fabricate observations, so the null
    // IS the "no actuals" marker and accuracy/scoring code must skip these rows.
    val computedHighTemp: Float?, // °F
    val computedLowTemp: Float?,  // °F
    val condition: String,
    val updatedAt: Long,        // epoch ms, used for cleanup
    val precipAmountMm: Float? = null, // Daily observed precipitation amount in mm (total)
    val precipDayMm: Float? = null, // Daytime (8AM-8PM) observed precipitation in mm
    val precipNightMm: Float? = null, // Nighttime (8PM-8AM) observed precipitation in mm
    // Resolved (as-displayed) forecast rain chance %, snapshotted while the day was current so
    // history replays what the widget showed instead of NWS's raw 6am/6pm period fields (see
    // DailyRainLabels.resolveDailyLabelPrecip). Null for rows written before this feature.
    val forecastDayPrecipChance: Int? = null,
    val forecastNightPrecipChance: Int? = null,
    // Frozen forecast-overlay values (yellow accuracy bar) and noon cloud %, snapshotted while the
    // day was current so the daily bar view can render past days from this row alone — without the
    // forecasts / hourly tables whose retention may be shorter (see DailyHistoryFreeze). High/low
    // move as a unit (both null or both set). Null for rows written before this feature.
    val forecastHighTemp: Float? = null,
    val forecastLowTemp: Float? = null,
    val forecastPrecipAmountMm: Float? = null,
    val noonCloudPercent: Int? = null,
    val apiHighTemp: Float? = null,  // °F — API-reported observed high; null when source provides no native actuals
    val apiLowTemp: Float? = null,   // °F — API-reported observed low; null when source provides no native actuals
    // Which station produced apiHighTemp/apiLowTemp, when they came from station observations
    // (NWS via StationDailyExtremes). Null for gridded api actuals and for pre-v59 rows.
    val apiStationId: String? = null,
    val apiStationDistanceKm: Float? = null,
    /** See `DailyActualsSource`; also the marker that a past day's actuals are resolved. */
    val actualsSource: String? = null,
    /** See `DailyHistoryWriter`; diagnostic only. */
    val lastWriter: String? = null,
) {
    fun toLocalDate(): LocalDate =
        LocalDate.ofEpochDay(date / 86_400_000L)

    /**
     * Accuracy-type view of this row. Null for forecast-only rows (null [computedHighTemp] /
     * [computedLowTemp]): without an observed extreme there is nothing to score against, so
     * callers skip the day entirely.
     */
    fun toDailyActual(): DailyActual? {
        val high = computedHighTemp ?: return null
        val low = computedLowTemp ?: return null
        return DailyActual(
            date = toLocalDate().toString(),
            computedHighTemp = high,
            computedLowTemp = low,
            condition = condition,
            apiHighTemp = apiHighTemp,
            apiLowTemp = apiLowTemp,
        )
    }

    /** Label-facing extreme: actual when present, else the frozen forecast (forecast-only rows). */
    val displayHighTemp: Float? get() = computedHighTemp ?: forecastHighTemp

    /** Label-facing extreme: actual when present, else the frozen forecast (forecast-only rows). */
    val displayLowTemp: Float? get() = computedLowTemp ?: forecastLowTemp

    /** True when this row carries observed extremes (i.e. it may serve as an accuracy baseline). */
    val hasActuals: Boolean get() = computedHighTemp != null && computedLowTemp != null
}

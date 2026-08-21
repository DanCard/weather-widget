package com.weatherwidget.util

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource

/**
 * Whether an [observation] row counts as the "actual" rainfall series for [displaySource].
 *
 * NWS uses real station observations but excludes the synthetic `NWS_BLEND` rows. Other sources
 * keep only their own `_MAIN` rows when their declared history provenance preserves precipitation.
 * Shared so the widget precip graph and Statistics rain accuracy use the same boundary.
 */
object ActualPrecipSource {
    const val NWS_BLEND_STATION_ID = "NWS_BLEND"
    const val MAIN_STATION_SUFFIX = "_MAIN"

    fun matches(observation: ObservationEntity, displaySource: WeatherSource): Boolean {
        if (!displaySource.historicalDataKind.preservesHistoricalPrecipitation) return false
        return when (displaySource) {
            WeatherSource.NWS ->
                observation.api == WeatherSource.NWS.id && observation.stationId != NWS_BLEND_STATION_ID
            else ->
                observation.api == displaySource.id && observation.stationId.endsWith(MAIN_STATION_SUFFIX)
        }
    }
}

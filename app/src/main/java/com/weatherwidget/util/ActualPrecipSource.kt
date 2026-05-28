package com.weatherwidget.util

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource

/**
 * Whether an [observation] row counts as the "actual" rainfall series for [displaySource].
 *
 * NWS uses real station observations but excludes the synthetic `NWS_BLEND` rows; every other
 * source keeps only its own `_MAIN` pseudo-actual rows. Shared so the widget precip graph and the
 * Statistics rain-accuracy history bucket the same observations.
 */
object ActualPrecipSource {
    const val NWS_BLEND_STATION_ID = "NWS_BLEND"
    const val MAIN_STATION_SUFFIX = "_MAIN"

    fun matches(observation: ObservationEntity, displaySource: WeatherSource): Boolean =
        when (displaySource) {
            WeatherSource.NWS ->
                observation.api == WeatherSource.NWS.id && observation.stationId != NWS_BLEND_STATION_ID
            else ->
                observation.api == displaySource.id && observation.stationId.endsWith(MAIN_STATION_SUFFIX)
        }
}

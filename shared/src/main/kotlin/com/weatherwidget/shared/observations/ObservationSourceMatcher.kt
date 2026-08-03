package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill

/**
 * Decides whether an observation row's [stationId] should be shown in the stations list for a given
 * display [WeatherSource]. Shared by the Android observations screen
 * (`WeatherObservationsActivity.WeatherObservationsSupport`) and the desktop `ObservationsWindow` so
 * the two cannot drift.
 *
 * Two kinds of rows are synthetic (not real station observations) and must never masquerade as a
 * station under NWS:
 *  - the internal IDW blend (`"NWS_BLEND"`), and
 *  - the historical-actuals backfill ([HistoricalActualsBackfill.syntheticStationId], e.g.
 *    `"NWS_MAIN"`) that the NWS->Open-Meteo fallback mints so the actual line still renders.
 *
 * For non-NWS sources the backfill row (`"<SOURCE>_MAIN"`) is intentionally kept: those sources have
 * no real stations of their own, so it is the only entry available.
 */
object ObservationSourceMatcher {

    /**
     * True when [stationId] is the historical-actuals backfill row for [sourceId] — a slice of that
     * source's hourly *forecast* re-filed as observations at `distanceKm = 0`, not a measurement.
     *
     * The blend uses this to rank such a row below every real station
     * (`ActualTemperatureSeriesBuilder.blendCandidateTemperature`): its zero distance would otherwise
     * win the near-zero override outright and suppress genuine readings.
     */
    fun isSyntheticBackfillStation(stationId: String, sourceId: String): Boolean =
        stationId == HistoricalActualsBackfill.syntheticStationId(sourceId)

    private val sourcePrefixes: Map<WeatherSource, String> =
        listOf(
            WeatherSource.VISUAL_CROSSING,
            WeatherSource.OPEN_WEATHER_MAP,
            WeatherSource.OPEN_METEO,
            WeatherSource.WEATHER_API,
            WeatherSource.SILURIAN,
            WeatherSource.TOMORROW_IO,
        ).associateWith { "${it.id}_" }

    fun matchesObservationSource(stationId: String, source: WeatherSource): Boolean =
        when (source) {
            WeatherSource.NWS ->
                stationId != "NWS_BLEND" &&
                    stationId != HistoricalActualsBackfill.syntheticStationId(WeatherSource.NWS.id) &&
                    sourcePrefixes.values.none { prefix -> stationId.startsWith(prefix) }
            else -> stationId.startsWith(sourcePrefixes[source] ?: return false)
        }
}

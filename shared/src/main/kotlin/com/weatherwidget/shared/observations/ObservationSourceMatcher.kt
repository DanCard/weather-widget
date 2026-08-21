package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill
import com.weatherwidget.shared.actuals.TomorrowIoActuals

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
     *
     * Deliberately matches ONLY `<SOURCE>_MAIN`. The current-temperature POI grid
     * (`CurrentTempRepository.getPointsOfInterest`) files its four offset samples as
     * `<SOURCE>_<index>` — also model-derived, not real thermometers — but they are NOT flagged here
     * because they must rank as distinct "sites" for the spatial-interpolation blend and surface as
     * POIs in the stations list. This asymmetry is intentional; changing it would silently switch
     * the forecast-only-source current blend from interpolating over the offsets to the centre-only
     * near-zero override.
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

    /**
     * Station-ID-based classifier used by the stations list (Android WeatherObservationsSupport and
     * desktop ObservationsWindow). It must key on [stationId] rather than the stored `api` column
     * because it also has to reject synthetic rows (NWS_BLEND, `<SOURCE>_MAIN`) and non-NWS prefixes
     * within the NWS pool.
     *
     * The blend path answers the same "does this observation belong to source X?" question with the
     * OTHER key — the stored `api` field — via
     * `ActualTemperatureSeriesBuilder.matchesObservationSource` (and Android's
     * `TemperatureHourDataBuilder.matchesObservationSource`). The two rules agree because both are
     * written together at insert time (`api = source.id`, stationId = `<SOURCE>_…`); they can only
     * drift if a new writer sets one without the other.
     */
    fun matchesObservationSource(stationId: String, source: WeatherSource): Boolean =
        when (source) {
            WeatherSource.NWS ->
                stationId != "NWS_BLEND" &&
                    stationId != HistoricalActualsBackfill.syntheticStationId(WeatherSource.NWS.id) &&
                    sourcePrefixes.values.none { prefix -> stationId.startsWith(prefix) }
            WeatherSource.TOMORROW_IO -> TomorrowIoActuals.isAllowedStation(stationId)
            else -> stationId.startsWith(sourcePrefixes[source] ?: return false)
        }

    /** True when a row may drive the selected source's temperature/cloud actuals. */
    fun matchesActualSource(
        stationId: String,
        api: String,
        source: WeatherSource,
        allowGenericGap: Boolean = true,
    ): Boolean {
        if (!source.supportsTemperatureActuals) return false
        if (api == WeatherSource.GENERIC_GAP.id) {
            return allowGenericGap && source != WeatherSource.TOMORROW_IO
        }
        if (api != source.id) return false
        return source != WeatherSource.TOMORROW_IO || TomorrowIoActuals.isAllowedStation(stationId)
    }
}

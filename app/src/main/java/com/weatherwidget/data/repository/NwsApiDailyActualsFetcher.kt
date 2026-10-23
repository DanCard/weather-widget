package com.weatherwidget.data.repository

import com.weatherwidget.shared.actuals.NwsStationActualsRun
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.actuals.NwsDailyExtremesFetch
import com.weatherwidget.shared.actuals.NwsStationActualsMaintenance
import com.weatherwidget.widget.WidgetConstants
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NwsApiDailyActuals"

/**
 * Fills **both** NWS actuals for a past day — the blend (`computedHighTemp`/`computedLowTemp`) and
 * the single-station extreme (`apiHighTemp`/`apiLowTemp`) — from one complete pull of
 * `api.weather.gov/stations/{id}/observations`.
 *
 * This is the only writer of NWS api actuals, and the only writer of a *past* day's blend. Two
 * earlier api-actual writers were removed on 2026-08-08: gridpoint
 * `maxTemperature`/`minTemperature` (the NDFD *forecast* grid, which made every past day's actual
 * equal that day's forecast) and an Open-Meteo ERA5 backfill (another provider's data in NWS's
 * row). Deriving either value from stored observation rows was rejected too — that pool is roughly
 * half Synoptic and materially thinner than the endpoint (AW020: 81 stored rows against 144
 * served). See plans/260808-nws-actuals-forecast-contamination.md and
 * plans/260808-history-actuals-from-nws-station-pull.md.
 */
@Singleton
class NwsApiDailyActualsFetcher
    @Inject
    constructor(
        private val observationSource: NwsObservationSource,
        private val stationActualsStore: NwsStationActualsStore,
        private val appLogDao: AppLogDao,
        private val personalStationWeightProvider: PersonalStationWeightProvider,
    ) {
        /** The rule is `:shared` [NwsStationActualsRun]; this is its Android host. */
        suspend fun fillMissingIfNeeded(latitude: Double, longitude: Double) =
            NwsStationActualsRun.run(
                object : NwsStationActualsRun.Host {
                    override val lat = latitude
                    override val lon = longitude
                    override val personalStationWeight = personalStationWeightProvider.currentWeight()

                    override suspend fun dailyHistory(startMs: Long, endMs: Long) =
                        stationActualsStore.dailyHistoryInRange(latitude, longitude, startMs, endMs)

                    override suspend fun stations() = observationSource.stationsForLocation(latitude, longitude)

                    override suspend fun nwsHourly(dayStartMs: Long, dayEndMs: Long) =
                        stationActualsStore.nwsHourlyForecastsForDay(latitude, longitude, dayStartMs, dayEndMs)

                    // Reuses NwsObservationSource.toEntity so readings carry the distanceKm /
                    // stationType enrichment StationDailyExtremes needs; nothing is stored.
                    override suspend fun stationDay(station: NwsApi.StationInfo, startIso: String, endIso: String) =
                        observationSource.fetchApiObservationsOnly(station, latitude, longitude, startIso, endIso)
                            .map { it.toReading() }

                    override suspend fun storedObservations(startMs: Long, endMs: Long) =
                        stationActualsStore.storedObservations(latitude, longitude, startMs, endMs)

                    override suspend fun upsert(rows: List<com.weatherwidget.data.model.DailyHistory>) =
                        stationActualsStore.upsertDailyHistory(rows)

                    override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
                },
                nowMs = System.currentTimeMillis(),
            )
    }

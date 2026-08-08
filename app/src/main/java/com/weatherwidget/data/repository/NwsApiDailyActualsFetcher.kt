package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.actuals.NwsDailyExtremesFetch
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
        private val dailyActualsStore: DailyActualsStore,
        private val appLogDao: AppLogDao,
        private val personalStationWeightProvider: PersonalStationWeightProvider,
    ) {
        suspend fun fillMissingIfNeeded(latitude: Double, longitude: Double) {
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val today = LocalDate.now(zone)
            val startMs = today.minusDays(NwsDailyExtremesFetch.MAX_LOOKBACK_DAYS)
                .toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val endMs = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY

            val missing = dailyActualsStore
                .findNwsDatesMissingStationActuals(latitude, longitude, startMs, endMs)
            if (missing.isEmpty()) return

            // Personal stations included: the blend interpolates across all of them, discounted by
            // the user's Personal Weather Stations preference. Only the api actual is
            // official-only, and StationDailyExtremes enforces that from the same pool.
            val stations = observationSource
                .stationsForLocation(latitude, longitude)
                .take(MAX_STATIONS)
            if (stations.isEmpty()) {
                appLogDao.log("NWS_STATION_ACTUALS_FAIL", "reason=no_stations", "WARN")
                return
            }
            val stationsById = stations.associateBy { it.id }

            val resolved = NwsDailyExtremesFetch.resolveForDates(
                datesEpochDayMs = missing,
                stationIdsNearestFirst = stations.map { it.id },
                userLat = latitude,
                userLon = longitude,
                personalStationWeight = personalStationWeightProvider.currentWeight(),
                zone = zone,
                nowMs = now,
                hourlyForecastsForDay = { dayStartMs, dayEndMs ->
                    dailyActualsStore.nwsHourlyForecastsForDay(latitude, longitude, dayStartMs, dayEndMs)
                },
            ) { stationId, startIso, endIso ->
                val station = stationsById[stationId] ?: return@resolveForDates emptyList()
                // null == request failed (retry later); emptyList == answered with nothing.
                fetchStationDay(station, latitude, longitude, startIso, endIso)
            }

            val pulled = resolved
                .mapNotNull { (date, outcome) ->
                    (outcome as? NwsDailyExtremesFetch.DayOutcome.Resolved)?.let { date to it.actuals }
                }
                .toMap()
            if (pulled.isNotEmpty()) {
                dailyActualsStore.persistNwsDailyActuals(latitude, longitude, pulled)
            }

            // Only Insufficient falls back. Unavailable means a request failed, so the date stays
            // in the missing set and retries rather than locking in a cached value over a live one.
            val insufficient = resolved
                .filterValues { it is NwsDailyExtremesFetch.DayOutcome.Insufficient }
                .keys
            val cached = insufficient.mapNotNull { dateMs ->
                val date = LocalDate.ofEpochDay(dateMs / WidgetConstants.MS_IN_A_DAY)
                dailyActualsStore
                    .stationExtremeFromStoredObservations(latitude, longitude, date, zone)
                    ?.let { dateMs to it }
            }.toMap()
            if (cached.isNotEmpty()) {
                dailyActualsStore.persistCachedStationActuals(latitude, longitude, cached)
            }

            val unavailable = resolved.count { it.value is NwsDailyExtremesFetch.DayOutcome.Unavailable }
            appLogDao.log(
                "NWS_STATION_ACTUALS_OUTCOME",
                "requested=${missing.size} stations=${stations.size} " +
                    "pulled=${pulled.size} cached=${cached.size} " +
                    "insufficientUnresolved=${insufficient.size - cached.size} unavailable=$unavailable",
                "DEBUG",
            )
        }

        /**
         * One station, one calendar day. Reuses [NwsObservationSource.toEntity] so the readings
         * carry the same `distanceKm`/`stationType` enrichment the stored rows get —
         * `StationDailyExtremes` needs both. Nothing is written to the observations table: these
         * readings exist only to be reduced to a high and a low, then discarded.
         *
         * Returns **null** when the request itself failed and an empty list when it answered with
         * nothing. The caller must keep these apart: a failed request makes the day Unavailable and
         * retryable, while an answered-but-thin day is Insufficient and falls back to our stored
         * observations. Collapsing them would let one network blip permanently substitute a cached
         * value for a live one.
         */
        private suspend fun fetchStationDay(
            station: NwsApi.StationInfo,
            latitude: Double,
            longitude: Double,
            startIso: String,
            endIso: String,
        ): List<ObservationReading>? =
            try {
                observationSource
                    .fetchApiObservationsOnly(station, latitude, longitude, startIso, endIso)
                    .map { it.toReading() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "station ${station.id} day fetch failed", e)
                appLogDao.log(
                    "NWS_STATION_ACTUALS_FAIL",
                    "station=${station.id} error=${e::class.simpleName}:${e.message}",
                    "WARN",
                )
                null
            }

        private companion object {
            /** Matches the station depth the observation backfill already uses. */
            const val MAX_STATIONS = 5
        }
    }

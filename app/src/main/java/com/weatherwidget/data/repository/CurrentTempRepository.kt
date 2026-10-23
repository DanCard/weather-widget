package com.weatherwidget.data.repository

import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.data.remote.FetchErrorCode
import android.content.Context
import android.location.Location
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.withQuantizedLocation
import com.weatherwidget.data.local.toEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.ApiKeyRedaction
import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.shared.observations.CurrentConditionsFetcher
import com.weatherwidget.shared.observations.CurrentConditionsRun
import com.weatherwidget.shared.util.SpatialInterpolator
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.util.NetworkRestrictionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val TAG = "CurrentTempRepository"

@Singleton
class CurrentTempRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val observationDao: ObservationDao,
        private val hourlyForecastDao: HourlyForecastDao,
        private val appLogDao: AppLogDao,
        private val nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        private val weatherApi: WeatherApi,
        private val widgetStateManager: WidgetStateManager,
        private val dailyHistoryDao: DailyHistoryDao,
        private val observationRepository: ObservationRepository,
        private val tomorrowIoApi: TomorrowIoApi? = null,
        private val openWeatherMapApi: OpenWeatherMapApi? = null,
    ) {
        /** The model feeds' current conditions, shared with desktop (plans/261013-shared-current-conditions-fetch.md). */
        private val currentConditions = CurrentConditionsFetcher.of(openMeteoApi, weatherApi, openWeatherMapApi, tomorrowIoApi)
        companion object {
            private val logTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
            private fun formatLogTime(ms: Long?): String = if (ms == null) "none" else logTimeFormatter.format(Instant.ofEpochMilli(ms))
        }

        /**
         * The run itself — feeds, throttle, freshness gate, records, `CURR_FETCH_*` rows — is `:shared`
         * [CurrentConditionsRun], which desktop runs too (plans/261013-shared-current-conditions-fetch.md
         * Phase 3). This side supplies the stores and the per-feed fetch.
         */
        private val run = CurrentConditionsRun(
            object : CurrentConditionsRun.Host {
                override fun visibleSources() = widgetStateManager.getVisibleSourcesOrder()
                override fun displayedSourceIds() = widgetStateManager.getActiveDisplaySourceIds()
                override fun lastRunMs(latitude: Double, longitude: Double) =
                    FetchMetadata.getLastCurrentTempFetchTime(context, latitude, longitude)
                override fun setLastRunMs(latitude: Double, longitude: Double, ms: Long) =
                    FetchMetadata.setLastCurrentTempFetchTime(context, latitude, longitude, ms)
                override fun lowPriorityDue(feedId: String, intervalMs: Long) =
                    widgetStateManager.shouldFetchCurrentTempForSource(feedId, intervalMs)
                override fun markAttempt(feedId: String) = widgetStateManager.markCurrentTempFetched(feedId)
                override suspend fun fetch(feed: WeatherSource, latitude: Double, longitude: Double) =
                    fetchFromSource(feed, latitude, longitude)?.let {
                        CurrentConditionsRun.Reading(it.source, it.temperature, it.condition, it.observedAt)
                    }
                override fun recordSuccess(feed: WeatherSource) = widgetStateManager.recordSourceFetchSuccess(feed)
                override fun recordFailure(feed: WeatherSource, error: Exception) =
                    widgetStateManager.recordSourceFetchFailure(
                        feed,
                        extractCurrentErrorCode(error),
                        ApiKeyRedaction.redact(error.message ?: error.javaClass.simpleName),
                    )
                override suspend fun log(tag: String, message: String, level: String) = appLogDao.log(tag, message, level)
            },
        )

        suspend fun refreshCurrentTemperature(
            latitude: Double,
            longitude: Double,
            source: WeatherSource? = null,
            reason: String = "unspecified",
            forceRefresh: Boolean = false,
        ): Result<Int> = run.run(latitude, longitude, source, reason, forceRefresh)

        private suspend fun fetchFromSource(source: WeatherSource, latitude: Double, longitude: Double): CurrentReadingPayload? =
            when (source) {
                WeatherSource.NWS -> observationRepository.fetchNwsCurrent(latitude, longitude)
                in CurrentConditionsFetcher.FEEDS -> fetchCurrentConditions(source, latitude, longitude)
                else -> null
            }

        /**
         * A model feed through the shared [CurrentConditionsFetcher]; this side stores the rows and logs.
         * A failed request the provider did not refuse is "no reading", not a source failure, as before
         * the move: only an [ApiAccessException] reaches the failure record (Phase 3 sets one rule).
         */
        private suspend fun fetchCurrentConditions(feed: WeatherSource, latitude: Double, longitude: Double): CurrentReadingPayload? {
            val result = try {
                currentConditions.fetch(feed, latitude, longitude)
            } catch (e: ApiAccessException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return null
            val entities = result.readings.map { it.toEntity() }
            if (feed == WeatherSource.TOMORROW_IO) {
                storeTomorrowIoHistory(entities, latitude, longitude)
            } else {
                entities.forEach { insertCurrentObservation(it) }
            }
            return result.current?.let { CurrentReadingPayload(it.source, it.temperature, it.condition, it.observedAt) }
        }

        /** Tomorrow.io's five-minute window: one batch, with how many rows it replaced, then the retired-product cleanup. */
        private suspend fun storeTomorrowIoHistory(entities: List<ObservationEntity>, latitude: Double, longitude: Double) {
            val replacementCount = if (entities.isEmpty()) {
                0
            } else {
                val sample = entities.first()
                TomorrowIoActuals.replacements(
                    entities.map { it.readingKey() },
                    observationDao.getObservationsInRange(
                        entities.minOf { it.timestamp },
                        entities.maxOf { it.timestamp },
                        sample.locationLat,
                        sample.locationLon,
                        listOf(WeatherSource.TOMORROW_IO.id),
                    ).map { it.readingKey() },
                )
            }
            if (entities.isNotEmpty()) observationDao.insertAll(entities)
            appLogDao.log(
                TomorrowIoActuals.FETCH_TAG,
                TomorrowIoActuals.fetchLine(
                    TomorrowIoApi.INCREMENTAL_ACTUALS_LOOKBACK_HOURS,
                    entities.size,
                    entities.minOfOrNull { it.timestamp },
                    entities.maxOfOrNull { it.timestamp },
                    replacementCount,
                ),
                "INFO",
            )
            RetiredProductCleanupRunner.runFor(
                api = WeatherSource.TOMORROW_IO.id,
                latitude = latitude,
                longitude = longitude,
                observationDao = observationDao,
                dailyHistoryDao = dailyHistoryDao,
                appLogDao = appLogDao,
            )
        }

        private fun ObservationEntity.readingKey() =
            TomorrowIoActuals.ReadingKey(stationId, timestamp, api, locationLat, locationLon)

        private fun extractCurrentErrorCode(exception: Exception): String =
            FetchErrorCode.of(exception) { NetworkRestrictionHelper.isBackgroundDataRestricted(context) }

        private suspend fun insertCurrentObservation(obsEntity: ObservationEntity) {
            // Snap the device location to the shared write-key grid so Open-Meteo/Tomorrow current
            // obs key the same site as NWS instead of a raw-double fragment (plan 260721, Fix A).
            val quantized = obsEntity.withQuantizedLocation()
            observationDao.insertAll(listOf(quantized))
            logCurrentObservationInsert(quantized)
        }

        private suspend fun logCurrentObservationInsert(obsEntity: ObservationEntity) {
            val nowMs = System.currentTimeMillis()
            val observedTimeStr = formatLogTime(obsEntity.timestamp)
            val fetchTimeStr = formatLogTime(obsEntity.fetchedAt)
            appLogDao.log(
                "OBS_CURRENT_INSERT",
                "source=${obsEntity.api} station=${obsEntity.stationId} timestamp=$observedTimeStr " +
                    "fetchedAt=$fetchTimeStr temp=${obsEntity.temperature} " +
                    "timestampAgeMin=${(nowMs - obsEntity.timestamp) / 60_000L} " +
                    "fetchAgeMin=${(nowMs - obsEntity.fetchedAt) / 60_000L}",
                "INFO",
            )
        }

        suspend fun getInterpolatedTemperature(
            latitude: Double,
            longitude: Double,
            time: LocalDateTime = LocalDateTime.now(),
        ): Float? {
            val zoneId = ZoneId.systemDefault()
            val startMs = time.minusHours(3).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
            val endMs = time.plusHours(3).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
            val hourlyForecasts = hourlyForecastDao.getHourlyForecasts(startMs, endMs, latitude, longitude)

            return if (hourlyForecasts.isEmpty()) null
            else TemperatureInterpolator.getInterpolatedTemperature(
                hourlyForecasts.map { it.toHourlyForecast() },
                time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )
        }

        suspend fun getNextInterpolationUpdateTime(
            latitude: Double,
            longitude: Double,
            time: LocalDateTime = LocalDateTime.now(),
        ): LocalDateTime {
            val zoneId = ZoneId.systemDefault()
            val currentHourMs = time.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .atZone(zoneId).toInstant().toEpochMilli()
            val nextHourMs = time.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1)
                .atZone(zoneId).toInstant().toEpochMilli()
            val forecasts = hourlyForecastDao.getHourlyForecasts(currentHourMs, nextHourMs, latitude, longitude)
            
            val tempDiff = if (forecasts.size >= 2) (forecasts[1].temperature - forecasts[0].temperature).toInt() else 0
            val nextEpochMs = TemperatureInterpolator.getNextUpdateTime(time.atZone(zoneId).toInstant().toEpochMilli(), tempDiff)
            return Instant.ofEpochMilli(nextEpochMs).atZone(zoneId).toLocalDateTime()
        }

        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0]
        }
    }

internal data class CurrentReadingPayload(
    val source: WeatherSource, 
    val temperature: Float, 
    val condition: String?, 
    val observedAt: Long?
)

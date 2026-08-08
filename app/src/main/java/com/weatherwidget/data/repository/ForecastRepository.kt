package com.weatherwidget.data.repository

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.getForecastsInRange
import com.weatherwidget.data.local.getLatestForecastsInRange
import com.weatherwidget.data.local.getLatestForecastsInRangeForSources
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.data.remote.VisualCrossingApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.widget.ForecastFetchContext
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.MonthDay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public forecast-data facade.
 *
 * Fetch coordination and persistence policy live in focused collaborators. This class keeps the
 * externally used API, the full-fetch mutex/throttle, and thin DAO query delegates.
 */
@Singleton
@Suppress("LongParameterList", "UNUSED_PARAMETER")
class ForecastRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val forecastDao: ForecastDao,
        hourlyForecastDao: HourlyForecastDao,
        hourlyForecastHistoryDao: HourlyForecastHistoryDao,
        private val appLogDao: AppLogDao,
        nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        visualCrossingApi: VisualCrossingApi,
        weatherApi: WeatherApi,
        silurianApi: SilurianApi,
        private val widgetStateManager: WidgetStateManager,
        private val climateNormalDao: ClimateNormalDao,
        private val observationDao: ObservationDao,
        dailyHistoryDao: DailyHistoryDao,
        observationRepository: ObservationRepository,
        tomorrowIoApi: TomorrowIoApi? = null,
        openWeatherMapApi: OpenWeatherMapApi? = null,
        nwsForecastMapper: NwsForecastMapper,
        dailyActualsStore: DailyActualsStore,
        // Null only in unit tests that never exercise a network fetch; production DI always
        // supplies it (see AppModule.provideForecastRepository).
        nwsApiDailyActualsFetcher: NwsApiDailyActualsFetcher? = null,
    ) {
        private val syncMutex = Mutex()
        private val snapshotStore = ForecastSnapshotStore(
            forecastDao = forecastDao,
            appLogDao = appLogDao,
            widgetStateManager = widgetStateManager,
            gapFiller = ClimateGapFiller(climateNormalDao),
        )
        private val hourlyStore = HourlyForecastStore(
            hourlyForecastDao = hourlyForecastDao,
            hourlyForecastHistoryDao = hourlyForecastHistoryDao,
            observationDao = observationDao,
            widgetStateManager = widgetStateManager,
        )
        private val dailyHistorySnapshotter = DailyHistorySnapshotter(
            context = context,
            forecastDao = forecastDao,
            hourlyForecastDao = hourlyForecastDao,
            hourlyForecastHistoryDao = hourlyForecastHistoryDao,
            dailyHistoryDao = dailyHistoryDao,
            appLogDao = appLogDao,
        )
        private val weatherApiHistoryBackfiller = WeatherApiHistoryBackfiller(
            weatherApi = weatherApi,
            observationDao = observationDao,
            hourlyStore = hourlyStore,
            observationRepository = observationRepository,
            appLogDao = appLogDao,
        )
        private val fetchCoordinator = ForecastFetchCoordinator(
            context = context,
            appLogDao = appLogDao,
            openMeteoApi = openMeteoApi,
            visualCrossingApi = visualCrossingApi,
            weatherApi = weatherApi,
            silurianApi = silurianApi,
            widgetStateManager = widgetStateManager,
            tomorrowIoApi = tomorrowIoApi,
            openWeatherMapApi = openWeatherMapApi,
            nwsForecastMapper = nwsForecastMapper,
            snapshotStore = snapshotStore,
            hourlyStore = hourlyStore,
            weatherApiHistoryBackfiller = weatherApiHistoryBackfiller,
            dailyActualsStore = dailyActualsStore,
            nwsApiDailyActualsFetcher = nwsApiDailyActualsFetcher,
        )
        private val retentionManager = WeatherRetentionManager(
            forecastDao = forecastDao,
            hourlyForecastDao = hourlyForecastDao,
            hourlyForecastHistoryDao = hourlyForecastHistoryDao,
            observationDao = observationDao,
            dailyHistoryDao = dailyHistoryDao,
            appLogDao = appLogDao,
        )
        private val climateNormalsRepository = ClimateNormalsRepository(
            climateNormalDao = climateNormalDao,
            openMeteoApi = openMeteoApi,
            widgetStateManager = widgetStateManager,
            appLogDao = appLogDao,
        )

        private var lastFetchTime: Long
            get() = FetchMetadata.getLastFullFetchTime(context)
            set(value) = FetchMetadata.setLastFullFetchTime(context, value)

        suspend fun getWeatherData(
            latitude: Double,
            longitude: Double,
            forceRefresh: Boolean = false,
            networkAllowed: Boolean = true,
            targetSourceId: String? = null,
            fetchContext: ForecastFetchContext? = null,
        ): Result<List<ForecastEntity>> {
            val fetchStartTime = System.currentTimeMillis()
            try {
                var cachedForecasts = getCachedData(latitude, longitude)
                if (
                    !forceRefresh &&
                    !fetchCoordinator.requiresNetworkFetch(
                        cachedForecasts,
                        fetchContext,
                    )
                ) {
                    return Result.success(cachedForecasts)
                }
                if (!networkAllowed) return Result.success(cachedForecasts)

                syncMutex.withLock {
                    cachedForecasts = getCachedData(latitude, longitude)
                    if (
                        !forceRefresh &&
                        !fetchCoordinator.requiresNetworkFetch(
                            cachedForecasts,
                            fetchContext,
                        )
                    ) {
                        return Result.success(cachedForecasts)
                    }
                    val timeSinceLastFetch =
                        System.currentTimeMillis() - lastFetchTime
                    if (
                        !forceRefresh &&
                        timeSinceLastFetch < MIN_NETWORK_INTERVAL_MS &&
                        cachedForecasts.isNotEmpty()
                    ) {
                        return Result.success(cachedForecasts)
                    }

                    appLogDao.log(
                        "NET_FETCH_START",
                        "force=$forceRefresh target=$targetSourceId " +
                            "ctx=${fetchContext?.let {
                                "charging=${it.isCharging}," +
                                    "screen=${it.isScreenInteractive}," +
                                    "batt=${it.batteryLevel}," +
                                    "active=${it.activeSourceIds}"
                            } ?: "none"}",
                    )
                    if (
                        forceRefresh &&
                        fetchCoordinator.isTargetSourceDisabled(targetSourceId)
                    ) {
                        appLogDao.log(
                            "NET_FETCH_SKIP_DISABLED",
                            "target=$targetSourceId",
                        )
                        return Result.success(cachedForecasts)
                    }
                    val sourcesToFetch = fetchCoordinator.visibleSourcesToFetch(
                        cachedForecasts = cachedForecasts,
                        forceRefresh = forceRefresh,
                        targetSourceId = targetSourceId,
                        fetchContext = fetchContext,
                    )
                    fetchCoordinator.fetchFromAllApis(
                        latitude,
                        longitude,
                        sourcesToFetch,
                    )
                    climateNormalsRepository.warmBestEffort(
                        latitude,
                        longitude,
                    )
                    cleanOldData()
                    val totalFetchTime =
                        System.currentTimeMillis() - fetchStartTime
                    lastFetchTime = System.currentTimeMillis()
                    appLogDao.log(
                        "NET_FETCH_COMPLETE",
                        "durationMs=$totalFetchTime sources=" +
                            sourcesToFetch.joinToString(",") { it.id },
                    )
                    return Result.success(getCachedData(latitude, longitude))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                lastFetchTime = 0L
                appLogDao.logException(
                    "NET_FETCH_ERROR",
                    "Network fetch failed",
                    exception,
                )
                val fallbackData = getCachedData(latitude, longitude)
                return if (fallbackData.isNotEmpty()) {
                    Result.success(fallbackData)
                } else {
                    Result.failure(exception)
                }
            }
        }

        internal suspend fun snapshotDisplayedRainChance(
            latitude: Double,
            longitude: Double,
        ) {
            dailyHistorySnapshotter.snapshotDisplayedRainChance(
                latitude,
                longitude,
            )
        }

        internal suspend fun repairFrozenRainChanceIfNeeded(
            latitude: Double,
            longitude: Double,
        ) {
            dailyHistorySnapshotter.repairFrozenRainChanceIfNeeded(
                latitude,
                longitude,
            )
        }

        internal suspend fun backfillForecastChanceSnapshotsIfNeeded(
            latitude: Double,
            longitude: Double,
        ) {
            dailyHistorySnapshotter.backfillForecastChanceSnapshotsIfNeeded(
                latitude,
                longitude,
            )
        }

        internal suspend fun backfillFrozenDisplayColumnsIfNeeded(
            latitude: Double,
            longitude: Double,
        ) {
            dailyHistorySnapshotter.backfillFrozenDisplayColumnsIfNeeded(
                latitude,
                longitude,
            )
        }

        internal suspend fun fetchFromNws(
            latitude: Double,
            longitude: Double,
        ): List<ForecastEntity> {
            return fetchCoordinator.fetchFromNws(latitude, longitude)
        }

        @VisibleForTesting
        internal fun mapDailyForecast(
            day: DailyForecast,
            latitude: Double,
            longitude: Double,
            sourceId: String,
            hourlyForecasts: List<HourlyForecast> = emptyList(),
        ): ForecastEntity {
            return snapshotStore.mapDailyForecast(
                day,
                latitude,
                longitude,
                sourceId,
                hourlyForecasts,
            )
        }

        @VisibleForTesting
        internal suspend fun saveForecastSnapshot(
            weatherForecasts: List<ForecastEntity>,
            latitude: Double,
            longitude: Double,
            sourceId: String,
            batchFetchedAt: Long = System.currentTimeMillis(),
        ) {
            snapshotStore.saveForecastSnapshot(
                weatherForecasts,
                latitude,
                longitude,
                sourceId,
                batchFetchedAt,
            )
        }

        suspend fun getHistoricalNormalsByMonthDay(
            latitude: Double,
            longitude: Double,
        ): Map<MonthDay, Pair<Float, Float>> {
            return climateNormalsRepository.getHistoricalNormalsByMonthDay(
                latitude,
                longitude,
            )
        }

        suspend fun getObservationsInRange(
            startTimestamp: Long,
            endTimestamp: Long,
            latitude: Double,
            longitude: Double,
        ): List<ObservationEntity> {
            return observationDao.getObservationsInRange(
                startTimestamp,
                endTimestamp,
                latitude,
                longitude,
            )
        }

        suspend fun getCachedData(
            latitude: Double,
            longitude: Double,
        ): List<ForecastEntity> {
            return snapshotStore.getCachedData(latitude, longitude)
        }

        suspend fun getCachedDataBySource(
            latitude: Double,
            longitude: Double,
            source: WeatherSource,
        ): List<ForecastEntity> {
            return snapshotStore.getCachedDataBySource(
                latitude,
                longitude,
                source,
            )
        }

        suspend fun getForecastForDate(
            date: Long,
            latitude: Double,
            longitude: Double,
        ): ForecastEntity? {
            return forecastDao.getForecastForDate(date, latitude, longitude)
        }

        suspend fun getForecastForDateBySource(
            date: Long,
            latitude: Double,
            longitude: Double,
            source: WeatherSource,
        ): ForecastEntity? {
            return forecastDao.getForecastsInRangeBySource(
                date,
                date,
                latitude,
                longitude,
                source.id,
            ).firstOrNull()
        }

        suspend fun getForecastsInRange(
            startDate: Long,
            endDate: Long,
            latitude: Double,
            longitude: Double,
        ): List<ForecastEntity> {
            return forecastDao.getForecastsInRange(
                startDate,
                endDate,
                latitude,
                longitude,
            )
        }

        suspend fun getAllForecastsInRange(
            startDate: Long,
            endDate: Long,
            latitude: Double,
            longitude: Double,
        ): List<ForecastEntity> {
            return forecastDao.getAllForecastsInRange(
                startDate,
                endDate,
                latitude,
                longitude,
            )
        }

        suspend fun getAllForecastsInRangeForSources(
            startDate: Long,
            endDate: Long,
            latitude: Double,
            longitude: Double,
            sources: List<String>,
        ): List<ForecastEntity> {
            return forecastDao.getAllForecastsInRangeForSources(
                startDate,
                endDate,
                latitude,
                longitude,
                sources,
            )
        }

        suspend fun getLatestForecastsInRange(
            startDate: Long,
            endDate: Long,
            latitude: Double,
            longitude: Double,
        ): List<ForecastEntity> {
            return forecastDao.getLatestForecastsInRange(
                startDate,
                endDate,
                latitude,
                longitude,
            )
        }

        suspend fun getLatestForecastsInRangeForSources(
            startDate: Long,
            endDate: Long,
            latitude: Double,
            longitude: Double,
            sources: List<String>,
        ): List<ForecastEntity> {
            return forecastDao.getLatestForecastsInRangeForSources(
                startDate,
                endDate,
                latitude,
                longitude,
                sources,
            )
        }

        suspend fun cleanOldData() {
            retentionManager.cleanOldData()
        }

        companion object {
            private const val MIN_NETWORK_INTERVAL_MS = 600_000L

            @VisibleForTesting
            internal val APP_LOG_PROTECTED_TAGS =
                WeatherRetentionManager.APP_LOG_PROTECTED_TAGS

            @VisibleForTesting
            internal fun hasMeaningfulHourlyChange(
                existing: HourlyForecastEntity?,
                newlyFetched: HourlyForecastEntity,
            ): Boolean = HourlyForecastStore.hasMeaningfulHourlyChange(
                existing,
                newlyFetched,
            )

            @VisibleForTesting
            internal fun siteExactExistingByDateTime(
                boxRows: List<HourlyForecastEntity>,
                lat: Double,
                lon: Double,
            ): Map<Long, HourlyForecastEntity> =
                HourlyForecastStore.siteExactExistingByDateTime(
                    boxRows,
                    lat,
                    lon,
                )

            @VisibleForTesting
            internal fun siteExactLatestForecastByDate(
                boxRows: List<ForecastEntity>,
                lat: Double,
                lon: Double,
            ): Map<Long, ForecastEntity> =
                ForecastSnapshotStore.siteExactLatestForecastByDate(
                    boxRows,
                    lat,
                    lon,
                )

            @VisibleForTesting
            internal fun mergePreservingNullableFields(
                existing: HourlyForecastEntity?,
                newlyFetched: HourlyForecastEntity,
            ): HourlyForecastEntity =
                HourlyForecastStore.mergePreservingNullableFields(
                    existing,
                    newlyFetched,
                )
        }
    }

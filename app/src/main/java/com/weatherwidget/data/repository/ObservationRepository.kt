package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.widget.DailyActualsBySource
import java.time.LocalDate
import javax.inject.Singleton

private data class ObservationComponents(
    val currentUpdater: NwsCurrentObservationUpdater,
    val backfiller: NwsObservationBackfiller,
    val dailyActualsStore: DailyActualsStore,
    val currentReader: CurrentObservationReader,
)

@Singleton
class ObservationRepository private constructor(
    private val observationDao: ObservationDao,
    private val components: ObservationComponents,
) {
    constructor(
        observationDao: ObservationDao,
        currentUpdater: NwsCurrentObservationUpdater,
        backfiller: NwsObservationBackfiller,
        dailyActualsStore: DailyActualsStore,
        currentReader: CurrentObservationReader,
    ) : this(
        observationDao,
        ObservationComponents(currentUpdater, backfiller, dailyActualsStore, currentReader),
    )

    /**
     * Compatibility construction seam retained for repository tests while production Hilt wiring
     * injects the cohesive collaborators above.
     */
    internal constructor(
        context: Context,
        observationDao: ObservationDao,
        dailyHistoryDao: DailyHistoryDao,
        appLogDao: AppLogDao,
        nwsApi: NwsApi,
        hourlyForecastDao: HourlyForecastDao,
        synopticApi: SynopticApi? = null,
    ) : this(
        observationDao,
        legacyComponents(
            context,
            observationDao,
            dailyHistoryDao,
            appLogDao,
            nwsApi,
            hourlyForecastDao,
            synopticApi,
        ),
    )

    internal suspend fun fetchNwsCurrent(
        latitude: Double,
        longitude: Double,
    ): CurrentReadingPayload? =
        components.currentUpdater.fetchNwsCurrent(latitude, longitude)

    internal suspend fun backfillNwsObservationsIfNeeded(
        latitude: Double,
        longitude: Double,
    ) = components.backfiller.backfillNwsObservationsIfNeeded(latitude, longitude)

    internal suspend fun backfillRecentNwsObservations(
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
    ): RecentBackfillResult =
        components.backfiller.backfillRecentNwsObservations(latitude, longitude, lookbackHours)

    internal suspend fun recomputeDailyExtremesFromStoredObservations(
        latitude: Double,
        longitude: Double,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
        force: Boolean = false,
    ) = components.dailyActualsStore.recomputeDailyExtremesFromStoredObservations(
        latitude,
        longitude,
        startDate,
        endDateInclusive,
        hourlyForecasts,
        force,
    )

    suspend fun getDailyActualsWithLiveToday(
        latitude: Double,
        longitude: Double,
        hourlyForecasts: List<HourlyForecastEntity>,
        activeSourceList: List<String>,
    ): DailyActualsBySource =
        components.dailyActualsStore.getDailyActualsWithLiveToday(
            latitude,
            longitude,
            hourlyForecasts,
            activeSourceList,
        )

    suspend fun getRecentObservations(sinceMs: Long): List<ObservationEntity> =
        observationDao.getRecentObservations(sinceMs)

    suspend fun getRecentObservationsNear(
        sinceMs: Long,
        latitude: Double,
        longitude: Double,
    ): List<ObservationEntity> =
        observationDao.getRecentObservationsNear(sinceMs, latitude, longitude)

    suspend fun getMainObservationsWithComputedNwsBlend(
        latitude: Double,
        longitude: Double,
        sinceMs: Long,
    ): List<ObservationEntity> =
        components.currentReader.getMainObservationsWithComputedNwsBlend(
            latitude,
            longitude,
            sinceMs,
        )
}

private fun legacyComponents(
    context: Context,
    observationDao: ObservationDao,
    dailyHistoryDao: DailyHistoryDao,
    appLogDao: AppLogDao,
    nwsApi: NwsApi,
    hourlyForecastDao: HourlyForecastDao,
    synopticApi: SynopticApi?,
): ObservationComponents {
    val weightProvider = WidgetPersonalStationWeightProvider(context)
    val dailyActualsStore = DailyActualsStore(
        observationDao,
        dailyHistoryDao,
        appLogDao,
        hourlyForecastDao,
        weightProvider,
    )
    val source = NwsObservationSource(context, nwsApi, appLogDao, synopticApi)
    return ObservationComponents(
        currentUpdater = NwsCurrentObservationUpdater(
            source,
            observationDao,
            appLogDao,
            dailyActualsStore,
        ),
        backfiller = NwsObservationBackfiller(
            source,
            observationDao,
            dailyHistoryDao,
            appLogDao,
            dailyActualsStore,
        ),
        dailyActualsStore = dailyActualsStore,
        currentReader = CurrentObservationReader(observationDao),
    )
}

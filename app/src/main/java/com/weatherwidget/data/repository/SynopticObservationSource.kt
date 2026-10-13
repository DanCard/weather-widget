package com.weatherwidget.data.repository

import com.weatherwidget.data.local.toEntity
import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.SynopticApi
import com.weatherwidget.shared.observations.SynopticObservationFetcher

/**
 * Android binding for [SynopticObservationFetcher]: `app_logs` sink and conversion to Room's [ObservationEntity].
 */
class SynopticObservationSource(
    context: Context,
    api: SynopticApi,
    private val appLogDao: AppLogDao,
) {
    private val fetcher = SynopticObservationFetcher(api) { tag, message, level ->
        appLogDao.log(tag, message, level)
    }

    suspend fun fetchObservations(
        latitude: Double,
        longitude: Double,
        radiusMiles: Double = SynopticObservationFetcher.DEFAULT_RADIUS_MILES,
        hours: Int = 2,
        limit: Int = SynopticObservationFetcher.DEFAULT_LIMIT,
        recentMinutes: Long? = null,
    ): List<ObservationEntity> =
        fetcher.fetchObservations(latitude, longitude, radiusMiles, hours, limit, recentMinutes).map { it.toEntity() }

    suspend fun fetchObservationsResult(
        latitude: Double,
        longitude: Double,
        radiusMiles: Double = SynopticObservationFetcher.DEFAULT_RADIUS_MILES,
        hours: Int = 2,
        limit: Int = SynopticObservationFetcher.DEFAULT_LIMIT,
        recentMinutes: Long? = null,
    ): FetchOutcome<List<ObservationEntity>> =
        when (val outcome = fetcher.fetchObservationsResult(latitude, longitude, radiusMiles, hours, limit, recentMinutes)) {
            is FetchOutcome.Success -> FetchOutcome.Success(outcome.value.map { it.toEntity() })
            is FetchOutcome.NoData -> FetchOutcome.NoData
            is FetchOutcome.Failed -> outcome
        }
}

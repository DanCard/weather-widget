package com.weatherwidget.data.repository

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.actuals.DailyActualsAssembler
import com.weatherwidget.shared.actuals.DailyBlendRecomputeRun
import com.weatherwidget.shared.actuals.InMemoryReducedSignatureStore
import com.weatherwidget.shared.actuals.ReducedSignatureStore
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.WidgetConstants
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DailyActualsStore"

@Singleton
class DailyActualsStore @Inject constructor(
    private val observationDao: ObservationDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val personalStationWeightProvider: PersonalStationWeightProvider,
    /**
     * Last observation signature a day was successfully reduced from, keyed by (day, quantized
     * location). Was a within-process map on the reasoning that losing it on process death costs
     * "exactly one redundant recompute" — which turned out to be 12 s of cold CPU at the worst
     * moment (see [ReducedSignatureStore]). Hilt supplies the persisted one; the in-memory default
     * keeps manual construction and tests unchanged.
     *
     * Concurrent because recomputes for different days overlap on the sync's dispatcher.
     */
    private val reducedSignatures: ReducedSignatureStore = InMemoryReducedSignatureStore(),
) {
    suspend fun getDailyActualsWithLiveToday(
        latitude: Double,
        longitude: Double,
        hourlyForecasts: List<HourlyForecastEntity>,
        activeSourceList: List<String>,
        /** Past days to read; the widget's 30, the months screen the whole record. */
        lookbackDays: Long = DEFAULT_LOOKBACK_DAYS,
    ): DailyActualsBySource {
        val sources = activeSourceList.map(WeatherSource::fromId)
        if (sources.isEmpty()) return emptyMap()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val startDate = today.minusDays(lookbackDays).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endDate = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // Composition is shared with desktop (DailyActualsAssembler); this only reads and logs.
        val result = DailyActualsAssembler.assemble(
            activeSources = sources,
            pastRows = dailyHistoryDao.getExtremesInRange(startDate, endDate, latitude, longitude)
                .map { it.toDailyHistory() },
            yesterdayDonors = dailyHistoryDao.getMeasuredExtremesForDateAnySite(endDate)
                .map { it.toDailyHistory() },
            observations = observationDao
                .getObservationsInRange(
                    todayStartMs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS,
                    tomorrowMs,
                    latitude,
                    longitude,
                    // Unscoped: this pass indexes actuals for every configured source at once, which
                    // is precisely the case the DAO's note says must NOT be narrowed.
                    apis = null,
                )
                .map { it.toReading() },
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            latitude = latitude,
            longitude = longitude,
            today = today,
            zone = zone,
            nowMs = System.currentTimeMillis(),
            personalStationWeight = personalStationWeightProvider.currentWeight(),
        )

        result.bySource.forEach { (source, byDate) ->
            byDate.values.filter { it.isActualsBorrowed }.forEach {
                Log.d(
                    TAG,
                    "PREVIOUS_SITE_HISTORY source=$source date=${it.toLocalDate()} km=${"%.0f".format(it.actualsBorrowedFromKm)} " +
                        "high=${it.computedHighTemp} low=${it.computedLowTemp} wholeRow=${it.borrowedWithoutLocalRow}",
                )
            }
        }
        val obsSpanSummary = result.todayObsSpan
            ?.let { (first, last) -> "${DailyActualsAssembler.formatLocal(first, zone)}..${DailyActualsAssembler.formatLocal(last, zone)}" }
            ?: "none"
        val liveSummary = result.live
            .joinToString("; ") { "${it.source}[blendedHigh=${it.high},blendedLow=${it.low},rows=${it.rows}]" }
            .ifEmpty { "none" }
        Log.d(
            TAG,
            "getDailyActualsWithLiveToday: date=$today lat=$latitude lon=$longitude " +
                "todayObsRows=${result.todayObsRows} span=$obsSpanSummary live=[$liveSummary]",
        )
        DailyActualsAssembler.suppressedLowLines(result, latitude, longitude, zone).forEach { (tag, message, level) ->
            Log.d(TAG, "$tag $message")
            appLogDao.log(tag, message, level)
        }
        return result.bySource
    }

    /** `:shared` [DailyBlendRecomputeRun] over this site's Room host. */
    internal fun blendRecomputeHost(latitude: Double, longitude: Double) = RoomDailyBlendRecomputeHost(
        lat = latitude,
        lon = longitude,
        observationDao = observationDao,
        dailyHistoryDao = dailyHistoryDao,
        hourlyForecastDao = hourlyForecastDao,
        appLogDao = appLogDao,
        personalStationWeightProvider = personalStationWeightProvider,
        signatures = reducedSignatures,
    )

    internal suspend fun recomputeDailyExtremesFromStoredObservations(
        latitude: Double,
        longitude: Double,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        /** See [recomputeDailyExtremesForDay]; true for the repair paths. */
        force: Boolean = false,
    ) {
        DailyBlendRecomputeRun.range(
            blendRecomputeHost(latitude, longitude), startDate, endDateInclusive, System.currentTimeMillis(), force = force,
        )
    }

    internal suspend fun recomputeDailyExtremesForDay(
        latitude: Double,
        longitude: Double,
        date: LocalDate,
        /**
         * Recompute even when nothing has been written for the day since the last one.
         *
         * For the repair paths — the history screen and the backfillers — which call this precisely
         * because they distrust what is stored, and whose whole purpose the skip would defeat.
         */
        force: Boolean = false,
    ) {
        DailyBlendRecomputeRun.day(blendRecomputeHost(latitude, longitude), date, System.currentTimeMillis(), force = force)
    }

    companion object {
        /** The widget's daily view: navigation reaches 30 days back. */
        const val DEFAULT_LOOKBACK_DAYS = 30L
    }
}

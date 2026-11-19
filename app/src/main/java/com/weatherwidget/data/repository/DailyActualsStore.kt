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
import com.weatherwidget.data.model.DailyHistory
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
        val sources = activeSourceList.map(WeatherSource::fromId).distinct()
        if (sources.isEmpty()) return emptyMap()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val weight = personalStationWeightProvider.currentWeight()
        val nowElapsedMs = android.os.SystemClock.elapsedRealtime()

        // Serve any already-cached sources from the versioned in-memory cache (invalidated on any
        // StoredDataVersion bump or after LIVE_CACHE_TTL_MS), and only read + assemble the missing
        // ones (performance/261118-android-api-toggle-tail-latency.md).
        val daoId = System.identityHashCode(observationDao)
        val cachedBySource = LinkedHashMap<String, Map<LocalDate, DailyHistory>>()
        val missingSources = mutableListOf<WeatherSource>()
        for (source in sources) {
            val key = sourceCacheKey(daoId, source, latitude, longitude, today.toEpochDay(), lookbackDays, weight)
            val hit = liveSourceCache.get(key, nowElapsedMs)
            if (hit != null) {
                cachedBySource[source.id] = hit
            } else {
                missingSources += source
            }
        }
        if (missingSources.isEmpty()) {
            return cachedBySource
        }

        val startDate = today.minusDays(lookbackDays).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endDate = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val providerApis = observationApisFor(missingSources)

        // Composition is shared with desktop (DailyActualsAssembler); this only reads and logs.
        val readStartMs = android.os.SystemClock.elapsedRealtime()
        val pastRows = dailyHistoryDao.getExtremesInRange(startDate, endDate, latitude, longitude)
            .map { it.toDailyHistory() }
        val yesterdayDonors = dailyHistoryDao.getMeasuredExtremesForDateAnySite(endDate)
            .map { it.toDailyHistory() }
        val observations = if (providerApis.isEmpty()) {
            emptyList()
        } else {
            observationDao
                .getObservationsInRange(
                    todayStartMs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS,
                    tomorrowMs,
                    latitude,
                    longitude,
                    apis = providerApis,
                )
                .map { it.toReading() }
        }
        val assembleStartMs = android.os.SystemClock.elapsedRealtime()
        val result = DailyActualsAssembler.assemble(
            activeSources = missingSources,
            pastRows = pastRows,
            yesterdayDonors = yesterdayDonors,
            observations = observations,
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            latitude = latitude,
            longitude = longitude,
            today = today,
            zone = zone,
            nowMs = System.currentTimeMillis(),
            personalStationWeight = weight,
        )
        val assembleDoneMs = android.os.SystemClock.elapsedRealtime()

        // Cache each assembled source individually so subsequent taps to other sources do not
        // evict or re-blend already-computed sources.
        for (source in missingSources) {
            val rowsForSource = result.bySource[source.id].orEmpty()
            val key = sourceCacheKey(daoId, source, latitude, longitude, today.toEpochDay(), lookbackDays, weight)
            liveSourceCache.put(key, rowsForSource, assembleDoneMs)
            if (rowsForSource.isNotEmpty()) {
                cachedBySource[source.id] = rowsForSource
            }
        }

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
        // Where a slow read went: the cold startup paint's largest step is this function.
        Log.d(
            TAG,
            "DAILY_ACTUALS_PERF readMs=${assembleStartMs - readStartMs} assembleMs=${assembleDoneMs - assembleStartMs} " +
                "obs=${observations.size} pastRows=${pastRows.size} ${result.blend.logFragment()}",
        )
        DailyActualsAssembler.suppressedLowLines(result, latitude, longitude, zone).forEach { (tag, message, level) ->
            Log.d(TAG, "$tag $message")
            appLogDao.log(tag, message, level)
        }
        return cachedBySource
    }

    /**
     * The provisional startup paint's actuals ([DailyActualsAssembler.assembleStored]): stored
     * rows through today, no blend. Reads today's provider observations only for the freshness
     * check and the late-start low gate. The live [getDailyActualsWithLiveToday] replaces it.
     * performance/261118-cold-start-daily-actuals-cost.md
     */
    suspend fun getDailyActualsStored(
        latitude: Double,
        longitude: Double,
        activeSourceList: List<String>,
        lookbackDays: Long = DEFAULT_LOOKBACK_DAYS,
    ): DailyActualsBySource {
        val sources = activeSourceList.map(WeatherSource::fromId)
        if (sources.isEmpty()) return emptyMap()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val startDate = today.minusDays(lookbackDays).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val todayKey = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val providerApis = sources
            .filter { com.weatherwidget.shared.observations.ActualsProviderResolver.hasTemperatureActuals(it) }
            .flatMap { listOf(it.id, com.weatherwidget.shared.observations.ActualsProviderResolver.providerIdFor(it)) }
            .distinct()

        val readStartMs = android.os.SystemClock.elapsedRealtime()
        val rows = dailyHistoryDao.getExtremesInRange(startDate, todayKey, latitude, longitude).map { it.toDailyHistory() }
        val yesterdayDonors = dailyHistoryDao
            .getMeasuredExtremesForDateAnySite(today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY)
            .map { it.toDailyHistory() }
        val todayObservations = if (providerApis.isEmpty()) {
            emptyList()
        } else {
            observationDao.getObservationsInRange(todayStartMs, tomorrowMs, latitude, longitude, apis = providerApis)
                .map { it.toReading() }
        }
        val readMs = android.os.SystemClock.elapsedRealtime() - readStartMs
        val stored = DailyActualsAssembler.assembleStored(
            activeSources = sources,
            pastRows = rows,
            yesterdayDonors = yesterdayDonors,
            todayObservations = todayObservations,
            latitude = latitude,
            longitude = longitude,
            today = today,
            zone = zone,
        )
        val accepted = stored.result.live.joinToString("; ") { "${it.source}[high=${it.high},low=${it.low},rows=${it.rows}]" }
        val rejected = stored.rejected.joinToString("; ") { "${it.source}:${it.reason}" }
        Log.d(
            TAG,
            "DAILY_ACTUALS_STORED readMs=$readMs todayObs=${todayObservations.size} rows=${rows.size} " +
                "today=[${accepted.ifEmpty { "none" }}] rejected=[${rejected.ifEmpty { "none" }}]",
        )
        return stored.result.bySource
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
        const val LIVE_CACHE_TTL_MS = 5 * 60_000L
        private const val COORD_QUANTIZE = 1_000.0

        private data class SourceCacheKey(
            val daoId: Int,
            val sourceId: String,
            val latQ: Long,
            val lonQ: Long,
            val todayEpochDay: Long,
            val lookbackDays: Long,
            val personalStationWeight: Double,
            val actualsProviderId: String,
        )

        private val liveSourceCache = com.weatherwidget.shared.util.VersionedTtlCache<SourceCacheKey, Map<LocalDate, DailyHistory>>(
            LIVE_CACHE_TTL_MS,
        )

        private fun sourceCacheKey(
            daoId: Int,
            source: WeatherSource,
            latitude: Double,
            longitude: Double,
            todayEpochDay: Long,
            lookbackDays: Long,
            personalStationWeight: Double,
        ): SourceCacheKey = SourceCacheKey(
            daoId = daoId,
            sourceId = source.id,
            latQ = Math.round(latitude * COORD_QUANTIZE),
            lonQ = Math.round(longitude * COORD_QUANTIZE),
            todayEpochDay = todayEpochDay,
            lookbackDays = lookbackDays,
            personalStationWeight = personalStationWeight,
            actualsProviderId = com.weatherwidget.shared.observations.ActualsProviderResolver.providerIdFor(source),
        )

        /** Observation `api` tags needed to assemble [sources] (including supplemental METAR for NWS). */
        internal fun observationApisFor(sources: List<WeatherSource>): List<String> =
            sources
                .filter { com.weatherwidget.shared.observations.ActualsProviderResolver.hasTemperatureActuals(it) }
                .flatMap { source ->
                    val providerId = com.weatherwidget.shared.observations.ActualsProviderResolver.providerIdFor(source)
                    buildList {
                        add(source.id)
                        add(providerId)
                        if (providerId == WeatherSource.NWS.id) {
                            add(WeatherSource.METAR.id)
                        }
                    }
                }
                .distinct()

        /** Returns cached live daily actuals only when every requested source is currently in RAM. */
        fun getCachedLive(
            observationDao: ObservationDao,
            latitude: Double,
            longitude: Double,
            activeSourceList: List<String>,
            personalStationWeight: Double,
            todayEpochDay: Long = LocalDate.now().toEpochDay(),
            lookbackDays: Long = DEFAULT_LOOKBACK_DAYS,
            nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime(),
        ): DailyActualsBySource? {
            val sources = activeSourceList.map(WeatherSource::fromId).distinct()
            if (sources.isEmpty()) return emptyMap()
            val daoId = System.identityHashCode(observationDao)
            val out = LinkedHashMap<String, Map<LocalDate, DailyHistory>>()
            for (source in sources) {
                val key = sourceCacheKey(daoId, source, latitude, longitude, todayEpochDay, lookbackDays, personalStationWeight)
                val hit = liveSourceCache.get(key, nowElapsedMs) ?: return null
                if (hit.isNotEmpty()) out[source.id] = hit
            }
            return out
        }

        fun clearCache() {
            liveSourceCache.clear()
        }

        /**
         * A read-side store over [database], for callers without a repository (the widget's tap
         * renders, `DailyInteractionRenderer`). Same shared composition as the worker's, so a tap never
         * paints different actuals — today's fog, gated low, previous-site days — than the refresh
         * before it. plans/261111-tap-render-today-fog-shared-actuals.md
         */
        fun forDatabase(context: android.content.Context, database: com.weatherwidget.data.local.WeatherDatabase) =
            DailyActualsStore(
                database.observationDao(),
                database.dailyHistoryDao(),
                database.appLogDao(),
                database.hourlyForecastDao(),
                WidgetPersonalStationWeightProvider(context),
            )
    }
}

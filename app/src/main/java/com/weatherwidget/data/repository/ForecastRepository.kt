package com.weatherwidget.data.repository

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.getForecastsInRange
import com.weatherwidget.data.local.getLatestForecastsInRange
import com.weatherwidget.data.local.getLatestForecastsInRangeForSources
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.local.toHourlyForecast
     import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.VisualCrossingApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.widget.ForecastFetchContext
import com.weatherwidget.widget.ForecastFetchPolicy
import com.weatherwidget.widget.ForecastStalenessPolicy
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.shared.actuals.HistoricalActualsBackfill
import com.weatherwidget.shared.util.ClimateNormals
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val TAG = "ForecastRepository"

@Singleton
class ForecastRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val forecastDao: ForecastDao,
        private val hourlyForecastDao: HourlyForecastDao,
        private val hourlyForecastHistoryDao: HourlyForecastHistoryDao,
        private val appLogDao: AppLogDao,
        private val nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        private val visualCrossingApi: VisualCrossingApi,
        private val weatherApi: WeatherApi,
        private val silurianApi: SilurianApi,
        private val widgetStateManager: WidgetStateManager,
        private val climateNormalDao: ClimateNormalDao,
        private val observationDao: ObservationDao,
        private val dailyHistoryDao: DailyHistoryDao,
        private val observationRepository: ObservationRepository,
        private val tomorrowIoApi: TomorrowIoApi? = null,
        private val openWeatherMapApi: OpenWeatherMapApi? = null,
        private val nwsForecastMapper: NwsForecastMapper,
    ) {
        

        private val syncMutex = Mutex()
        private val prefs by lazy { com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "weather_prefs") }
        private val gapFiller = ClimateGapFiller(climateNormalDao)

        companion object {
            private const val MIN_NETWORK_INTERVAL_MS = 600_000L // 10 minutes
            private const val MAX_RETRIES = 5
            private const val CACHE_LOOKBACK_DAYS = 7L
            private const val CACHE_FORECAST_DAYS = 30L
            private const val PREF_CHANCE_BACKFILL_DONE = "rain_chance_backfill_done"
            private const val PREF_FROZEN_DISPLAY_BACKFILL_DONE = "frozen_display_backfill_done"
            private const val CHANCE_BACKFILL_LOOKBACK_DAYS = 30L

            // A row is rewritten only when its content actually changes — never just to refresh
            // fetchedAt. fetchedAt therefore means "when this content was produced", which is what
            // freshest-selection wants; the forecast-history snapshot stamps its own real fetch time
            // independently. An unchanged row keeps its row (and old fetchedAt) untouched, which is
            // the write-saving point. (Retention deletes by fetchedAt < 30d, but the live display
            // window is only -24h..+60h, so a stale-fetchedAt row is never one still on screen.)
            @androidx.annotation.VisibleForTesting
            internal fun hasMeaningfulHourlyChange(
                existing: HourlyForecastEntity?,
                newlyFetched: HourlyForecastEntity,
            ): Boolean {
                if (existing == null) return true
                return existing.temperature != newlyFetched.temperature ||
                    existing.condition != newlyFetched.condition ||
                    existing.precipProbability != newlyFetched.precipProbability ||
                    existing.precipAmountMm != newlyFetched.precipAmountMm ||
                    existing.cloudCover != newlyFetched.cloudCover
            }

            /**
             * Index existing rows by dateTime for the change gate, keeping ONLY rows at the exact
             * coordinate pair being written. The proximity-box query can also return fresher rows
             * from a GPS-jitter fragment that the renderer never reads (unifyToNearestSite reads a
             * single site); comparing against those makes a revised value look "unchanged" and the
             * write at the display site is skipped forever, so the widget serves stale data while
             * desktop/emulator show the revision (2026-07-10: Sunday noon cloud stuck at 67% at the
             * display site while a jittered fragment held the revised 96%). The gate must compare
             * against exactly the rows the read side will resolve.
             */
            @androidx.annotation.VisibleForTesting
            internal fun siteExactExistingByDateTime(
                boxRows: List<HourlyForecastEntity>,
                lat: Double,
                lon: Double,
            ): Map<Long, HourlyForecastEntity> =
                boxRows.filter { it.locationLat == lat && it.locationLon == lon }
                    .associateBy { it.dateTime }

            /**
             * Merge a newly fetched hourly entity with the existing DB row, preserving any
             * non-null nullable fields from the existing row when the new fetch returned null.
             * Prevents a single bad fetch (e.g., NWS gridpoints failure that drops the skyCover
             * field) from wiping previously good cloudCover / precip data.
             */
            @androidx.annotation.VisibleForTesting
            internal fun mergePreservingNullableFields(
                existing: HourlyForecastEntity?,
                newlyFetched: HourlyForecastEntity,
            ): HourlyForecastEntity {
                if (existing == null) return newlyFetched
                return newlyFetched.copy(
                    cloudCover = newlyFetched.cloudCover ?: existing.cloudCover,
                    precipProbability = newlyFetched.precipProbability ?: existing.precipProbability,
                    precipAmountMm = newlyFetched.precipAmountMm ?: existing.precipAmountMm,
                )
            }

        }

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
                // Initial check without locking
                var cachedForecasts = getCachedData(latitude, longitude)
                if (!forceRefresh && !requiresNetworkFetch(latitude, longitude, cachedForecasts, fetchContext)) {
                    return Result.success(cachedForecasts)
                }

                if (!networkAllowed) return Result.success(cachedForecasts)

                syncMutex.withLock {
                    // Re-read data after acquiring lock to ensure another thread didn't just update it
                    cachedForecasts = getCachedData(latitude, longitude)
                    if (!forceRefresh && !requiresNetworkFetch(latitude, longitude, cachedForecasts, fetchContext)) {
                        return Result.success(cachedForecasts)
                    }

                    // Enforce a hard throttle on full network fetches unless forced
                    val timeSinceLastFetch = System.currentTimeMillis() - lastFetchTime
                    if (!forceRefresh && timeSinceLastFetch < MIN_NETWORK_INTERVAL_MS && cachedForecasts.isNotEmpty()) {
                        return Result.success(cachedForecasts)
                    }

                    appLogDao.log("NET_FETCH_START", "force=$forceRefresh target=$targetSourceId ctx=${fetchContext?.let { "charging=${it.isCharging},screen=${it.isScreenInteractive},batt=${it.batteryLevel},active=${it.activeSourceIds}" } ?: "none"}")
                    val enabledSources = widgetStateManager.getVisibleSourcesOrder().toSet()
                    if (forceRefresh && targetSourceId != null && enabledSources.none { it.id == targetSourceId }) {
                        appLogDao.log("NET_FETCH_SKIP_DISABLED", "target=$targetSourceId")
                        return Result.success(cachedForecasts)
                    }

                    fun shouldForceSource(source: WeatherSource): Boolean {
                        if (!forceRefresh) return false
                        if (targetSourceId == null) return true // Force all if no target specified
                        return source.id == targetSourceId
                    }

                   // Perform parallel fetches from all APIs
                    val sourcesToFetch = enabledSources.filter { source ->
                        shouldForceSource(source) || isStale(source, cachedForecasts, fetchContext)
                    }.toSet() - WeatherSource.GENERIC_GAP

                    val freshForecasts = fetchFromAllApis(
                        latitude, longitude, sourcesToFetch,
                        openWeatherMapApi != null,
                    )

                    // Best-effort warm of the climate-normals cache so read-time gap-fill (ClimateGapFiller)
                    // has data available; this already fetches+caches on a miss (getHistoricalNormalsByMonthDay).
                    // Network stays fetch-path-only — gap generation itself is cache-only.
                    runCatching { getHistoricalNormalsByMonthDay(latitude, longitude) }

                    cleanOldData()
                    val totalFetchTime = System.currentTimeMillis() - fetchStartTime
                    lastFetchTime = System.currentTimeMillis()
                    
                    appLogDao.log("NET_FETCH_COMPLETE", "durationMs=$totalFetchTime sources=${sourcesToFetch.joinToString(",") { it.id }}")
                    
                    return Result.success(getCachedData(latitude, longitude))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (exception: Exception) {
                lastFetchTime = 0L // Allow immediate retry on error
                appLogDao.logException("NET_FETCH_ERROR", "Network fetch failed", exception)
                val fallbackData = getCachedData(latitude, longitude)
                return if (fallbackData.isNotEmpty()) Result.success(fallbackData) else Result.failure(exception)
            }
        }

        /**
         * Snapshots the resolved (as-displayed) day/night forecast rain chance into daily_history for
         * yesterday and today, per source, so that once a day rolls into history its rain label can
         * replay what was actually shown instead of falling back to NWS's raw 6am/6pm period fields
         * (see DailyRainLabels.resolveLiveDayNightChance / resolveDailyLabelPrecip). Call after every
         * successful fetch.
         *
         * Each of a date's two windows (day: 8am-8pm, night: 8pm-8am next day) is only (re)written
         * while it is still open. The live hourly_forecasts table is REPLACE'd on every fetch, so a
         * PAST hour's row reflects the latest re-forecast for that hour, not what was actually shown
         * at the time ("hindcast drift" — same reason the hourly graph reads hourly_forecast_history
         * instead for its past segment). Recomputing a closed window from that drifted data would
         * silently overwrite the correctly-archived snapshot with a different, wrong value days
         * later. Once closed, a window's stored value is left untouched forever.
         *
         * Only updates daily_history rows that already exist (written by the actuals path) — a chance
         * with nothing to attach to yet is caught by the next fetch cycle.
         *
         * Also freezes the forecast-overlay values (forecastHighTemp/LowTemp/PrecipAmountMm) and the
         * measured noon cloud % under [DailyHistoryFreeze] windows, so the daily bar view can render
         * past days from daily_history alone. The overlay freezes from the latest batch per source:
         * because this runs after every fetch and the merge only accepts complete batches, the
         * surviving value equals "most recent complete batch of the day" — what the snapshot-table
         * reader would have selected.
         */
        internal suspend fun snapshotDisplayedRainChance(latitude: Double, longitude: Double) {
            val zoneId = ZoneId.systemDefault()
            val nowMs = System.currentTimeMillis()
            val today = LocalDate.now(zoneId)
            val yesterday = today.minusDays(1)
            val startMs = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val endMs = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY

            val dailyRows = forecastDao.getForecastsInRange(startMs, endMs, latitude, longitude)
            if (dailyRows.isEmpty()) return
            // Today's night window runs 8pm today -> 8am TOMORROW, so the hourly range must extend
            // a full day past `today`, not stop at tomorrow's midnight.
            val hourlyRows = hourlyForecastDao.getHourlyForecasts(
                yesterday.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                today.plusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                latitude,
                longitude,
            ).map { it.toHourlyForecast() }
            val existingByDateSource = dailyHistoryDao.getExtremesInRange(startMs, endMs, latitude, longitude)
                .groupBy { it.date to it.source }

            val toInsert = mutableListOf<com.weatherwidget.data.local.DailyHistoryEntity>()
            listOf(yesterday, today).forEach { date ->
                val dayWindowOpen = nowMs < date.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli()
                val nightWindowOpen = nowMs < date.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
                // The night window is the last to close (next-day 8am, same as the noon-cloud
                // window; the overlay window closes earlier at midnight), so this early-exit
                // covers every freeze window too.
                if (!dayWindowOpen && !nightWindowOpen) return@forEach
                val overlayOpen = com.weatherwidget.shared.util.DailyHistoryFreeze.overlayWindowOpen(nowMs, date, zoneId)
                val noonCloudOpen = com.weatherwidget.shared.util.DailyHistoryFreeze.noonCloudWindowOpen(nowMs, date, zoneId)

                val dateMs = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY
                dailyRows.filter { it.targetDate == dateMs }.forEach { row ->
                    val fragments = existingByDateSource[dateMs to row.source].orEmpty()
                    if (fragments.isEmpty()) return@forEach
                    val resolved = com.weatherwidget.shared.util.DailyRainLabels.resolveLiveDayNightChance(
                        displaySourceId = row.source,
                        daytimePrecipProbability = row.daytimePrecipProbability,
                        nighttimePrecipProbability = row.nighttimePrecipProbability,
                        precipProbability = row.precipProbability,
                        hourly = hourlyRows,
                        targetDate = date,
                        zoneId = zoneId,
                    )
                    // Overlay freeze candidates: only a real, complete forecast row (climate-normal
                    // filler must never masquerade as the day's displayed forecast).
                    val overlayRow = row.takeIf {
                        !it.isClimateNormal && it.source != WeatherSource.GENERIC_GAP.id &&
                            it.highTemp != null && it.lowTemp != null
                    }
                    val resolvedNoonCloud = com.weatherwidget.shared.util.DailyNoonCloudCover
                        .resolveMeasuredNoonCloudCoverPercent(
                            hourly = hourlyRows,
                            date = date,
                            displaySourceId = row.source,
                        )
                    fragments.forEach { existing ->
                        val newDay = if (dayWindowOpen) resolved.dayPrecip else existing.forecastDayPrecipChance
                        val newNight = if (nightWindowOpen) resolved.nightPrecip else existing.forecastNightPrecipChance
                        val frozen = com.weatherwidget.shared.util.DailyHistoryFreeze.merge(
                            overlayOpen = overlayOpen,
                            noonCloudOpen = noonCloudOpen,
                            resolvedHigh = overlayRow?.highTemp,
                            resolvedLow = overlayRow?.lowTemp,
                            resolvedPrecipAmountMm = overlayRow?.precipAmountMm,
                            resolvedNoonCloudPercent = resolvedNoonCloud,
                            existing = com.weatherwidget.shared.util.DailyHistoryFreeze.FrozenDisplay(
                                forecastHighTemp = existing.forecastHighTemp,
                                forecastLowTemp = existing.forecastLowTemp,
                                forecastPrecipAmountMm = existing.forecastPrecipAmountMm,
                                noonCloudPercent = existing.noonCloudPercent,
                            ),
                        )
                        val updated = existing.copy(
                            forecastDayPrecipChance = newDay,
                            forecastNightPrecipChance = newNight,
                            forecastHighTemp = frozen.forecastHighTemp,
                            forecastLowTemp = frozen.forecastLowTemp,
                            forecastPrecipAmountMm = frozen.forecastPrecipAmountMm,
                            noonCloudPercent = frozen.noonCloudPercent,
                        )
                        Log.v(
                            TAG,
                            "freezeDisplay: date=$date src=${row.source} overlayOpen=$overlayOpen noonCloudOpen=$noonCloudOpen" +
                                " dayWin=$dayWindowOpen nightWin=$nightWindowOpen" +
                                " dayChance=${existing.forecastDayPrecipChance}->$newDay(resolved=${resolved.dayPrecip})" +
                                " nightChance=${existing.forecastNightPrecipChance}->$newNight(resolved=${resolved.nightPrecip})" +
                                " high=${existing.forecastHighTemp}->${updated.forecastHighTemp}" +
                                " low=${existing.forecastLowTemp}->${updated.forecastLowTemp}" +
                                " amount=${existing.forecastPrecipAmountMm}->${updated.forecastPrecipAmountMm}" +
                                " noonCloud=${existing.noonCloudPercent}->${updated.noonCloudPercent}",
                        )
                        // Persist the rain-chance transition to app_logs (VERBOSE above is logcat-only).
                        // The frozen day/night chance is captured from the live hourly-window max while
                        // the window is open, so two independently-fetching installs can freeze
                        // different values if a provider revises the chance between their fetches (e.g.
                        // NWS 14%->15%). Logging the resolved input + before->after + timestamp lets us
                        // reconstruct which value each install captured and when, if they ever diverge.
                        if (newDay != existing.forecastDayPrecipChance || newNight != existing.forecastNightPrecipChance) {
                            appLogDao.log(
                                "FREEZE_RAIN_CHANCE",
                                "date=$date src=${row.source} dayWin=$dayWindowOpen nightWin=$nightWindowOpen" +
                                    " resolvedDay=${resolved.dayPrecip} resolvedNight=${resolved.nightPrecip}" +
                                    " day=${existing.forecastDayPrecipChance}->$newDay" +
                                    " night=${existing.forecastNightPrecipChance}->$newNight",
                            )
                        }
                        if (updated != existing) toInsert.add(updated)
                    }
                }
            }
            if (toInsert.isNotEmpty()) dailyHistoryDao.insertAll(toInsert)
        }

        /**
         * One-time backfill: fills the forecast chance snapshot columns for daily_history rows from
         * before this feature existed, using [HourlyForecastHistoryEntity] (the as-predicted hourly
         * archive) rather than the live, REPLACE-overwritten hourly_forecasts table — so the backfilled
         * value reflects what was actually forecast for those hours, not a later hindcast-drifted one.
         * Reuses the same window-max logic ([DailyRainLabels.calculateDayNightPrecipProbabilities]) and
         * the same freshest-per-hour collapse ([HourlyForecastStitcher.stitch]) the live hourly graph
         * uses for its own hindcast segment.
         *
         * Best-effort: a day with no matching history rows (never fetched, or aged past the 30-day
         * hourly_forecast_history retention) is simply left with null chances, same as today — no
         * regression, just a missed enhancement for that day. Gated by a one-time SharedPreferences
         * flag since a row's chances staying null forever would otherwise re-scan every call.
         */
        internal suspend fun backfillForecastChanceSnapshotsIfNeeded(latitude: Double, longitude: Double) {
            if (prefs.getBoolean(PREF_CHANCE_BACKFILL_DONE, false)) return
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val startMs = today.minusDays(CHANCE_BACKFILL_LOOKBACK_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val endMs = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY

            val rowsNeedingBackfill = dailyHistoryDao.getExtremesInRange(startMs, endMs, latitude, longitude)
                .filter { it.forecastDayPrecipChance == null && it.forecastNightPrecipChance == null }

            val toInsert = mutableListOf<com.weatherwidget.data.local.DailyHistoryEntity>()
            for (row in rowsNeedingBackfill) {
                val date = LocalDate.ofEpochDay(row.date / WidgetConstants.MS_IN_A_DAY)
                val windowStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val windowEndMs = date.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
                val historyRows = hourlyForecastHistoryDao.getHistoryInRangeForBucketWindow(
                    startDateTime = windowStartMs,
                    endDateTime = windowEndMs,
                    bucketStart = Long.MIN_VALUE,
                    bucketEnd = Long.MAX_VALUE,
                    lat = latitude,
                    lon = longitude,
                    source = row.source,
                ).map {
                    HourlyForecastEntity(
                        dateTime = it.dateTime,
                        locationLat = it.locationLat,
                        locationLon = it.locationLon,
                        temperature = it.temperature,
                        condition = it.condition,
                        source = it.source,
                        precipProbability = it.precipProbability,
                        cloudCover = it.cloudCover,
                        precipAmountMm = it.precipAmountMm,
                        fetchedAt = it.fetchedAt,
                    ).toHourlyForecast()
                }
                if (historyRows.isEmpty()) continue

                val stitched = com.weatherwidget.data.model.HourlyForecastStitcher.stitch(
                    current = emptyList(),
                    history = historyRows,
                    nowMs = System.currentTimeMillis(),
                    centerLat = latitude,
                    centerLon = longitude,
                )
                val dayNight = com.weatherwidget.shared.util.DailyRainLabels.calculateDayNightPrecipProbabilities(
                    hourly = stitched,
                    targetDate = date,
                    displaySourceId = row.source,
                    zoneId = zoneId,
                )
                if (dayNight.dayMax == null && dayNight.nightMax == null) continue
                toInsert.add(row.copy(forecastDayPrecipChance = dayNight.dayMax, forecastNightPrecipChance = dayNight.nightMax))
            }
            if (toInsert.isNotEmpty()) dailyHistoryDao.insertAll(toInsert)
            prefs.edit().putBoolean(PREF_CHANCE_BACKFILL_DONE, true).apply()
        }

        /**
         * One-time backfill of the frozen display columns (forecastHighTemp/LowTemp/PrecipAmountMm +
         * noonCloudPercent, see [DailyHistoryFreeze]) for daily_history rows from before the feature
         * existed, while their source tables are still retained:
         *
         *  - Overlay: the most recent complete non-climate batch from the forecasts table for that
         *    (date, source) — exactly what the past-day reader selects today. Past target dates are
         *    never re-fetched, so every stored batch predates the day's end and the freeze-window
         *    rule is satisfied by construction.
         *  - Noon cloud: the as-predicted hourly_forecast_history archive (freshest snapshot per
         *    hour via [HourlyForecastStitcher.stitch]), same pipeline as the chance backfill.
         *
         * Best-effort: unfillable days keep nulls (no regression). Pref-gated one-shot.
         */
        internal suspend fun backfillFrozenDisplayColumnsIfNeeded(latitude: Double, longitude: Double) {
            if (prefs.getBoolean(PREF_FROZEN_DISPLAY_BACKFILL_DONE, false)) return
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val startMs = today.minusDays(CHANCE_BACKFILL_LOOKBACK_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val endMs = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY

            // Per-column: a row can already carry noon cloud but no overlay (the live writer runs
            // before this backfill, and yesterday's noon-cloud window is still open on the first
            // post-migration fetch) — an all-columns-null row gate would skip its overlay forever.
            val rowsNeedingBackfill = dailyHistoryDao.getExtremesInRange(startMs, endMs, latitude, longitude)
                .filter { (it.forecastHighTemp == null && it.forecastLowTemp == null) || it.noonCloudPercent == null }
            if (rowsNeedingBackfill.isEmpty()) {
                prefs.edit().putBoolean(PREF_FROZEN_DISPLAY_BACKFILL_DONE, true).apply()
                return
            }

            val snapshotsByDateSource = forecastDao.getAllForecastsInRange(startMs, endMs, latitude, longitude)
                .groupBy { it.targetDate to it.source }

            val toInsert = mutableListOf<com.weatherwidget.data.local.DailyHistoryEntity>()
            for (row in rowsNeedingBackfill) {
                val date = LocalDate.ofEpochDay(row.date / WidgetConstants.MS_IN_A_DAY)
                val overlay = snapshotsByDateSource[row.date to row.source].orEmpty()
                    .filter { !it.isClimateNormal && it.highTemp != null && it.lowTemp != null }
                    .maxByOrNull { it.fetchedAt }

                val windowStartMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val windowEndMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val historyRows = hourlyForecastHistoryDao.getHistoryInRangeForBucketWindow(
                    startDateTime = windowStartMs,
                    endDateTime = windowEndMs,
                    bucketStart = Long.MIN_VALUE,
                    bucketEnd = Long.MAX_VALUE,
                    lat = latitude,
                    lon = longitude,
                    source = row.source,
                ).map {
                    HourlyForecastEntity(
                        dateTime = it.dateTime,
                        locationLat = it.locationLat,
                        locationLon = it.locationLon,
                        temperature = it.temperature,
                        condition = it.condition,
                        source = it.source,
                        precipProbability = it.precipProbability,
                        cloudCover = it.cloudCover,
                        precipAmountMm = it.precipAmountMm,
                        fetchedAt = it.fetchedAt,
                    ).toHourlyForecast()
                }
                val noonCloud = if (historyRows.isEmpty()) {
                    null
                } else {
                    com.weatherwidget.shared.util.DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(
                        hourly = com.weatherwidget.data.model.HourlyForecastStitcher.stitch(
                            current = emptyList(),
                            history = historyRows,
                            nowMs = System.currentTimeMillis(),
                            centerLat = latitude,
                            centerLon = longitude,
                        ),
                        date = date,
                        displaySourceId = row.source,
                        zone = zoneId,
                    )
                }

                // Fill only what's missing — never overwrite a value the live writer already froze.
                val updated = row.copy(
                    forecastHighTemp = row.forecastHighTemp ?: overlay?.highTemp,
                    forecastLowTemp = row.forecastLowTemp ?: overlay?.lowTemp,
                    forecastPrecipAmountMm = row.forecastPrecipAmountMm ?: overlay?.precipAmountMm,
                    noonCloudPercent = row.noonCloudPercent ?: noonCloud,
                )
                if (updated == row) continue
                toInsert.add(updated)
            }
            if (toInsert.isNotEmpty()) dailyHistoryDao.insertAll(toInsert)
            appLogDao.log("FROZEN_DISPLAY_BACKFILL", "backfilled=${toInsert.size} scanned=${rowsNeedingBackfill.size}")
            prefs.edit().putBoolean(PREF_FROZEN_DISPLAY_BACKFILL_DONE, true).apply()
        }

        private fun requiresNetworkFetch(
            latitude: Double,
            longitude: Double,
            forecasts: List<ForecastEntity>,
            fetchContext: ForecastFetchContext? = null,
        ): Boolean {
            val sourcesToCheck = listOf(
                WeatherSource.NWS,
                WeatherSource.VISUAL_CROSSING,
                WeatherSource.OPEN_WEATHER_MAP,
                WeatherSource.SILURIAN,
                WeatherSource.WEATHER_API,
                WeatherSource.OPEN_METEO,
                WeatherSource.TOMORROW_IO,
            )
            return sourcesToCheck.any { source ->
                val isNeeded = widgetStateManager.isSourceVisible(source)
                isNeeded && isStale(source, forecasts, fetchContext)
            }
        }

        private fun isStale(
            source: WeatherSource,
            forecasts: List<ForecastEntity>,
            fetchContext: ForecastFetchContext? = null,
        ): Boolean {
            val lastSourceFetchTime = forecasts.filter { it.source == source.id }.maxOfOrNull { it.batchFetchedAt } ?: 0L
            val now = System.currentTimeMillis()

            if (fetchContext != null) {
                val intervalMinutes = ForecastFetchPolicy.intervalMinutes(
                    isCharging = fetchContext.isCharging,
                    isScreenInteractive = fetchContext.isScreenInteractive,
                    isActiveSource = source.id in fetchContext.activeSourceIds,
                    batteryLevel = fetchContext.batteryLevel,
                ) ?: return false
                return ForecastFetchPolicy.isDue(lastSourceFetchTime, intervalMinutes, now)
            }

            val visibleSources = widgetStateManager.getVisibleSourcesOrder()
            val position = visibleSources.indexOf(source)
            val threshold = ForecastStalenessPolicy.getStalenessThresholdMs(position)
            return now - lastSourceFetchTime >= threshold
        }

        private data class FetchResult(
            val nws: List<ForecastEntity>?,
            val openWeatherMap: List<ForecastEntity>?,
            val visualCrossing: List<ForecastEntity>?,
            val meteo: List<ForecastEntity>?,
            val wapi: List<ForecastEntity>?,
            val silurian: List<ForecastEntity>?,
            val tomorrowIo: List<ForecastEntity>?,
        )

       private suspend fun fetchFromAllApis(
            latitude: Double,
            longitude: Double,
            sourcesToFetch: Set<WeatherSource>,
            hasOpenWeatherMapApi: Boolean,
        ): FetchResult = coroutineScope {
            val nwsDeferred = if (WeatherSource.NWS in sourcesToFetch) async {
                safeFetch("FETCH_NWS_FAIL", WeatherSource.NWS) {
                    fetchFromNws(latitude, longitude)
                }
            } else null

            val openWeatherMapDeferred = if (hasOpenWeatherMapApi && WeatherSource.OPEN_WEATHER_MAP in sourcesToFetch) async {
                safeFetch("FETCH_OWM_FAIL", WeatherSource.OPEN_WEATHER_MAP) {
                    val api = openWeatherMapApi ?: return@safeFetch null
                    val result = api.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.OPEN_WEATHER_MAP.id)
                    }
                    result.daily.map { day ->
                        mapDailyForecast(day, latitude, longitude, WeatherSource.OPEN_WEATHER_MAP.id, result.hourly)
                    }
                }
            } else null

            val visualCrossingDeferred = if (WeatherSource.VISUAL_CROSSING in sourcesToFetch) async {
                safeFetch("FETCH_VISUAL_CROSSING_FAIL", WeatherSource.VISUAL_CROSSING) {
                    val result = visualCrossingApi.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.VISUAL_CROSSING.id)
                    }
                    result.daily.map { day ->
                        mapDailyForecast(day, latitude, longitude, WeatherSource.VISUAL_CROSSING.id, result.hourly)
                    }
                }
            } else null
            
            val meteoDeferred = if (WeatherSource.OPEN_METEO in sourcesToFetch) async {
                safeFetch("FETCH_METEO_FAIL", WeatherSource.OPEN_METEO) {
                    val result = openMeteoApi.getForecast(
                        latitude,
                        longitude,
                        historyDays = WeatherConfig.ACTUALS_HISTORY_DAYS
                    )
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.OPEN_METEO.id)
                    }
                    result.daily.map { day ->
                        mapDailyForecast(day, latitude, longitude, WeatherSource.OPEN_METEO.id, result.hourly)
                    }
                }
            } else null
            
            val wapiDeferred = if (WeatherSource.WEATHER_API in sourcesToFetch) async {
                safeFetch("FETCH_WAPI_FAIL", WeatherSource.WEATHER_API) {
                    val result = weatherApi.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.WEATHER_API.id)
                    }
                    result.daily.map { day ->
                        mapDailyForecast(day, latitude, longitude, WeatherSource.WEATHER_API.id, result.hourly)
                    }
                }
            } else null

            val silurianDeferred = if (WeatherSource.SILURIAN in sourcesToFetch) async {
                safeFetch("FETCH_SILURIAN_FAIL", WeatherSource.SILURIAN) {
                    val result = silurianApi.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.SILURIAN.id)
                    }
                    result.daily.map { day ->
                    mapDailyForecast(
                        DailyForecast(
                            day.date,
                            day.highTemp,
                            day.lowTemp,
                            day.condition,
                            day.condition,
                            day.precipProbability,
                            day.precipAmountMm,
                        ),

                        latitude,
                        longitude,
                        WeatherSource.SILURIAN.id,
                        result.hourly,
                    )
                    }

                }
            } else null

            val tomorrowIoDeferred = if (WeatherSource.TOMORROW_IO in sourcesToFetch) async {
                safeFetch("FETCH_TMRW_FAIL", WeatherSource.TOMORROW_IO) {
                    val api = tomorrowIoApi ?: return@safeFetch null
                    val result = api.getForecast(latitude, longitude)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyEntitiesFromShared(result.hourly, latitude, longitude, WeatherSource.TOMORROW_IO.id)
                    }
                    result.daily.map { day ->
                        mapDailyForecast(day, latitude, longitude, WeatherSource.TOMORROW_IO.id, result.hourly)
                    }
                }
            } else null

            val nwsForecasts = nwsDeferred?.await()
            val owmForecasts = openWeatherMapDeferred?.await()
            val visualCrossingForecasts = visualCrossingDeferred?.await()
            val meteoForecasts = meteoDeferred?.await()
            val wapiForecasts = wapiDeferred?.await()
            val silurianForecasts = silurianDeferred?.await()
            val tomorrowIoForecasts = tomorrowIoDeferred?.await()

            // Save each provider fetch as a coherent batch with a shared batchFetchedAt.
            nwsForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.NWS.id, System.currentTimeMillis()) }
            owmForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.OPEN_WEATHER_MAP.id, System.currentTimeMillis()) }
            visualCrossingForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.VISUAL_CROSSING.id, System.currentTimeMillis()) }
            meteoForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.OPEN_METEO.id, System.currentTimeMillis()) }
            wapiForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.WEATHER_API.id, System.currentTimeMillis()) }
            silurianForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.SILURIAN.id, System.currentTimeMillis()) }
            tomorrowIoForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.TOMORROW_IO.id, System.currentTimeMillis()) }

            FetchResult(nwsForecasts, owmForecasts, visualCrossingForecasts, meteoForecasts, wapiForecasts, silurianForecasts, tomorrowIoForecasts)
        }

        internal suspend fun fetchFromNws(latitude: Double, longitude: Double): List<ForecastEntity> {
            val (forecastEntities, hourlyEntities) = nwsForecastMapper.fetchFromNws(latitude, longitude)
            if (hourlyEntities.isNotEmpty()) {
                saveHourlyEntities(hourlyEntities)
            }
            return forecastEntities
        }

        private suspend fun logFetchFailure(
            tag: String,
            source: WeatherSource,
            exception: Exception,
        ) {
            when (exception) {
                is ApiAccessException -> {
                    val code = exception.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
                    appLogDao.log(tag, "source=${source.id} code=$code detail=${exception.detail}", "WARN")
                }
                is ClientRequestException -> {
                    val statusCode = exception.response.status.value
                    val detail = extractHttpErrorDetail(
                        runCatching { exception.response.bodyAsText() }.getOrNull(),
                        exception.message,
                    )
                    appLogDao.log(tag, "source=${source.id} code=HTTP_$statusCode detail=$detail", "WARN")
                }
                else -> appLogDao.log(tag, "source=${source.id} error=${exception.message}", "WARN")
            }
        }

        private fun extractErrorCode(exception: Exception): String = when (exception) {
            is ApiAccessException -> exception.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
            is ClientRequestException -> "HTTP_${exception.response.status.value}"
            else -> {
                val name = exception.javaClass.simpleName
                when {
                    name.contains("UnknownHost") || name.contains("UnresolvedAddress") -> "DNS_ERROR"
                    name.contains("ConnectException") || name.contains("ConnectionRefused") -> "CONN_REFUSED"
                    name.contains("Timeout") || name.contains("SocketTimeout") -> "TIMEOUT"
                    name.contains("SSL") || name.contains("TLS") -> "SSL_ERROR"
                    name.contains("SocketException") -> "SOCKET_ERROR"
                    else -> name.take(20).ifBlank { "ERROR" }
                }
            }
        }

        private fun extractHttpErrorDetail(body: String?, fallbackMessage: String?): String {
            val bodyText = body?.trim().orEmpty()
            val messageMatch = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(bodyText)?.groupValues?.getOrNull(1)
            val errorMatch = Regex("\"error\"\\s*:\\s*\\{[^}]*\"message\"\\s*:\\s*\"([^\"]+)\"").find(bodyText)?.groupValues?.getOrNull(1)
            return messageMatch ?: errorMatch ?: fallbackMessage ?: "Request failed"
        }

        private suspend fun <T> safeFetch(
            tag: String,
            source: WeatherSource,
            block: suspend () -> T,
        ): T? {
            return try {
                val result = block()
                if (result != null) {
                    widgetStateManager.recordSourceFetchSuccess(source)
                }
                result
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (exception: Exception) {
                widgetStateManager.recordSourceFetchFailure(source, errorCode = extractErrorCode(exception))
                logFetchFailure(tag, source, exception)
                null
            }
        }

        @androidx.annotation.VisibleForTesting
        internal fun mapDailyForecast(
            day: DailyForecast,
            latitude: Double,
            longitude: Double,
            sourceId: String,
            hourlyForecasts: List<HourlyForecast> = emptyList(),
        ): ForecastEntity {
            val targetDate = LocalDate.parse(day.date)
            val zone = ZoneId.systemDefault()

            // Daytime: 8:00 AM to 8:00 PM (on the target date)
            val dayStart = targetDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            val dayEnd = targetDate.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

            // Nighttime: 8:00 PM on target date to 8:00 AM next day
            val nightStart = dayEnd
            val nightEnd = targetDate.plusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

            val calcDaytime = hourlyForecasts
                .filter { it.dateTime >= dayStart && it.dateTime < dayEnd }
                .mapNotNull { it.precipProbability }
                .maxOrNull()

            val calcNighttime = hourlyForecasts
                .filter { it.dateTime >= nightStart && it.dateTime < nightEnd }
                .mapNotNull { it.precipProbability }
                .maxOrNull()

            return ForecastEntity(
                targetDate = targetDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                dateOfPrediction = LocalDate.now().toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                locationLat = latitude,
                locationLon = longitude,
                highTemp = day.highTemp,
                lowTemp = day.lowTemp,
                condition = day.condition,
                nativeDailyIconToken = day.iconToken,
                isClimateNormal = false,
                source = sourceId,
                precipProbability = day.precipProbability,
                daytimePrecipProbability = calcDaytime,
                nighttimePrecipProbability = calcNighttime,
                precipAmountMm = day.precipAmountMm,
            )
        }

        @androidx.annotation.VisibleForTesting
        internal suspend fun saveForecastSnapshot(
            weatherForecasts: List<ForecastEntity>, 
            latitude: Double, 
            longitude: Double, 
            sourceId: String,
            batchFetchedAt: Long = System.currentTimeMillis(),
        ) {
            val todayDate = LocalDate.now()
            val todayEpoch = todayDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val now = ZonedDateTime.now()
            // Coordinates go into the PK; write them quantized so jitter between fetches lands on
            // the same key and REPLACE overwrites instead of stranding a stale per-precision site
            // (same rationale as the hourly tables — see LocationMatch.quantize).
            val keyLat = LocationMatch.quantize(latitude)
            val keyLon = LocationMatch.quantize(longitude)
            val forecastsToSave = weatherForecasts.filter { forecast ->
                val date = LocalDate.ofEpochDay(forecast.targetDate / WidgetConstants.MS_IN_A_DAY)
                if (date.isBefore(todayDate) || forecast.isClimateNormal) return@filter false
                // Exclude entries whose daytime period has already ended — NWS overwrites elapsed
                // periods with observed reality, so snapshotting them would corrupt accuracy tracking
                // by making tomorrow's "forecast vs actual" comparison observed-vs-observed.
                val periodEnd = forecast.periodEndTime?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
                if (periodEnd != null && periodEnd.isBefore(now)) {
                    appLogDao.log("SNAPSHOT_SKIP_ELAPSED", "date=${LocalDate.ofEpochDay(forecast.targetDate / WidgetConstants.MS_IN_A_DAY)} source=${forecast.source} periodEnd=$periodEnd")
                    return@filter false
                }
                true
            }.mapNotNull { forecast ->
                // Treat non-finite (NaN/Infinity) temps as missing: roundToInt() throws
                // "Cannot round NaN value." on NaN (the ?. operator only guards null), which
                // would abort the whole fetch and drop later sources' snapshots.
                val high = forecast.highTemp?.takeIf { it.isFinite() }
                val low = forecast.lowTemp?.takeIf { it.isFinite() }
                if (high == null && low == null) return@mapNotNull null

                // Preserve full decimal precision for Today's forecast to improve accuracy tracking.
                // Continue rounding future days to integers for UI consistency and storage.
                val isToday = forecast.targetDate == todayEpoch
                val highTempSaved = if (isToday) high else high?.roundToInt()?.toFloat()
                val lowTempSaved = if (isToday) low else low?.roundToInt()?.toFloat()

                ForecastEntity(
                    targetDate = forecast.targetDate,
                    dateOfPrediction = todayEpoch,
                    locationLat = keyLat,
                    locationLon = keyLon,
                    highTemp = highTempSaved,
                    lowTemp = lowTempSaved,
                    condition = forecast.condition,
                    nativeDailyIconToken = forecast.nativeDailyIconToken,
                    isClimateNormal = forecast.isClimateNormal,
                    source = sourceId,
                    precipProbability = forecast.precipProbability,
                    daytimePrecipProbability = forecast.daytimePrecipProbability,
                    nighttimePrecipProbability = forecast.nighttimePrecipProbability,
                    precipAmountMm = forecast.precipAmountMm,
                    batchFetchedAt = batchFetchedAt,
                    fetchedAt = System.currentTimeMillis()
                )
            }

            if (forecastsToSave.isNotEmpty()) {
                // Use the optimized DAO query to get existing records for comparison
                val existingForecasts = forecastDao.getForecastsInRangeBySource(
                    startDate = todayEpoch,
                    endDate = todayDate.plusDays(14).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    lat = latitude,
                    lon = longitude,
                    source = sourceId
                )
                
                // associateBy picks the first occurrence.
                // Since DAO orders by batchFetchedAt DESC, then fetchedAt DESC, this is the latest row for each targetDate.
                val latestByDate = existingForecasts.distinctBy { it.targetDate }.associateBy { it.targetDate }
                
                val changedForecasts = forecastsToSave.filter { newlyFetched ->
                    val existing = latestByDate[newlyFetched.targetDate]
                    val fieldsMatch = existing != null &&
                        existing.highTemp == newlyFetched.highTemp &&
                        existing.lowTemp == newlyFetched.lowTemp &&
                        existing.condition == newlyFetched.condition &&
                        existing.nativeDailyIconToken == newlyFetched.nativeDailyIconToken &&
                        existing.precipProbability == newlyFetched.precipProbability &&
                        existing.daytimePrecipProbability == newlyFetched.daytimePrecipProbability &&
                        existing.nighttimePrecipProbability == newlyFetched.nighttimePrecipProbability &&
                        existing.precipAmountMm == newlyFetched.precipAmountMm
                    val newDataIsStrictlyBetter = existing != null &&
                        ((existing.highTemp == null && newlyFetched.highTemp != null) ||
                        (existing.lowTemp == null && newlyFetched.lowTemp != null))
                    if (fieldsMatch && !newDataIsStrictlyBetter) {
                        appLogDao.log("SNAPSHOT_SKIP", "date=${newlyFetched.targetDate} source=$sourceId existing_high=${existing.highTemp} new_high=${newlyFetched.highTemp} existing_low=${existing.lowTemp} new_low=${newlyFetched.lowTemp} existing_cond=${existing.condition} new_cond=${newlyFetched.condition} existing_precip=${existing.precipProbability} new_precip=${newlyFetched.precipProbability}")
                        false
                    } else {
                        if (existing != null && newDataIsStrictlyBetter) {
                            appLogDao.log("SNAPSHOT_UPGRADE", "date=${newlyFetched.targetDate} source=$sourceId existing_high=${existing.highTemp} new_high=${newlyFetched.highTemp} existing_low=${existing.lowTemp} new_low=${newlyFetched.lowTemp}")
                        }
                        appLogDao.log("SNAPSHOT_SAVE", "date=${newlyFetched.targetDate} source=$sourceId")
                        true
                    }
                }

                if (sourceId == WeatherSource.NWS.id) {
                    appLogDao.log(
                        "NWS_BATCH_SAVE_SUMMARY",
                        buildNwsBatchSaveSummary(
                            batchFetchedAt = batchFetchedAt,
                            rawForecasts = weatherForecasts,
                            forecastsToSave = forecastsToSave,
                            changedForecasts = changedForecasts,
                        ),
                    )
                }
                
                if (changedForecasts.isNotEmpty()) {
                    // History cadence cap: collapse any earlier snapshot from this same bucket so the
                    // daily forecast timeline keeps at most one row per 4h (priority) / 12h (background)
                    // window. The inserted rows carry the real fetchedAt, so current display and
                    // last-updated stay fresh; only intra-bucket duplicates are removed. Priority =
                    // the currently-displayed sources, so the source the user is viewing keeps the
                    // fast cadence. See ForecastHistoryPolicy and ForecastEvolutionRenderer's
                    // SNAPSHOT_BUCKET_HOURS (a display-only collapse, unaffected by a wider cadence).
                    val prioritySourceIds = widgetStateManager.getActiveDisplaySourceIds()
                    val bucketStart = ForecastHistoryPolicy.timestampToGroupPredictions(System.currentTimeMillis(), sourceId, prioritySourceIds)
                    val bucketEnd = bucketStart + ForecastHistoryPolicy.bucketMs(sourceId, prioritySourceIds)
                    forecastDao.deleteForecastsInBucket(
                        source = sourceId,
                        lat = keyLat,
                        lon = keyLon,
                        targetDates = changedForecasts.map { it.targetDate },
                        bucketStart = bucketStart,
                        bucketEnd = bucketEnd,
                    )
                    forecastDao.insertAll(changedForecasts)
                }
            }
        }

        private fun buildNwsBatchSaveSummary(
            batchFetchedAt: Long,
            rawForecasts: List<ForecastEntity>,
            forecastsToSave: List<ForecastEntity>,
            changedForecasts: List<ForecastEntity>,
        ): String {
            val rawMaxDate = rawForecasts.maxOfOrNull { it.targetDate }
            val filteredMaxDate = forecastsToSave.maxOfOrNull { it.targetDate }
            val savedMaxDate = changedForecasts.maxOfOrNull { it.targetDate }
            val terminalRow = forecastsToSave.maxByOrNull { it.targetDate }
            return "batch=$batchFetchedAt " +
                "rawCount=${rawForecasts.size} rawMaxDate=${formatEpochDate(rawMaxDate)} " +
                "filteredCount=${forecastsToSave.size} filteredMaxDate=${formatEpochDate(filteredMaxDate)} " +
                "savedCount=${changedForecasts.size} savedMaxDate=${formatEpochDate(savedMaxDate)} " +
                "terminal=${formatTerminalRow(terminalRow)}"
        }

        private fun formatEpochDate(epochMs: Long?): String =
            epochMs?.let { LocalDate.ofEpochDay(it / WidgetConstants.MS_IN_A_DAY).toString() } ?: "null"

        private fun formatTerminalRow(row: ForecastEntity?): String {
            if (row == null) return "null"
            return "${formatEpochDate(row.targetDate)} high=${row.highTemp} low=${row.lowTemp}"
        }

        /**
         * Returns climate normals (a rough seasonal average high/low) for every calendar
         * day at this location, used as the future-day fallback when no real forecast
         * exists. Normals are derived by averaging several years of observed daily highs/lows
         * from the Open-Meteo historical archive into 12 monthly means, cached as 12 rows,
         * and expanded back to per-day values by interpolating between month midpoints.
         */
        suspend fun getHistoricalNormalsByMonthDay(latitude: Double, longitude: Double): Map<MonthDay, Pair<Float, Float>> {
            val locationKey = ClimateNormals.locationKey(latitude, longitude)
            val cachedNormals = climateNormalDao.getNormalsForLocation(locationKey)

            if (cachedNormals.isNotEmpty()) {
                val monthlyHigh = cachedNormals.associate { it.monthDay.take(2).toInt() to it.highTemp }
                val monthlyLow = cachedNormals.associate { it.monthDay.take(2).toInt() to it.lowTemp }
                return ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)
            }

            if (!widgetStateManager.isSourceVisible(WeatherSource.OPEN_METEO)) {
                appLogDao.log("CLIMATE_SKIP_DISABLED", "source=${WeatherSource.OPEN_METEO.id}")
                return emptyMap()
            }

            val (startDate, endDate) = ClimateNormals.rollingWindow()
            val dailyTemps = openMeteoApi.getHistoricalDailyTemps(latitude, longitude, startDate, endDate)
            val (monthlyHigh, monthlyLow) = ClimateNormals.monthlyMeans(dailyTemps)

            if (monthlyHigh.isEmpty() || monthlyLow.isEmpty()) {
                appLogDao.log("CLIMATE_FETCH_EMPTY", "lat=$latitude lon=$longitude rows=${dailyTemps.size}")
                return emptyMap()
            }

            climateNormalDao.deleteOtherLocations(locationKey)
            climateNormalDao.insertAll(
                (1..12).mapNotNull { month ->
                    val high = monthlyHigh[month] ?: return@mapNotNull null
                    val low = monthlyLow[month] ?: return@mapNotNull null
                    ClimateNormalEntity(
                        monthDay = "${month.toString().padStart(2, '0')}-15",
                        locationKey = locationKey,
                        highTemp = high,
                        lowTemp = low,
                    )
                },
            )

            return ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow)
        }

        private suspend fun saveHourlyEntities(rawEntities: List<HourlyForecastEntity>) {
            if (rawEntities.isEmpty()) return

            // Quantize the PK coordinate so geocoding/GPS jitter overwrites the existing row instead
            // of accumulating a new per-precision fragment (see LocationMatch.quantize). Done once
            // here so both the live insert and the history snapshot below share the stable key.
            val entities = rawEntities.map {
                it.copy(
                    locationLat = LocationMatch.quantize(it.locationLat),
                    locationLon = LocationMatch.quantize(it.locationLon),
                )
            }

            val minDateTime = entities.minOf { it.dateTime }
            val maxDateTime = entities.maxOf { it.dateTime }
            val sample = entities.first()
            // Site-exact, not the raw proximity box: the change gate below must diff against the
            // rows the renderer will actually read at this coordinate (see siteExactExistingByDateTime).
            val existingByDateTime = siteExactExistingByDateTime(
                hourlyForecastDao.getHourlyForecastsBySource(
                    minDateTime, maxDateTime, sample.locationLat, sample.locationLon, sample.source
                ),
                sample.locationLat,
                sample.locationLon,
            )

            val mergedEntities = entities.map { newlyFetched ->
                mergePreservingNullableFields(existingByDateTime[newlyFetched.dateTime], newlyFetched)
            }

            // Priority = the currently-displayed sources; background sources snapshot history at a
            // wider cadence (see below). Live rows are written only on a real content change, so the
            // priority set does not gate the live insert.
            val prioritySourceIds = widgetStateManager.getActiveDisplaySourceIds()

            val changedEntities = mergedEntities.filter { merged ->
                val existing = existingByDateTime[merged.dateTime]
                hasMeaningfulHourlyChange(existing, merged)
            }

            if (changedEntities.isNotEmpty()) {
                hourlyForecastDao.insertAll(changedEntities)
            }

            // Forecast-history snapshot: preserve the full predicted hourly curve as fetched, keyed by
            // its snapshot bucket. Within a bucket the PK (incl snapshotBucket) makes later fetches
            // REPLACE earlier ones, capping cadence at 4h (priority) / 12h (background). The live table
            // above stays latest-only; this is the historical record. See ForecastHistoryPolicy.
            val historyRows = mergedEntities.map { e ->
                HourlyForecastHistoryEntity(
                    dateTime = e.dateTime,
                    locationLat = e.locationLat,
                    locationLon = e.locationLon,
                    temperature = e.temperature,
                    condition = e.condition,
                    source = e.source,
                    timestampToGroupPredictions = ForecastHistoryPolicy.timestampToGroupPredictions(e.fetchedAt, e.source, prioritySourceIds),
                    precipProbability = e.precipProbability,
                    cloudCover = e.cloudCover,
                    precipAmountMm = e.precipAmountMm,
                    fetchedAt = e.fetchedAt,
                )
            }
            if (historyRows.isNotEmpty()) {
                hourlyForecastHistoryDao.insertAll(historyRows)
            }
        }
        private suspend fun saveHourlyEntitiesFromShared(
            hourlyData: List<HourlyForecast>,
            latitude: Double,
            longitude: Double,
            sourceId: String
        ) {
            val now = System.currentTimeMillis()
            val fetchedAt = now

            // 1. Save future data as forecasts (predictions). We filter out the past so we don't 
            // retroactively overwrite older predictions with newly fetched re-analysis history,
            // preserving the difference between what was forecast vs actuals.
            val futureData = hourlyData.filter { it.dateTime >= now - 3600_000L }
            saveHourlyEntities(futureData.map {
                HourlyForecastEntity(
                    it.dateTime, latitude, longitude, it.temperature, it.condition,
                    sourceId, it.precipProbability, it.cloudCover, it.precipAmountMm, fetchedAt
                )
            })

            // 2. Backfill past hours as observations to drive the "actuals" line for non-NWS
            // APIs on fresh installs or emulators. NOTE: this is the past slice of the source's
            // hourly data, not a station measurement — see precip handling below.
            saveHistoricalActuals(hourlyData, latitude, longitude, sourceId)
        }

        private suspend fun saveHistoricalActuals(
            hourlyData: List<HourlyForecast>,
            latitude: Double,
            longitude: Double,
            sourceId: String
        ) {
            // The past-hours -> observation mapping (and its measured-precip provenance gate) is
            // shared with the desktop service via HistoricalActualsBackfill so the two platforms
            // cannot drift. Precip is kept only for sources whose past hours are genuine actuals
            // (a real history/reanalysis product); forecast-only sources have it nulled so their
            // own past forecast is not presented as a measurement.
            val now = System.currentTimeMillis()
            val historicalObs = HistoricalActualsBackfill.build(
                hourly = hourlyData,
                latitude = latitude,
                longitude = longitude,
                sourceId = sourceId,
                nowMs = now,
            ).map { reading ->
                ObservationEntity(
                    stationId = reading.stationId,
                    stationName = reading.stationName,
                    timestamp = reading.timestamp,
                    temperature = reading.temperature,
                    condition = reading.condition,
                    locationLat = reading.locationLat,
                    locationLon = reading.locationLon,
                    distanceKm = reading.distanceKm,
                    stationType = reading.stationType,
                    fetchedAt = reading.fetchedAt,
                    api = reading.api,
                    precipAmountMm = reading.precipAmountMm,
                )
            }

            if (historicalObs.isNotEmpty()) {
                observationDao.insertAll(historicalObs)
            }
        }
        private suspend fun saveNwsHourlyForecasts(hourlyPeriods: List<NwsApi.HourlyForecastPeriod>, latitude: Double, longitude: Double) =
            saveHourlyEntities(hourlyPeriods.map { period ->
                HourlyForecastEntity(
                    period.startTime, latitude, longitude, period.temperature,
                    period.shortForecast, WeatherSource.NWS.id, period.precipProbability, period.cloudCover, period.precipAmountMm, System.currentTimeMillis()
                )
            })

        suspend fun getObservationsInRange(
            startTimestamp: Long,
            endTimestamp: Long,
            latitude: Double,
            longitude: Double,
        ): List<ObservationEntity> = observationDao.getObservationsInRange(startTimestamp, endTimestamp, latitude, longitude)

        suspend fun getCachedData(latitude: Double, longitude: Double): List<ForecastEntity> {
            val today = LocalDate.now()
            val rows = forecastDao.getLatestForecastsInRange(
                today.minusDays(CACHE_LOOKBACK_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                today.plusDays(CACHE_FORECAST_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                latitude,
                longitude,
            )
            return gapFiller.appendGaps(rows, latitude, longitude, today, CACHE_FORECAST_DAYS)
        }

        suspend fun getCachedDataBySource(latitude: Double, longitude: Double, source: WeatherSource): List<ForecastEntity> {
            val today = LocalDate.now()
            val startDate = today.minusDays(CACHE_LOOKBACK_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val endDate = today.plusDays(CACHE_FORECAST_DAYS).toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val sourceData = forecastDao.getForecastsInRangeBySource(startDate, endDate, latitude, longitude, source.id)

            val latestBatchFetchedAt = sourceData.maxOfOrNull { it.batchFetchedAt }
            val liveSourceData = if (latestBatchFetchedAt != null) {
                sourceData.filter { it.batchFetchedAt == latestBatchFetchedAt }
            } else {
                emptyList()
            }

            if (source == WeatherSource.NWS) {
                appLogDao.log(
                    "NWS_BATCH_RENDER_SUMMARY",
                    "batch=$latestBatchFetchedAt liveCount=${liveSourceData.size} " +
                        "liveMinDate=${formatEpochDate(liveSourceData.minOfOrNull { it.targetDate })} " +
                        "liveMaxDate=${formatEpochDate(liveSourceData.maxOfOrNull { it.targetDate })}",
                )
            }

            val coveredDates = liveSourceData.map { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }.toSet()
            val gapData = gapFiller.gapRows(latitude, longitude, coveredDates, today, CACHE_FORECAST_DAYS)

            val latestSourceByDate = liveSourceData.groupBy { it.targetDate }.mapValues { (_, rows) -> rows.first() }
            val latestGapByDate = gapData.groupBy { it.targetDate }.mapValues { (_, rows) -> rows.first() }

            return (latestGapByDate.keys + latestSourceByDate.keys)
                .sorted()
                .mapNotNull { date -> latestSourceByDate[date] ?: latestGapByDate[date] }
        }

        suspend fun getForecastForDate(date: Long, latitude: Double, longitude: Double) =
            forecastDao.getForecastForDate(date, latitude, longitude)

        suspend fun getForecastForDateBySource(date: Long, latitude: Double, longitude: Double, source: WeatherSource): ForecastEntity? =
            forecastDao.getForecastsInRangeBySource(date, date, latitude, longitude, source.id).firstOrNull()

        suspend fun getForecastsInRange(startDate: Long, endDate: Long, latitude: Double, longitude: Double) =
            forecastDao.getForecastsInRange(startDate, endDate, latitude, longitude)

        suspend fun getAllForecastsInRange(startDate: Long, endDate: Long, latitude: Double, longitude: Double) =
            forecastDao.getAllForecastsInRange(startDate, endDate, latitude, longitude)

        suspend fun getAllForecastsInRangeForSources(startDate: Long, endDate: Long, latitude: Double, longitude: Double, sources: List<String>) =
            forecastDao.getAllForecastsInRangeForSources(startDate, endDate, latitude, longitude, sources)

        suspend fun getLatestForecastsInRange(startDate: Long, endDate: Long, latitude: Double, longitude: Double) =
            forecastDao.getLatestForecastsInRange(startDate, endDate, latitude, longitude)

        suspend fun getLatestForecastsInRangeForSources(startDate: Long, endDate: Long, latitude: Double, longitude: Double, sources: List<String>) =
            forecastDao.getLatestForecastsInRangeForSources(startDate, endDate, latitude, longitude, sources)

        suspend fun cleanOldData() {
            val oneMonthAgoTimestamp = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30 // 30 days
            val thirteenMonthsAgoTimestamp = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 395 // 13 months (395 days)
            val tenDaysAgoTimestamp = System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 10 // 10 days
            val logsCutoffTimestamp = System.currentTimeMillis() - 1000L * 60 * 60 * 72 // 72 hours
            forecastDao.deleteOldForecasts(oneMonthAgoTimestamp)
            forecastDao.deleteClimateNormalRows(WeatherSource.GENERIC_GAP.id)
            hourlyForecastDao.deleteOldForecasts(oneMonthAgoTimestamp)
            hourlyForecastHistoryDao.deleteOldHistory(oneMonthAgoTimestamp)
            observationDao.deleteOldObservations(tenDaysAgoTimestamp)
            dailyHistoryDao.deleteOldExtremes(thirteenMonthsAgoTimestamp)
            appLogDao.deleteOldLogs(logsCutoffTimestamp)
        }
    }

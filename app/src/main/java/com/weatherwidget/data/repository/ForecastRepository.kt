package com.weatherwidget.data.repository

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.widget.ForecastStalenessPolicy
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private val appLogDao: AppLogDao,
        private val nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        private val weatherApi: WeatherApi,
        private val widgetStateManager: WidgetStateManager,
        private val climateNormalDao: ClimateNormalDao,
        private val observationDao: ObservationDao,
    ) {
        internal data class ObservationResult(
            val highTemp: Float,
            val lowTemp: Float,
            val stationId: String,
            val condition: String
        )

        private val syncMutex = Mutex()
        private val prefs by lazy { context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE) }

        companion object {
            private const val MIN_NETWORK_INTERVAL_MS = 600_000L // 10 minutes
            private const val NWS_PERIOD_SUMMARY_COUNT = 8
            private const val MAX_RETRIES = 5
        }

        private var lastFetchTime: Long
            get() = FetchMetadata.getLastFullFetchTime(context)
            set(value) = FetchMetadata.setLastFullFetchTime(context, value)

        suspend fun getWeatherData(
            latitude: Double,
            longitude: Double,
            locationName: String,
            forceRefresh: Boolean = false,
            networkAllowed: Boolean = true,
            targetSourceId: String? = null,
            onCurrentTempCallback: (suspend (String, Float, Long, String?) -> Unit)? = null
        ): Result<List<ForecastEntity>> {
            try {
                // Initial check without locking
                var cachedForecasts = getCachedData(latitude, longitude)
                if (!forceRefresh && !requiresNetworkFetch(cachedForecasts)) {
                    return Result.success(cachedForecasts)
                }

                if (!networkAllowed) return Result.success(cachedForecasts)

                syncMutex.withLock {
                    // Re-read data after acquiring lock to ensure another thread didn't just update it
                    cachedForecasts = getCachedData(latitude, longitude)
                    if (!forceRefresh && !requiresNetworkFetch(cachedForecasts)) {
                        return Result.success(cachedForecasts)
                    }

                    // Enforce a hard throttle on full network fetches unless forced
                    val timeSinceLastFetch = System.currentTimeMillis() - lastFetchTime
                    if (!forceRefresh && timeSinceLastFetch < MIN_NETWORK_INTERVAL_MS && cachedForecasts.isNotEmpty()) {
                        return Result.success(cachedForecasts)
                    }

                    appLogDao.log("NET_FETCH_START", "force=$forceRefresh target=$targetSourceId")
                    
                    fun shouldForceSource(source: WeatherSource): Boolean {
                        if (!forceRefresh) return false
                        if (targetSourceId == null) return true // Force all if no target specified
                        return source.id == targetSourceId
                    }

                    // Perform parallel fetches from all APIs
                    val (nwsForecasts, meteoForecasts, wapiForecasts) = fetchFromAllApis(
                        latitude, longitude, locationName,
                        shouldForceSource(WeatherSource.NWS) || isStale(WeatherSource.NWS, cachedForecasts),
                        shouldForceSource(WeatherSource.OPEN_METEO) || isStale(WeatherSource.OPEN_METEO, cachedForecasts),
                        shouldForceSource(WeatherSource.WEATHER_API) || isStale(WeatherSource.WEATHER_API, cachedForecasts),
                        onCurrentTempCallback
                    )

                    // Determine the end of our real forecast coverage to fill in the rest with climate normals
                    val maxCoverageDates = mutableListOf<LocalDate>()
                    nwsForecasts?.maxOfOrNull { LocalDate.parse(it.targetDate) }?.let { maxCoverageDates.add(it) }
                    meteoForecasts?.maxOfOrNull { LocalDate.parse(it.targetDate) }?.let { maxCoverageDates.add(it) }
                    wapiForecasts?.maxOfOrNull { LocalDate.parse(it.targetDate) }?.let { maxCoverageDates.add(it) }
                    
                    // If any expected source is missing from both the fresh fetch AND the cache, 
                    // we need to fill from today onwards
                    if (maxCoverageDates.isEmpty() && cachedForecasts.isEmpty()) {
                        maxCoverageDates.add(LocalDate.now().minusDays(1))
                    } else if (maxCoverageDates.isEmpty()) {
                        // Use the max date from cache if no fresh fetch succeeded
                        cachedForecasts.maxOfOrNull { LocalDate.parse(it.targetDate) }?.let { maxCoverageDates.add(it) }
                    }
                    
                    val minMaxDate = maxCoverageDates.minOrNull() ?: LocalDate.now()
                    val climateGaps = fetchClimateNormalsGap(latitude, longitude, locationName, minMaxDate, 30)
                    if (climateGaps.isNotEmpty()) {
                        forecastDao.insertAll(climateGaps)
                    }

                    cleanOldData()
                    lastFetchTime = System.currentTimeMillis()
                    
                    return Result.success(getCachedData(latitude, longitude))
                }
            } catch (exception: Exception) {
                lastFetchTime = 0L // Allow immediate retry on error
                appLogDao.log("NET_FETCH_ERROR", "${exception.message}", "ERROR")
                val fallbackData = getCachedData(latitude, longitude)
                return if (fallbackData.isNotEmpty()) Result.success(fallbackData) else Result.failure(exception)
            }
        }

        private fun requiresNetworkFetch(forecasts: List<ForecastEntity>): Boolean {
            val sourcesToCheck = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API)
            return sourcesToCheck.any { source ->
                val isNeeded = widgetStateManager.isSourceVisible(source) || isPlugged()
                isNeeded && isStale(source, forecasts)
            }
        }

        private fun isStale(source: WeatherSource, forecasts: List<ForecastEntity>): Boolean {
            val lastSourceFetchTime = forecasts.filter { it.source == source.id }.maxOfOrNull { it.fetchedAt } ?: 0L
            val visibleSources = widgetStateManager.getVisibleSourcesOrder()
            val position = visibleSources.indexOf(source)
            
            // If not visible, use the longest threshold (position = -1 maps to 120m)
            val threshold = ForecastStalenessPolicy.getStalenessThresholdMs(position)
            return System.currentTimeMillis() - lastSourceFetchTime >= threshold
        }

        private suspend fun fetchFromAllApis(
            latitude: Double,
            longitude: Double,
            locationName: String,
            shouldFetchNws: Boolean,
            shouldFetchMeteo: Boolean,
            shouldFetchWapi: Boolean,
            onCurrentTempCallback: (suspend (String, Float, Long, String?) -> Unit)?
        ): Triple<List<ForecastEntity>?, List<ForecastEntity>?, List<ForecastEntity>?> = coroutineScope {
            val nwsDeferred = if (shouldFetchNws) async {
                try { fetchFromNws(latitude, longitude, locationName) } catch (exception: Exception) { 
                    appLogDao.log("FETCH_NWS_FAIL", "${exception.message}", "WARN")
                    null 
                }
            } else null
            
            val meteoDeferred = if (shouldFetchMeteo) async {
                try {
                    val result = openMeteoApi.getForecast(latitude, longitude, 7)
                    if (result.hourly.isNotEmpty()) {
                        saveHourlyForecasts(result.hourly, latitude, longitude)
                    }
                    if (result.currentTemp != null && onCurrentTempCallback != null) {
                        onCurrentTempCallback(
                            WeatherSource.OPEN_METEO.id, 
                            result.currentTemp, 
                            result.currentObservedAt ?: System.currentTimeMillis(), 
                            null
                        )
                    }
                    result.daily.map { day ->
                        ForecastEntity(
                            targetDate = day.date, 
                            forecastDate = LocalDate.now().toString(), 
                            locationLat = latitude, 
                            locationLon = longitude, 
                            locationName = locationName,
                            highTemp = day.highTemp, 
                            lowTemp = day.lowTemp, 
                            condition = openMeteoApi.weatherCodeToCondition(day.weatherCode),
                            isClimateNormal = false, 
                            source = WeatherSource.OPEN_METEO.id, 
                            precipProbability = day.precipProbability
                        )
                    }
                } catch (exception: Exception) {
                    appLogDao.log("FETCH_METEO_FAIL", "${exception.message}", "WARN")
                    null
                }
            } else null
            
            val wapiDeferred = if (shouldFetchWapi) async {
                try {
                    val result = weatherApi.getForecast(latitude, longitude, 14)
                    if (result.hourly.isNotEmpty()) {
                        saveWeatherApiHourlyForecasts(result.hourly, latitude, longitude)
                    }
                    if (result.currentTemp != null && onCurrentTempCallback != null) {
                        onCurrentTempCallback(
                            WeatherSource.WEATHER_API.id, 
                            result.currentTemp, 
                            result.currentObservedAt ?: System.currentTimeMillis(), 
                            null
                        )
                    }
                    result.daily.map { day ->
                        ForecastEntity(
                            targetDate = day.date, 
                            forecastDate = LocalDate.now().toString(), 
                            locationLat = latitude, 
                            locationLon = longitude, 
                            locationName = locationName,
                            highTemp = day.highTemp, 
                            lowTemp = day.lowTemp, 
                            condition = day.condition,
                            isClimateNormal = false, 
                            source = WeatherSource.WEATHER_API.id, 
                            precipProbability = day.precipProbability
                        )
                    }
                } catch (exception: Exception) {
                    appLogDao.log("FETCH_WAPI_FAIL", "${exception.message}", "WARN")
                    null
                }
            } else null

            val nwsForecasts = nwsDeferred?.await()
            val meteoForecasts = meteoDeferred?.await()
            val wapiForecasts = wapiDeferred?.await()

            // Save snapshots with deduplication logic
            nwsForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.NWS.id) }
            meteoForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.OPEN_METEO.id) }
            wapiForecasts?.let { saveForecastSnapshot(it, latitude, longitude, WeatherSource.WEATHER_API.id) }

            Triple(nwsForecasts, meteoForecasts, wapiForecasts)
        }

        internal suspend fun fetchFromNws(latitude: Double, longitude: Double, locationName: String): List<ForecastEntity> = coroutineScope {
            val grid = nwsApi.getGridPoint(latitude, longitude)
            val forecastDeferred = async { nwsApi.getForecast(grid) }
            val hourlyDeferred = async { nwsApi.getHourlyForecast(grid) }
            
            val forecastPeriods = forecastDeferred.await()
            val hourlyPeriods = hourlyDeferred.await()
            val todayDate = LocalDate.now()
            val todayDateString = todayDate.toString()

            persistNwsPeriodSummary(grid.forecastUrl, forecastPeriods)
            if (hourlyPeriods.isNotEmpty()) {
                saveNwsHourlyForecasts(hourlyPeriods, latitude, longitude)
            }

            val temperatureMap = mutableMapOf<String, Pair<Float?, Float?>>()
            val conditionMap = mutableMapOf<String, String>()
            val conditionSourceMap = mutableMapOf<String, String>()
            val precipProbabilityMap = mutableMapOf<String, Int>()
            val highTempSourceMap = mutableMapOf<String, String>()
            val lowTempSourceMap = mutableMapOf<String, String>()
            val periodTimeMap = mutableMapOf<String, Pair<String?, String?>>()

            initPrecipFromHourly(hourlyPeriods, precipProbabilityMap)
            initConditionsFromHourly(hourlyPeriods, conditionMap, conditionSourceMap)

            val todayForecastPeriods = applyForecastPeriods(
                forecastPeriods, todayDateString, temperatureMap, conditionMap,
                conditionSourceMap, highTempSourceMap, lowTempSourceMap, precipProbabilityMap,
                periodTimeMap
            )
            logTodayDiagnostics(
                todayDateString, temperatureMap, highTempSourceMap, lowTempSourceMap, 
                conditionMap, conditionSourceMap, todayForecastPeriods, latitude, longitude
            )

            temperatureMap.map { (dateString, temperatures) ->
                val (pStart, pEnd) = periodTimeMap[dateString] ?: (null to null)
                ForecastEntity(
                    targetDate = dateString,
                    forecastDate = todayDateString,
                    locationLat = latitude,
                    locationLon = longitude,
                    locationName = locationName,
                    highTemp = temperatures.first,
                    lowTemp = temperatures.second,
                    condition = conditionMap[dateString] ?: "Unknown",
                    isClimateNormal = false,
                    source = WeatherSource.NWS.id,
                    precipProbability = precipProbabilityMap[dateString],
                    periodStartTime = pStart,
                    periodEndTime = pEnd,
                )
            }
        }

        private fun initPrecipFromHourly(
            hourlyPeriods: List<NwsApi.HourlyForecastPeriod>, 
            precipProbabilityMap: MutableMap<String, Int>
        ) {
            hourlyPeriods.forEach { hour ->
                runCatching { ZonedDateTime.parse(hour.startTime).toLocalDate().toString() }.getOrNull()?.let { dateString ->
                    val probability = hour.precipProbability ?: 0
                    if (probability > (precipProbabilityMap[dateString] ?: 0)) {
                        precipProbabilityMap[dateString] = probability
                    }
                }
            }
        }

        private fun initConditionsFromHourly(
            hourlyPeriods: List<NwsApi.HourlyForecastPeriod>, 
            conditionMap: MutableMap<String, String>, 
            sourceMap: MutableMap<String, String>
        ) {
            val todayDate = LocalDate.now()
            hourlyPeriods.groupBy { runCatching { ZonedDateTime.parse(it.startTime).toLocalDate().toString() }.getOrNull() }
                .forEach { (dateString, periods) ->
                    if (dateString != null && LocalDate.parse(dateString).isAfter(todayDate)) {
                        // Try to pick a midday condition for the daily summary
                        val targetHours = listOf(13, 14, 12, 15)
                        var bestPeriod: NwsApi.HourlyForecastPeriod? = null
                        for (hour in targetHours) {
                            bestPeriod = periods.find { runCatching { ZonedDateTime.parse(it.startTime).hour }.getOrNull() == hour }
                            if (bestPeriod != null) break
                        }
                        
                        if (bestPeriod != null) {
                            val midText = bestPeriod.shortForecast
                            // Priority check for fog
                            val hasFog = periods.any { 
                                val hour = runCatching { ZonedDateTime.parse(it.startTime).hour }.getOrDefault(-1)
                                hour in 5..10 && it.shortForecast.lowercase().contains("fog")
                            }
                            val isSunny = midText.lowercase().contains("sunny") || midText.lowercase().contains("clear")
                            
                            if (hasFog && isSunny) {
                                conditionMap[dateString] = "Fog then $midText"
                                sourceMap[dateString] = "HOURLY_MIDDAY_TRANSITION:${bestPeriod.startTime}"
                                return@forEach
                            }
                            
                            if (midText.lowercase().contains("fog")) {
                                periods.find { it.shortForecast.lowercase().contains("sunny") || it.shortForecast.lowercase().contains("clear") }?.let {
                                    conditionMap[dateString] = it.shortForecast
                                    sourceMap[dateString] = "HOURLY_MIDDAY_SUN_PRIORITY:${it.startTime}"
                                    return@forEach
                                }
                            }
                            
                            conditionMap[dateString] = midText
                            sourceMap[dateString] = "HOURLY_MIDDAY:${bestPeriod.startTime}"
                        }
                    }
                }
        }

        private fun applyForecastPeriods(
            forecastPeriods: List<NwsApi.ForecastPeriod>,
            todayDateString: String,
            temperatureMap: MutableMap<String, Pair<Float?, Float?>>,
            conditionMap: MutableMap<String, String>,
            conditionSourceMap: MutableMap<String, String>,
            highTempSourceMap: MutableMap<String, String>,
            lowTempSourceMap: MutableMap<String, String>,
            precipProbabilityMap: MutableMap<String, Int>,
            periodTimeMap: MutableMap<String, Pair<String?, String?>>
        ): List<NwsApi.ForecastPeriod> {
            val todayPeriods = mutableListOf<NwsApi.ForecastPeriod>()
            forecastPeriods.forEach { period ->
                val dateString = extractNwsForecastDate(period.startTime) ?: return@forEach
                if (dateString == todayDateString) todayPeriods.add(period)

                val probability = period.precipProbability
                if (probability != null && !precipProbabilityMap.containsKey(dateString)) {
                    precipProbabilityMap[dateString] = probability
                }

                if (period.isDaytime) {
                    val currentTemps = temperatureMap[dateString] ?: (null to null)
                    temperatureMap[dateString] = period.temperature.toFloat() to currentTemps.second
                    highTempSourceMap[dateString] = "FCST:${period.name}@${period.startTime}"
                    periodTimeMap[dateString] = period.startTime to period.endTime
                } else {
                    // The overnight low physically occurs in the early morning of the following day.
                    // Use the endTime's date (not startTime's) so the low is attributed correctly.
                    val lowDateString = extractNwsForecastDate(period.endTime) ?: dateString
                    val currentLowTemps = temperatureMap[lowDateString] ?: (null to null)
                    temperatureMap[lowDateString] = currentLowTemps.first to period.temperature.toFloat()
                    lowTempSourceMap[lowDateString] = "FCST:${period.name}@${period.startTime}"
                }

                if (conditionMap[dateString] == null) {
                    conditionMap[dateString] = period.shortForecast
                    conditionSourceMap[dateString] = "FCST:${period.name}@${period.startTime}"
                }
            }
            return todayPeriods
        }

        private suspend fun logTodayDiagnostics(
            todayDateString: String,
            temperatureMap: Map<String, Pair<Float?, Float?>>,
            highTempSourceMap: Map<String, String>,
            lowTempSourceMap: Map<String, String>,
            conditionMap: MutableMap<String, String>,
            conditionSourceMap: MutableMap<String, String>,
            todayPeriods: List<NwsApi.ForecastPeriod>,
            latitude: Double,
            longitude: Double
        ) {
            // Only use the nighttime condition if we are currently IN the night period (after 6 PM)
            val currentHour = LocalDateTime.now().hour
            if (currentHour >= 18) {
                todayPeriods.firstOrNull { !it.isDaytime }?.let { period ->
                    conditionMap[todayDateString] = period.shortForecast
                    conditionSourceMap[todayDateString] = "FCST_ACTIVE:${period.name}@${period.startTime}"
                }
            }
            
            val todayTemps = temperatureMap[todayDateString] ?: return
            appLogDao.log(
                "NWS_TODAY_SOURCE", 
                "high=${todayTemps.first} (${highTempSourceMap[todayDateString]}) " +
                "low=${todayTemps.second} (${lowTempSourceMap[todayDateString]}) " +
                "cond=${conditionMap[todayDateString]} (${conditionSourceMap[todayDateString]})"
            )
        }

        @androidx.annotation.VisibleForTesting
        internal suspend fun saveForecastSnapshot(
            weatherForecasts: List<ForecastEntity>, 
            latitude: Double, 
            longitude: Double, 
            sourceId: String
        ) {
            val todayDate = LocalDate.now()
            val todayDateString = todayDate.toString()
            val now = ZonedDateTime.now()
            val forecastsToSave = weatherForecasts.filter { forecast ->
                val date = runCatching { LocalDate.parse(forecast.targetDate) }.getOrNull()
                if (date == null || date.isBefore(todayDate) || forecast.isClimateNormal) return@filter false
                // Exclude entries whose daytime period has already ended — NWS overwrites elapsed
                // periods with observed reality, so snapshotting them would corrupt accuracy tracking
                // by making tomorrow's "forecast vs actual" comparison observed-vs-observed.
                val periodEnd = runCatching { ZonedDateTime.parse(forecast.periodEndTime) }.getOrNull()
                if (periodEnd != null && periodEnd.isBefore(now)) {
                    appLogDao.log("SNAPSHOT_SKIP_ELAPSED", "date=${forecast.targetDate} source=${forecast.source} periodEnd=${forecast.periodEndTime}")
                    return@filter false
                }
                true
            }.mapNotNull { forecast ->
                if (forecast.highTemp == null && forecast.lowTemp == null) return@mapNotNull null
                
                // Preserve full decimal precision for Today's forecast to improve accuracy tracking.
                // Continue rounding future days to integers for UI consistency and storage.
                val isToday = forecast.targetDate == todayDateString
                val highTempSaved = if (isToday) forecast.highTemp else forecast.highTemp?.roundToInt()?.toFloat()
                val lowTempSaved = if (isToday) forecast.lowTemp else forecast.lowTemp?.roundToInt()?.toFloat()
                
                ForecastEntity(
                    targetDate = forecast.targetDate,
                    forecastDate = todayDate.toString(),
                    locationLat = latitude,
                    locationLon = longitude,
                    locationName = "",
                    highTemp = highTempSaved,
                    lowTemp = lowTempSaved,
                    condition = forecast.condition,
                    isClimateNormal = forecast.isClimateNormal,
                    source = sourceId,
                    precipProbability = forecast.precipProbability,
                    fetchedAt = System.currentTimeMillis()
                )
            }

            if (forecastsToSave.isNotEmpty()) {
                // Use the optimized DAO query to get existing records for comparison
                val existingForecasts = forecastDao.getForecastsInRangeBySource(
                    startDate = todayDate.toString(),
                    endDate = todayDate.plusDays(14).toString(),
                    lat = latitude,
                    lon = longitude,
                    source = sourceId
                )
                
                // associateBy picks the first occurrence. 
                // Since DAO orders by fetchedAt DESC, this will be the latest record for each targetDate.
                val latestByDate = existingForecasts.associateBy { it.targetDate }
                
                val changedForecasts = forecastsToSave.filter { newlyFetched ->
                    val existing = latestByDate[newlyFetched.targetDate]
                    if (existing != null && 
                        existing.highTemp == newlyFetched.highTemp && 
                        existing.lowTemp == newlyFetched.lowTemp && 
                        existing.condition == newlyFetched.condition &&
                        existing.precipProbability == newlyFetched.precipProbability) {
                        appLogDao.log("SNAPSHOT_SKIP", "date=${newlyFetched.targetDate} source=$sourceId")
                        false
                    } else {
                        appLogDao.log("SNAPSHOT_SAVE", "date=${newlyFetched.targetDate} source=$sourceId")
                        true
                    }
                }
                
                if (changedForecasts.isNotEmpty()) {
                    forecastDao.insertAll(changedForecasts)
                }
            }
        }

        private suspend fun fetchClimateNormalsGap(
            latitude: Double, 
            longitude: Double, 
            locationName: String, 
            lastCoveredDate: LocalDate, 
            targetDays: Int
        ): List<ForecastEntity> {
            val targetEndDate = LocalDate.now().plusDays(targetDays.toLong())
            var cursorDate = lastCoveredDate.plusDays(1)
            val normalsMap = getHistoricalNormalsByMonthDay(latitude, longitude)
            val gapEntities = mutableListOf<ForecastEntity>()
            
            while (!cursorDate.isAfter(targetEndDate)) {
                normalsMap[MonthDay.from(cursorDate)]?.let { (highTemp, lowTemp) ->
                    gapEntities.add(ForecastEntity(
                        cursorDate.toString(), cursorDate.toString(), latitude, longitude, "",
                        highTemp.toFloat(), lowTemp.toFloat(), "Historical Avg", true,
                        source = WeatherSource.GENERIC_GAP.id
                    ))
                }
                cursorDate = cursorDate.plusDays(1)
            }
            return gapEntities
        }

        private suspend fun getHistoricalNormalsByMonthDay(latitude: Double, longitude: Double): Map<MonthDay, Pair<Int, Int>> {
            val locationKey = "${(latitude * 10).roundToInt() / 10.0}_${(longitude * 10).roundToInt() / 10.0}"
            val cachedNormals = climateNormalDao.getNormalsForLocation(locationKey)
            
            if (cachedNormals.isNotEmpty()) {
                return cachedNormals.associate { 
                    MonthDay.of(it.monthDay.take(2).toInt(), it.monthDay.takeLast(2).toInt()) to (it.highTemp to it.lowTemp)
                }
            }
            
            val climateData = openMeteoApi.getClimateForecast(latitude, longitude, "2020-01-01", "2020-12-31")
            val normalsMap = climateData.associate { 
                MonthDay.from(LocalDate.parse(it.date)) to (it.highTemp.roundToInt() to it.lowTemp.roundToInt())
            }
            
            climateNormalDao.deleteOtherLocations(locationKey)
            climateNormalDao.insertAll(normalsMap.map { (monthDay, temperatures) -> 
                ClimateNormalEntity(
                    "${monthDay.monthValue.toString().padStart(2, '0')}-${monthDay.dayOfMonth.toString().padStart(2, '0')}", 
                    locationKey, temperatures.first, temperatures.second
                )
            })
            
            return normalsMap
        }

        internal suspend fun fetchDayObservations(stationUrl: String, date: LocalDate): ObservationResult? {
            if (stationUrl.isEmpty()) return null
            return fetchDayObservations(getSortedObservationStations(stationUrl), date)
        }

        internal suspend fun fetchDayObservations(stations: List<NwsApi.StationInfo>, date: LocalDate): ObservationResult? {
            val localZone = ZoneId.systemDefault()
            val startTimeStr = date.atStartOfDay(localZone).format(DateTimeFormatter.ISO_INSTANT)
            val endTimeStr = date.plusDays(1).atStartOfDay(localZone).format(DateTimeFormatter.ISO_INSTANT)

            for (stationInfo in stations.take(MAX_RETRIES)) {
                try {
                    val observations = nwsApi.getObservations(stationInfo.id, startTimeStr, endTimeStr)
                    if (observations.isEmpty()) continue
                    
                    val temperaturesF = observations.map { (it.temperatureCelsius * 1.8f) + 32f }
                    val highTemp = temperaturesF.maxOrNull() ?: continue
                    val lowTemp = temperaturesF.minOrNull() ?: continue
                    
                    // Logic to extract condition from observation text
                    val daylightObservations = observations.filter { 
                        runCatching { ZonedDateTime.parse(it.timestamp).withZoneSameInstant(localZone).hour }.getOrDefault(12) in 7..19 
                    }.ifEmpty { observations }
                    
                    val hasPrecipitation = daylightObservations.any { 
                        val description = it.textDescription.lowercase()
                        description.contains("rain") || description.contains("shower") || description.contains("storm") || description.contains("snow")
                    }
                    
                    val cloudScores = daylightObservations.map { 
                        val description = it.textDescription.lowercase()
                        when {
                            description.contains("mostly cloudy") -> 75
                            description.contains("mostly clear") || description.contains("mostly sunny") -> 25
                            description.contains("partly") -> 50
                            description.contains("cloudy") || description.contains("overcast") -> 100
                            description.contains("fair") || description.contains("sunny") || description.contains("clear") -> 0
                            else -> 50
                        }
                    }
                    
                    val averageCloudScore = if (cloudScores.isNotEmpty()) cloudScores.average() else 50.0
                    val baseCondition = if (hasPrecipitation) "Rain" else when {
                        averageCloudScore <= 15 -> "Sunny"
                        averageCloudScore <= 35 -> "Mostly Sunny"
                        averageCloudScore <= 65 -> "Partly Cloudy"
                        averageCloudScore <= 85 -> "Mostly Cloudy"
                        else -> "Cloudy"
                    }
                    
                    val finalCondition = if (averageCloudScore == 0.0 || averageCloudScore == 100.0) baseCondition else "$baseCondition (${averageCloudScore.roundToInt()}%)"
                    
                    return ObservationResult(highTemp, lowTemp, stationInfo.id, finalCondition)
                } catch (exception: Exception) {
                    // Fail silently and try next station
                }
            }
            return null
        }

        private suspend fun getSortedObservationStations(stationsUrl: String): List<NwsApi.StationInfo> {
            val stationsKey = "observation_stations_v2_${stationsUrl.hashCode()}"
            val timeKey = "observation_stations_time_v2_${stationsUrl.hashCode()}"
            val cachedStationsString = prefs.getString(stationsKey, null)
            val lastUpdateTimestamp = prefs.getLong(timeKey, 0)
            
            if (cachedStationsString != null && System.currentTimeMillis() - lastUpdateTimestamp < 86400000) {
                return cachedStationsString.split("|").map { 
                    val parts = it.split("\t")
                    NwsApi.StationInfo(parts[0], parts[1], parts[2].toDouble(), parts[3].toDouble())
                }
            }
            
            val fetchedStations = runCatching { nwsApi.getObservationStations(stationsUrl) }.getOrDefault(emptyList())
            if (fetchedStations.isNotEmpty()) {
                prefs.edit()
                    .putString(stationsKey, fetchedStations.joinToString("|") { "${it.id}\t${it.name}\t${it.lat}\t${it.lon}" })
                    .putLong(timeKey, System.currentTimeMillis())
                    .apply()
            }
            return fetchedStations
        }

        private fun extractNwsForecastDate(isoString: String): String? = 
            runCatching { ZonedDateTime.parse(isoString).toLocalDate().toString() }.getOrNull() 
            ?: runCatching { LocalDate.parse(isoString.take(10)).toString() }.getOrNull()

        private suspend fun saveHourlyEntities(entities: List<HourlyForecastEntity>) {
            if (entities.isEmpty()) return
            
            val minDateTime = entities.minOf { it.dateTime }
            val maxDateTime = entities.maxOf { it.dateTime }
            val existingByDateTime = hourlyForecastDao.getHourlyForecastsBySource(
                minDateTime, maxDateTime, entities.first().locationLat, entities.first().locationLon, entities.first().source
            ).associateBy { it.dateTime }
            
            val changedEntities = entities.filter { newlyFetched ->
                val existing = existingByDateTime[newlyFetched.dateTime]
                existing == null || existing.temperature != newlyFetched.temperature || existing.condition != newlyFetched.condition
            }
            
            if (changedEntities.isNotEmpty()) {
                hourlyForecastDao.insertAll(changedEntities)
            }
        }

        private suspend fun saveHourlyForecasts(hourlyData: List<OpenMeteoApi.HourlyForecast>, latitude: Double, longitude: Double) = 
            saveHourlyEntities(hourlyData.map { 
                HourlyForecastEntity(
                    it.dateTime, latitude, longitude, it.temperature, 
                    openMeteoApi.weatherCodeToCondition(it.weatherCode), 
                    WeatherSource.OPEN_METEO.id, it.precipProbability, System.currentTimeMillis()
                ) 
            })

        private suspend fun saveWeatherApiHourlyForecasts(hourlyData: List<WeatherApi.HourlyForecast>, latitude: Double, longitude: Double) = 
            saveHourlyEntities(hourlyData.map { 
                HourlyForecastEntity(
                    it.dateTime, latitude, longitude, it.temperature, it.condition, 
                    WeatherSource.WEATHER_API.id, it.precipProbability, System.currentTimeMillis()
                ) 
            })

        private suspend fun saveNwsHourlyForecasts(hourlyPeriods: List<NwsApi.HourlyForecastPeriod>, latitude: Double, longitude: Double) = 
            saveHourlyEntities(hourlyPeriods.mapNotNull { period -> 
                val dateTimeString = runCatching { 
                    ZonedDateTime.parse(period.startTime).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")) 
                }.getOrNull() ?: return@mapNotNull null
                HourlyForecastEntity(
                    dateTimeString, latitude, longitude, period.temperature.toFloat(), 
                    period.shortForecast, WeatherSource.NWS.id, period.precipProbability, System.currentTimeMillis()
                ) 
            })

        private suspend fun persistNwsPeriodSummary(url: String, forecastPeriods: List<NwsApi.ForecastPeriod>) {
            if (forecastPeriods.isEmpty()) return
            val now = ZonedDateTime.now()
            val compactSummary = forecastPeriods.take(NWS_PERIOD_SUMMARY_COUNT).mapIndexed { index, period ->
                val start = runCatching { ZonedDateTime.parse(period.startTime) }.getOrNull()
                val end = runCatching { ZonedDateTime.parse(period.endTime) }.getOrNull()
                val marker = when {
                    end != null && end.isBefore(now) -> "PAST"
                    start != null && start.isBefore(now) -> "ACTIVE"
                    else -> "FUTURE"
                }
                "$index[$marker]:${period.name}@${period.startTime}..${period.endTime}=${period.temperature}"
            }.joinToString("; ")
            appLogDao.log("NWS_PERIOD_SUMMARY", "url=$url first8=$compactSummary")
        }

        private fun isPlugged(): Boolean {
            val batteryStatusIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = batteryStatusIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
        }

        suspend fun getCachedData(latitude: Double, longitude: Double) = 
            forecastDao.getLatestForecastsInRange(LocalDate.now().minusDays(7).toString(), LocalDate.now().plusDays(30).toString(), latitude, longitude)

        suspend fun getCachedDataBySource(latitude: Double, longitude: Double, source: WeatherSource): List<ForecastEntity> {
            val startDate = LocalDate.now().minusDays(7).toString()
            val endDate = LocalDate.now().plusDays(30).toString()
            val gapData = forecastDao.getForecastsInRangeBySource(startDate, endDate, latitude, longitude, WeatherSource.GENERIC_GAP.id)
            val sourceData = forecastDao.getForecastsInRangeBySource(startDate, endDate, latitude, longitude, source.id)
            
            return (gapData + sourceData).associateBy { it.targetDate }.values.sortedBy { it.targetDate }
        }

        suspend fun getForecastForDate(dateString: String, latitude: Double, longitude: Double) = 
            forecastDao.getForecastForDate(dateString, latitude, longitude)
            
        suspend fun getForecastForDateBySource(dateString: String, latitude: Double, longitude: Double, source: WeatherSource): ForecastEntity? = 
            forecastDao.getForecastsInRangeBySource(dateString, dateString, latitude, longitude, source.id).firstOrNull()

        suspend fun getForecastsInRange(startDate: String, endDate: String, latitude: Double, longitude: Double) = 
            forecastDao.getForecastsInRange(startDate, endDate, latitude, longitude)
            
        suspend fun getWeatherRange(startDate: String, endDate: String, latitude: Double, longitude: Double) = 
            forecastDao.getForecastsInRange(startDate, endDate, latitude, longitude)

        suspend fun cleanOldData() {
            val oneMonthAgoTimestamp = System.currentTimeMillis() - 2592000000L // 30 days
            val logsCutoffTimestamp = System.currentTimeMillis() - 259200000L // 72 hours
            forecastDao.deleteOldForecasts(oneMonthAgoTimestamp)
            hourlyForecastDao.deleteOldForecasts(oneMonthAgoTimestamp)
            observationDao.deleteOldObservations(oneMonthAgoTimestamp)
            appLogDao.deleteOldLogs(logsCutoffTimestamp)
        }
    }

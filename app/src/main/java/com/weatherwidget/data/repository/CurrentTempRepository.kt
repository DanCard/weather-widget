package com.weatherwidget.data.repository

import android.content.Context
import android.location.Location
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.CurrentTempDao
import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.WeatherObservationDao
import com.weatherwidget.data.local.WeatherObservationEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        @ApplicationContext private val context: Context,
        private val currentTempDao: CurrentTempDao,
        private val weatherObservationDao: WeatherObservationDao,
        private val hourlyForecastDao: HourlyForecastDao,
        private val appLogDao: AppLogDao,
        private val nwsApi: NwsApi,
        private val openMeteoApi: OpenMeteoApi,
        private val weatherApi: WeatherApi,
        private val widgetStateManager: WidgetStateManager,
        private val temperatureInterpolator: TemperatureInterpolator,
    ) {
        private val syncMutex = Mutex()
        companion object { private const val CURRENT_TEMP_FRESHNESS_MS = 300000L; private const val MAX_RETRIES = 5 }
        private var lastFetch: Long
            get() = FetchMetadata.getLastCurrentTempFetchTime(context)
            set(v) = FetchMetadata.setLastCurrentTempFetchTime(context, v)
        private val prefs by lazy { context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE) }

        suspend fun refreshCurrentTemperature(lat: Double, lon: Double, loc: String, source: WeatherSource? = null, reason: String = "unspecified", force: Boolean = false): Result<Int> {
            return try {
                syncMutex.withLock {
                    val now = System.currentTimeMillis()
                    if (!force && now - lastFetch < CURRENT_TEMP_FRESHNESS_MS) return Result.success(0)
                    recordHistoricalPoi(lat, lon, loc)
                    val targets = (source?.let { listOf(it) } ?: widgetStateManager.getVisibleSourcesOrder()).filter { it != WeatherSource.GENERIC_GAP }.distinct()
                    appLogDao.log("CURR_FETCH_START", "reason=$reason targets=${targets.joinToString { it.id }}")
                    targets.forEach { s ->
                        try {
                            val r = fetchFromSource(s, lat, lon) ?: return@forEach
                            currentTempDao.insert(CurrentTempEntity(LocalDate.now().toString(), r.source.id, lat, lon, r.temperature, r.observedAt ?: now, r.condition, now))
                        } catch (e: Exception) { appLogDao.log("CURR_FETCH_ERROR", "source=${s.id} error=${e.message}", "WARN") }
                    }
                    lastFetch = System.currentTimeMillis(); Result.success(targets.size)
                }
            } catch (e: Exception) { Result.failure(e) }
        }

        private suspend fun fetchFromSource(s: WeatherSource, lat: Double, lon: Double): CurrentReadingPayload? = when (s) {
            WeatherSource.OPEN_METEO -> fetchOpenMeteoCurrent(lat, lon)
            WeatherSource.WEATHER_API -> fetchWeatherApiCurrent(lat, lon)
            WeatherSource.NWS -> fetchNwsCurrent(lat, lon)
            else -> null
        }

        private suspend fun fetchOpenMeteoCurrent(lat: Double, lon: Double): CurrentReadingPayload? = coroutineScope {
            val ps = getPois(lat, lon)
            val rs = ps.mapIndexed { i, p ->
                async {
                    val r = runCatching { openMeteoApi.getCurrent(p.first, p.second) }.getOrNull()
                    if (r != null) {
                        val cond = r.weatherCode?.let { openMeteoApi.weatherCodeToCondition(it) } ?: "Unknown"
                        weatherObservationDao.insertAll(listOf(WeatherObservationEntity(if (p.third == "Current") "OPEN_METEO_MAIN" else "OPEN_METEO_$i", "Meteo: ${p.third}", r.observedAt ?: System.currentTimeMillis(), r.temperature, cond, lat, lon, calculateDistance(lat, lon, p.first, p.second) / 1000f, "OFFICIAL")))
                    }
                    r
                }
            }.map { it.await() }
            rs.firstNotNullOfOrNull { it }?.let { CurrentReadingPayload(WeatherSource.OPEN_METEO, it.temperature, it.weatherCode?.let { c -> openMeteoApi.weatherCodeToCondition(c) }, it.observedAt) }
        }

        private suspend fun fetchWeatherApiCurrent(lat: Double, lon: Double): CurrentReadingPayload? = coroutineScope {
            val ps = getPois(lat, lon)
            val rs = ps.mapIndexed { i, p ->
                async {
                    val r = runCatching { weatherApi.getCurrent(p.first, p.second) }.getOrNull()
                    if (r != null) {
                        weatherObservationDao.insertAll(listOf(WeatherObservationEntity(if (p.third == "Current") "WEATHER_API_MAIN" else "WEATHER_API_$i", "WAPI: ${p.third}", r.observedAt ?: System.currentTimeMillis(), r.temperature, r.condition ?: "Unknown", lat, lon, calculateDistance(lat, lon, p.first, p.second) / 1000f, "OFFICIAL")))
                    }
                    r
                }
            }.map { it.await() }
            rs.firstNotNullOfOrNull { it }?.let { CurrentReadingPayload(WeatherSource.WEATHER_API, it.temperature, it.condition, it.observedAt) }
        }

        private suspend fun fetchNwsCurrent(lat: Double, lon: Double): CurrentReadingPayload? = coroutineScope {
            val grid = nwsApi.getGridPoint(lat, lon); val ss = getSortedObservationStations(grid.observationStationsUrl ?: "")
            if (ss.isEmpty()) return@coroutineScope null
            val os = ss.take(MAX_RETRIES).map { info ->
                async {
                    val o = runCatching { nwsApi.getLatestObservationDetailed(info.id) }.getOrNull()
                    if (o != null) {
                        weatherObservationDao.insertAll(listOf(WeatherObservationEntity(info.id, o.stationName, OffsetDateTime.parse(o.timestamp).toInstant().toEpochMilli(), (o.temperatureCelsius * 1.8f) + 32f, o.textDescription, lat, lon, calculateDistance(lat, lon, info.lat, info.lon) / 1000f, info.type.name)))
                    }
                    o
                }
            }.map { it.await() }
            os.firstNotNullOfOrNull { it }?.let { CurrentReadingPayload(WeatherSource.NWS, (it.temperatureCelsius * 1.8f) + 32f, it.textDescription, OffsetDateTime.parse(it.timestamp).toInstant().toEpochMilli()) }
        }

        private fun getPois(lat: Double, lon: Double): List<Triple<Double, Double, String>> {
            val list = mutableListOf(Triple(lat, lon, "Current"), Triple(lat + 0.072, lon, "North"), Triple(lat - 0.072, lon, "South"), Triple(lat, lon + 0.09, "East"), Triple(lat, lon - 0.09, "West"))
            getHistoricalPois().forEach { (hLat, hLon, hName) -> if (calculateDistance(lat, lon, hLat, hLon) > 1000) list.add(Triple(hLat, hLon, "Recent: $hName")) }
            return list.distinctBy { "${it.first},${it.second}" }
        }

        suspend fun getInterpolatedTemperature(lat: Double, lon: Double, time: LocalDateTime = LocalDateTime.now()): Float? {
            val s = time.minusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")); val e = time.plusHours(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val h = hourlyForecastDao.getHourlyForecasts(s, e, lat, lon); return if (h.isEmpty()) null else temperatureInterpolator.getInterpolatedTemperature(h, time)
        }

        suspend fun getNextInterpolationUpdateTime(lat: Double, lon: Double, time: LocalDateTime = LocalDateTime.now()): LocalDateTime {
            val c = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")); val n = time.plusHours(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            val fs = hourlyForecastDao.getHourlyForecasts(c, n, lat, lon); val diff = if (fs.size >= 2) (fs[1].temperature - fs[0].temperature).toInt() else 0
            return temperatureInterpolator.getNextUpdateTime(time, diff)
        }

        private suspend fun getSortedObservationStations(url: String): List<NwsApi.StationInfo> {
            if (url.isEmpty()) return emptyList(); val key = "observation_stations_v2_${url.hashCode()}"; val tKey = "observation_stations_time_v2_${url.hashCode()}"
            val cached = prefs.getString(key, null); val t = prefs.getLong(tKey, 0)
            if (cached != null && System.currentTimeMillis() - t < 86400000) return cached.split("|").map { val p = it.split(","); NwsApi.StationInfo(p[0], p[1], p[2].toDouble(), p[3].toDouble()) }
            val ss = runCatching { nwsApi.getObservationStations(url) }.getOrDefault(emptyList())
            if (ss.isNotEmpty()) prefs.edit().putString(key, ss.joinToString("|") { "${it.id},${it.name},${it.lat},${it.lon}" }).putLong(tKey, System.currentTimeMillis()).apply()
            return ss
        }

        @androidx.annotation.VisibleForTesting
        internal fun recordHistoricalPoi(lat: Double, lon: Double, name: String) {
            val pois = prefs.getString("historical_pois", "")!!.split(";").filter { it.isNotEmpty() }.toMutableList()
            pois.removeIf { it.contains("|$lat|$lon") }; pois.add(0, "$name|$lat|$lon")
            prefs.edit().putString("historical_pois", pois.take(3).joinToString(";")).apply()
        }

        @androidx.annotation.VisibleForTesting
        internal fun getHistoricalPois(): List<Triple<Double, Double, String>> = prefs.getString("historical_pois", "")!!.split(";").filter { it.isNotEmpty() }.mapNotNull { runCatching { val p = it.split("|"); Triple(p[1].toDouble(), p[2].toDouble(), p[0]) }.getOrNull() }

        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float { val r = FloatArray(1); Location.distanceBetween(lat1, lon1, lat2, lon2, r); return r[0] }
    }

internal data class CurrentReadingPayload(val source: WeatherSource, val temperature: Float, val condition: String?, val observedAt: Long?)

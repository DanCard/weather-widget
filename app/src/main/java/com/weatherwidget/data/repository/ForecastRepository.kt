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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.OffsetDateTime
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
    ) {
        internal data class ObservationResult(val highTemp: Float, val lowTemp: Float, val stationId: String, val condition: String)
        private val syncMutex = Mutex()
        private val prefs by lazy { context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE) }
        companion object { private const val MIN_NETWORK_INTERVAL_MS = 600_000L; private const val NWS_PERIOD_SUMMARY_COUNT = 8; private const val DAYS_OF_HISTORY = 8; private const val MAX_RETRIES = 5 }
        private var lastFetch: Long
            get() = FetchMetadata.getLastFullFetchTime(context)
            set(v) = FetchMetadata.setLastFullFetchTime(context, v)

        suspend fun getWeatherData(
            lat: Double,
            lon: Double,
            loc: String,
            force: Boolean = false,
            net: Boolean = true,
            targetSourceId: String? = null,
            onCurr: (suspend (String, Float, Long, String?) -> Unit)? = null
        ): Result<List<ForecastEntity>> {
            try {
                val c = getCachedData(lat, lon); if (!force && !requiresNetworkFetch(c)) return Result.success(c)
                if (!net) return Result.success(c)
                syncMutex.withLock {
                    val f = getCachedData(lat, lon); if (!force && !requiresNetworkFetch(f)) return Result.success(f)
                    if (System.currentTimeMillis() - lastFetch < MIN_NETWORK_INTERVAL_MS && f.isNotEmpty()) return Result.success(f)
                    appLogDao.log("NET_FETCH_START", "force=$force target=$targetSourceId")
                    val s = System.currentTimeMillis()

                    fun shouldForce(source: WeatherSource): Boolean {
                        if (!force) return false
                        if (targetSourceId == null) return true // Force all if no target specified
                        return source.id == targetSourceId
                    }

                    val (n, m, w) = fetchFromAllApis(
                        lat, lon, loc,
                        shouldForce(WeatherSource.NWS) || isStale(WeatherSource.NWS, f),
                        shouldForce(WeatherSource.OPEN_METEO) || isStale(WeatherSource.OPEN_METEO, f),
                        shouldForce(WeatherSource.WEATHER_API) || isStale(WeatherSource.WEATHER_API, f),
                        onCurr
                    )
                    if (n != null) forecastDao.insertAll(n)
                    if (m != null) forecastDao.insertAll(m)
                    if (w != null) forecastDao.insertAll(w)
                    val l = listOfNotNull(n?.maxOfOrNull { LocalDate.parse(it.targetDate) }, m?.maxOfOrNull { LocalDate.parse(it.targetDate) }, w?.maxOfOrNull { LocalDate.parse(it.targetDate) }, if (n.isNullOrEmpty() || m.isNullOrEmpty() || w.isNullOrEmpty()) LocalDate.now().minusDays(1) else null).minOrNull() ?: LocalDate.now()
                    val g = fetchClimateNormalsGap(lat, lon, loc, l, 30); if (g.isNotEmpty()) forecastDao.insertAll(g)
                    cleanOldData(); lastFetch = System.currentTimeMillis(); return Result.success(getCachedData(lat, lon))
                }
            } catch (e: Exception) { lastFetch = 0L; appLogDao.log("NET_FETCH_ERROR", "${e.message}", "ERROR"); val c = getCachedData(lat, lon); return if (c.isNotEmpty()) Result.success(c) else Result.failure(e) }
        }

        private fun requiresNetworkFetch(c: List<ForecastEntity>) = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API).any { s -> (widgetStateManager.isSourceVisible(s) || isPlugged()) && isStale(s, c) }
        private fun isStale(s: WeatherSource, c: List<ForecastEntity>): Boolean {
            val last = c.filter { it.source == s.id }.maxOfOrNull { it.fetchedAt } ?: 0L
            val thresh = ForecastStalenessPolicy.getStalenessThresholdMs(
                widgetStateManager.getVisibleSourcesOrder().indexOf(s)
            )
            return System.currentTimeMillis() - last >= thresh
        }

        private suspend fun fetchFromAllApis(lat: Double, lon: Double, loc: String, fN: Boolean, fM: Boolean, fW: Boolean, onC: (suspend (String, Float, Long, String?) -> Unit)?): Triple<List<ForecastEntity>?, List<ForecastEntity>?, List<ForecastEntity>?> = coroutineScope {
            val nD = if (fN) async { try { fetchFromNws(lat, lon, loc) } catch (e: Exception) { null } } else null
            val mD = if (fM) async { try {
                val f = openMeteoApi.getForecast(lat, lon, 7); if (f.hourly.isNotEmpty()) saveHourlyForecasts(f.hourly, lat, lon)
                if (f.currentTemp != null && onC != null) onC(WeatherSource.OPEN_METEO.id, f.currentTemp, f.currentObservedAt ?: System.currentTimeMillis(), null)
                f.daily.map { d -> ForecastEntity(d.date, LocalDate.now().toString(), lat, lon, loc, d.highTemp, d.lowTemp, openMeteoApi.weatherCodeToCondition(d.weatherCode), false, source = WeatherSource.OPEN_METEO.id, precipProbability = d.precipProbability) }
            } catch (e: Exception) { null } } else null
            val wD = if (fW) async { try {
                val f = weatherApi.getForecast(lat, lon, 14); if (f.hourly.isNotEmpty()) saveWeatherApiHourlyForecasts(f.hourly, lat, lon)
                if (f.currentTemp != null && onC != null) onC(WeatherSource.WEATHER_API.id, f.currentTemp, f.currentObservedAt ?: System.currentTimeMillis(), null)
                f.daily.map { d -> ForecastEntity(d.date, LocalDate.now().toString(), lat, lon, loc, d.highTemp, d.lowTemp, d.condition, false, source = WeatherSource.WEATHER_API.id, precipProbability = d.precipProbability) }
            } catch (e: Exception) { null } } else null
            val n = nD?.await(); val m = mD?.await(); val w = wD?.await()
            n?.let { saveForecastSnapshot(it, lat, lon, "NWS") }; m?.let { saveForecastSnapshot(it, lat, lon, "OPEN_METEO") }; w?.let { saveForecastSnapshot(it, lat, lon, "WEATHER_API") }
            Triple(n, m, w)
        }

        internal suspend fun fetchFromNws(lat: Double, lon: Double, loc: String): List<ForecastEntity> = coroutineScope {
            val grid = nwsApi.getGridPoint(lat, lon); val fD = async { nwsApi.getForecast(grid) }; val hD = async { nwsApi.getHourlyForecast(grid) }
            val f = fD.await(); val h = hD.await(); val today = LocalDate.now(); val todayStr = today.toString()
            persistNwsPeriodSummary(grid.forecastUrl, f); if (h.isNotEmpty()) saveNwsHourlyForecasts(h, lat, lon)
            val w = mutableMapOf<String, Pair<Float?, Float?>>(); val c = mutableMapOf<String, String>(); val cS = mutableMapOf<String, String>(); val p = mutableMapOf<String, Int>(); val s = mutableMapOf<String, String>(); val hS = mutableMapOf<String, String>(); val lS = mutableMapOf<String, String>()
            initPrecipFromHourly(h, p); initConditionsFromHourly(h, c, cS)
            val tPs = applyForecastPeriods(f, todayStr, w, c, cS, hS, lS, p)
            logTodayDiagnostics(todayStr, w, hS, lS, c, cS, tPs, lat, lon)
            w.map { (dt, ts) -> ForecastEntity(dt, LocalDate.now().toString(), lat, lon, loc, ts.first, ts.second, c[dt] ?: "Unknown", false, source = WeatherSource.NWS.id, precipProbability = p[dt]) }
        }

        private fun initPrecipFromHourly(h: List<NwsApi.HourlyForecastPeriod>, p: MutableMap<String, Int>) = h.forEach { hr -> runCatching { ZonedDateTime.parse(hr.startTime).toLocalDate().toString() }.getOrNull()?.let { d -> val pop = hr.precipProbability ?: 0; if (pop > (p[d] ?: 0)) p[d] = pop } }
        private fun initConditionsFromHourly(h: List<NwsApi.HourlyForecastPeriod>, c: MutableMap<String, String>, s: MutableMap<String, String>) {
            val today = LocalDate.now()
            h.groupBy { runCatching { ZonedDateTime.parse(it.startTime).toLocalDate().toString() }.getOrNull() }.forEach { (d, ps) -> if (d != null && LocalDate.parse(d).isAfter(today)) {
                val targetHours = listOf(13, 14, 12, 15); var best: NwsApi.HourlyForecastPeriod? = null
                for (hr in targetHours) { best = ps.find { runCatching { ZonedDateTime.parse(it.startTime).hour }.getOrNull() == hr }; if (best != null) break }
                if (best != null) {
                    val mid = best.shortForecast; val fog = ps.any { val hr = runCatching { ZonedDateTime.parse(it.startTime).hour }.getOrDefault(-1); hr in 5..10 && it.shortForecast.lowercase().contains("fog") }
                    val sun = mid.lowercase().contains("sunny") || mid.lowercase().contains("clear")
                    if (fog && sun) { c[d] = "Fog then $mid"; s[d] = "HOURLY_MIDDAY_TRANSITION:${best.startTime}"; return@forEach }
                    if (mid.lowercase().contains("fog")) ps.find { it.shortForecast.lowercase().contains("sunny") || it.shortForecast.lowercase().contains("clear") }?.let { c[d] = it.shortForecast; s[d] = "HOURLY_MIDDAY_SUN_PRIORITY:${it.startTime}"; return@forEach }
                    c[d] = mid; s[d] = "HOURLY_MIDDAY:${best.startTime}"
                }
            } }
        }

        private fun applyForecastPeriods(f: List<NwsApi.ForecastPeriod>, today: String, w: MutableMap<String, Pair<Float?, Float?>>, c: MutableMap<String, String>, cS: MutableMap<String, String>, hS: MutableMap<String, String>, lS: MutableMap<String, String>, p: MutableMap<String, Int>): List<NwsApi.ForecastPeriod> {
            val tPs = mutableListOf<NwsApi.ForecastPeriod>()
            f.forEach { pr ->
                val d = extractNwsForecastDate(pr.startTime) ?: return@forEach; if (d == today) tPs.add(pr)
                val cur = w[d] ?: (null to null); val pop = pr.precipProbability; if (pop != null && !p.containsKey(d)) p[d] = pop
                if (pr.isDaytime) { w[d] = pr.temperature.toFloat() to cur.second; hS[d] = "FCST:${pr.name}@${pr.startTime}" }
                else { w[d] = cur.first to pr.temperature.toFloat(); lS[d] = "FCST:${pr.name}@${pr.startTime}" }
                if (c[d] == null) { c[d] = pr.shortForecast; cS[d] = "FCST:${pr.name}@${pr.startTime}" }
            }
            return tPs
        }

        private suspend fun logTodayDiagnostics(today: String, w: Map<String, Pair<Float?, Float?>>, hS: Map<String, String>, lS: Map<String, String>, c: MutableMap<String, String>, cS: MutableMap<String, String>, periods: List<NwsApi.ForecastPeriod>, lat: Double, lon: Double) {
            periods.firstOrNull { !it.isDaytime }?.let { c[today] = it.shortForecast; cS[today] = "FCST_ACTIVE:${it.name}@${it.startTime}" }
            val t = w[today] ?: return; appLogDao.log("NWS_TODAY_SOURCE", "high=${t.first} (${hS[today]}) low=${t.second} (${lS[today]}) cond=${c[today]} (${cS[today]})")
        }

        @androidx.annotation.VisibleForTesting
        internal suspend fun saveForecastSnapshot(weather: List<ForecastEntity>, lat: Double, lon: Double, source: String) {
            val forecasts = weather.filter { val d = runCatching { LocalDate.parse(it.targetDate) }.getOrNull(); d != null && !d.isBefore(LocalDate.now()) && !it.isClimateNormal }.mapNotNull { f ->
                if (f.highTemp == null && f.lowTemp == null) return@mapNotNull null
                val h = if (source == "OPEN_METEO") f.highTemp?.roundToInt()?.toFloat() else f.highTemp; val l = if (source == "OPEN_METEO") f.lowTemp?.roundToInt()?.toFloat() else f.lowTemp
                ForecastEntity(f.targetDate, LocalDate.now().toString(), lat, lon, "", h, l, f.condition, f.isClimateNormal, source, f.precipProbability, System.currentTimeMillis())
            }
            if (forecasts.isNotEmpty()) {
                val existing = forecastDao.getForecastsInRange(LocalDate.now().toString(), LocalDate.now().plusDays(14).toString(), lat, lon).filter { it.source == source }.associateBy { it.targetDate }
                val newForecasts = forecasts.filter { sn -> val ex = existing[sn.targetDate]; if (ex != null && ex.highTemp == sn.highTemp && ex.lowTemp == sn.lowTemp && ex.condition == sn.condition) { appLogDao.log("SNAPSHOT_SKIP", "date=${sn.targetDate} source=$source"); false } else { appLogDao.log("SNAPSHOT_SAVE", "date=${sn.targetDate} source=$source"); true } }
                if (newForecasts.isNotEmpty()) forecastDao.insertAll(newForecasts)
            }
        }

        private suspend fun fetchClimateNormalsGap(lat: Double, lon: Double, loc: String, last: LocalDate, targetDays: Int): List<ForecastEntity> {
            val target = LocalDate.now().plusDays(targetDays.toLong()); var cursor = last.plusDays(1); val normals = getHistoricalNormalsByMonthDay(lat, lon); val res = mutableListOf<ForecastEntity>()
            while (!cursor.isAfter(target)) { normals[MonthDay.from(cursor)]?.let { (h, l) ->                     res.add(ForecastEntity(cursor.toString(), cursor.toString(), lat, lon, "", h.toFloat(), l.toFloat(), "Historical Avg", true, source = WeatherSource.GENERIC_GAP.id)) }; cursor = cursor.plusDays(1) }
            return res
        }

        private suspend fun getHistoricalNormalsByMonthDay(lat: Double, lon: Double): Map<MonthDay, Pair<Int, Int>> {
            val key = "${(lat * 10).roundToInt() / 10.0}_${(lon * 10).roundToInt() / 10.0}"; val cached = climateNormalDao.getNormalsForLocation(key)
            if (cached.isNotEmpty()) return cached.associate { MonthDay.of(it.monthDay.take(2).toInt(), it.monthDay.takeLast(2).toInt()) to (it.highTemp to it.lowTemp) }
            val climate = openMeteoApi.getClimateForecast(lat, lon, "2020-01-01", "2020-12-31"); val normals = climate.associate { MonthDay.from(LocalDate.parse(it.date)) to (it.highTemp.roundToInt() to it.lowTemp.roundToInt()) }
            climateNormalDao.deleteOtherLocations(key);             climateNormalDao.insertAll(normals.map { (md, ts) -> ClimateNormalEntity("${md.monthValue.toString().padStart(2, '0')}-${md.dayOfMonth.toString().padStart(2, '0')}", key, ts.first, ts.second) })
            return normals
        }

        internal suspend fun fetchDayObservations(url: String, date: LocalDate): ObservationResult? { if (url.isEmpty()) return null; return fetchDayObservations(getSortedObservationStations(url), date) }

        internal suspend fun fetchDayObservations(ss: List<NwsApi.StationInfo>, date: LocalDate): ObservationResult? {
            for (info in ss.take(MAX_RETRIES)) {
                try {
                    val local = ZoneId.systemDefault(); val s = date.atStartOfDay(local).format(DateTimeFormatter.ISO_INSTANT); val e = date.plusDays(1).atStartOfDay(local).format(DateTimeFormatter.ISO_INSTANT)
                    val obs = nwsApi.getObservations(info.id, s, e); if (obs.isEmpty()) continue
                    val ts = obs.map { (it.temperatureCelsius * 1.8f) + 32f }; val h = ts.maxOrNull() ?: continue; val l = ts.minOrNull() ?: continue
                    val daylight = obs.filter { runCatching { ZonedDateTime.parse(it.timestamp).withZoneSameInstant(local).hour }.getOrDefault(12) in 7..19 }.ifEmpty { obs }
                    val precip = daylight.any { val d = it.textDescription.lowercase(); d.contains("rain") || d.contains("shower") || d.contains("storm") || d.contains("snow") }
                    val scores = daylight.map { val d = it.textDescription.lowercase(); when { d.contains("mostly cloudy") -> 75; d.contains("mostly clear") || d.contains("mostly sunny") -> 25; d.contains("partly") -> 50; d.contains("cloudy") || d.contains("overcast") -> 100; d.contains("fair") || d.contains("sunny") || d.contains("clear") -> 0; else -> 50 } }
                    val avg = if (scores.isNotEmpty()) scores.average() else 50.0
                    val base = if (precip) "Rain" else when { avg <= 15 -> "Sunny"; avg <= 35 -> "Mostly Sunny"; avg <= 65 -> "Partly Cloudy"; avg <= 85 -> "Mostly Cloudy"; else -> "Cloudy" }
                    val finalCond = if (avg == 0.0 || avg == 100.0) base else "$base (${avg.roundToInt()}%)"
                    return ObservationResult(h, l, info.id, finalCond)
                } catch (_: Exception) {}
            }
            return null
        }

        private suspend fun getSortedObservationStations(url: String): List<NwsApi.StationInfo> {
            val key = "observation_stations_v2_${url.hashCode()}"; val tKey = "observation_stations_time_v2_${url.hashCode()}"
            val cached = prefs.getString(key, null); val t = prefs.getLong(tKey, 0)
            if (cached != null && System.currentTimeMillis() - t < 86400000) return cached.split("|").map { val p = it.split("\t"); NwsApi.StationInfo(p[0], p[1], p[2].toDouble(), p[3].toDouble()) }
            val ss = runCatching { nwsApi.getObservationStations(url) }.getOrDefault(emptyList())
            if (ss.isNotEmpty()) prefs.edit().putString(key, ss.joinToString("|") { "${it.id}\t${it.name}\t${it.lat}\t${it.lon}" }).putLong(tKey, System.currentTimeMillis()).apply()
            return ss
        }

        private fun extractNwsForecastDate(s: String): String? = runCatching { ZonedDateTime.parse(s).toLocalDate().toString() }.getOrNull() ?: runCatching { LocalDate.parse(s.take(10)).toString() }.getOrNull()

        private suspend fun saveHourlyEntities(entities: List<HourlyForecastEntity>, label: String) {
            if (entities.isEmpty()) return
            val existing = hourlyForecastDao.getHourlyForecastsBySource(entities.minOf { it.dateTime }, entities.maxOf { it.dateTime }, entities.first().locationLat, entities.first().locationLon, entities.first().source).associateBy { it.dateTime }
            val changed = entities.filter { new -> existing[new.dateTime]?.let { it.temperature != new.temperature || it.condition != new.condition } ?: true }
            if (changed.isNotEmpty()) hourlyForecastDao.insertAll(changed)
        }

        private suspend fun saveHourlyForecasts(h: List<OpenMeteoApi.HourlyForecast>, lat: Double, lon: Double) = saveHourlyEntities(h.map { HourlyForecastEntity(it.dateTime, lat, lon, it.temperature, openMeteoApi.weatherCodeToCondition(it.weatherCode), "OPEN_METEO", it.precipProbability, System.currentTimeMillis()) }, "Meteo")
        private suspend fun saveWeatherApiHourlyForecasts(h: List<WeatherApi.HourlyForecast>, lat: Double, lon: Double) = saveHourlyEntities(h.map { HourlyForecastEntity(it.dateTime, lat, lon, it.temperature, it.condition, "WEATHER_API", it.precipProbability, System.currentTimeMillis()) }, "WAPI")
        private suspend fun saveNwsHourlyForecasts(h: List<NwsApi.HourlyForecastPeriod>, lat: Double, lon: Double) = saveHourlyEntities(h.mapNotNull { p -> val dt = runCatching { ZonedDateTime.parse(p.startTime).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")) }.getOrNull() ?: return@mapNotNull null; HourlyForecastEntity(dt, lat, lon, p.temperature.toFloat(), p.shortForecast, "NWS", p.precipProbability, System.currentTimeMillis()) }, "NWS")
        private suspend fun persistNwsPeriodSummary(url: String, f: List<NwsApi.ForecastPeriod>) { if (f.isEmpty()) return; val compact = f.take(NWS_PERIOD_SUMMARY_COUNT).mapIndexed { i, p -> "$i:${p.name}@${p.startTime}=${p.temperature}" }.joinToString("; "); appLogDao.log("NWS_PERIOD_SUMMARY", "url=$url first8=$compact") }
        private fun isPlugged(): Boolean { val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)); return (i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1) == android.os.BatteryManager.BATTERY_STATUS_CHARGING }
        suspend fun getCachedData(lat: Double, lon: Double) = forecastDao.getForecastsInRange(LocalDate.now().minusDays(7).toString(), LocalDate.now().plusDays(30).toString(), lat, lon)
        suspend fun getCachedDataBySource(lat: Double, lon: Double, s: WeatherSource) = (forecastDao.getForecastsInRangeBySource(LocalDate.now().minusDays(7).toString(), LocalDate.now().plusDays(30).toString(), lat, lon, WeatherSource.GENERIC_GAP.id) + forecastDao.getForecastsInRangeBySource(LocalDate.now().minusDays(7).toString(), LocalDate.now().plusDays(30).toString(), lat, lon, s.id)).associateBy { it.targetDate }.values.sortedBy { it.targetDate }
        suspend fun getForecastForDate(date: String, lat: Double, lon: Double) = forecastDao.getForecastForDate(date, lat, lon)
        suspend fun getForecastForDateBySource(date: String, lat: Double, lon: Double, source: WeatherSource): ForecastEntity? { val s = forecastDao.getForecastsInRangeBySource(date, date, lat, lon, source.id); return s.firstOrNull() }
        suspend fun getForecastsInRange(s: String, e: String, lat: Double, lon: Double) = forecastDao.getForecastsInRange(s, e, lat, lon)
        suspend fun getWeatherRange(s: String, e: String, lat: Double, lon: Double) = forecastDao.getForecastsInRange(s, e, lat, lon)
        suspend fun cleanOldData() { val monthAgo = System.currentTimeMillis() - 2592000000L; forecastDao.deleteOldForecasts(monthAgo); hourlyForecastDao.deleteOldForecasts(monthAgo); appLogDao.deleteOldLogs(System.currentTimeMillis() - 259200000L) }
    }

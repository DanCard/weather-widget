package com.weatherwidget.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The raw NWS forecast pieces both platforms need, before each platform's own daily mapping.
 *
 * @param rawHourlyPeriods hourly periods before the gridpoint merge (sky cover / QPF still null);
 *   kept because the desktop observation fallback and condition fallback read them directly.
 * @param hourlyPeriods [rawHourlyPeriods] with sky cover + grid QPF merged in.
 * @param gridpoints the empty bundle when [gridpointsFailure] is non-null.
 * @param gridpointsFailure non-null when the gridpoints request failed; the caller logs its own tag
 *   (Android `NWS_GRIDPOINTS_FAIL`, desktop `bestEffort`).
 * @param elapsedHourlyPeriods hours the raw grid still carries from *before* the first
 *   `/forecast/hourly` period — the current issuance's already-elapsed hours, assembled like
 *   [hourlyPeriods] (grid temperature, sky cover, PoP; condition from the sky-cover band). Empty
 *   when the grid leg failed. These never enter the live table; they exist so a site with no
 *   forecast history yet can be given one (`ElapsedForecastBackfill`).
 */
data class NwsForecastBundle(
    val rawHourlyPeriods: List<NwsApi.HourlyForecastPeriod>,
    val hourlyPeriods: List<NwsApi.HourlyForecastPeriod>,
    val forecastPeriods: List<NwsApi.ForecastPeriod>,
    val gridpoints: NwsApi.GridpointsBundle,
    val gridpointsFailure: Throwable?,
    val elapsedHourlyPeriods: List<NwsApi.HourlyForecastPeriod> = emptyList(),
)

/**
 * Platform-neutral NWS forecast fetch orchestration shared by Android's `NwsForecastMapper` and
 * desktop's `DesktopWeatherService.fetchNwsForecast` (Phase 3b of
 * plans/260909-nws-fetch-unification.md).
 *
 * Owns only the concurrent fetch + gridpoint merge both platforms duplicated. The daily mapping
 * (Android's accumulator pipeline vs desktop's `NwsDailyMapper.buildDailyForecasts`) and all
 * logging stay with the caller. [grid] is resolved by the caller so desktop can start its station
 * fetch before awaiting the forecast endpoints, preserving the existing overlap.
 */
object NwsForecastFetch {
    suspend fun fetch(
        api: NwsApi,
        grid: NwsApi.GridPointInfo,
    ): NwsForecastBundle = coroutineScope {
        val hourlyDeferred = async { api.getHourlyForecast(grid) }
        val dailyDeferred = async { api.getForecast(grid) }
        // Raw gridpoints supply per-date min/max extremes that backstop the day/night periods —
        // notably the final forecast day, whose overnight low is otherwise absent. Best-effort:
        // on failure the daily mapping falls back to whatever the periods provide. Cancellation
        // is rethrown so structured concurrency keeps working.
        val gridpointsDeferred = async {
            try {
                Result.success(api.getGridpointsBundle(grid))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        val rawHourlyPeriods = hourlyDeferred.await()
        val forecastPeriods = dailyDeferred.await()
        val gridpointsResult = gridpointsDeferred.await()
        val gridpointsFailure = gridpointsResult.exceptionOrNull()
        val gridpoints = gridpointsResult.getOrElse {
            NwsApi.GridpointsBundle(
                skyCoverByHour = emptyMap(),
                qpfIntervals = emptyList(),
                dailyTemperatures = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap()),
            )
        }
        // The hourly endpoint omits sky cover + grid QPF; merge them on via the shared helper so
        // the cloud-cover graph (and grid precip) match Android. Without this, every NWS hourly
        // row has cloudCover=null and the cloud graph collapses to a flat zero line.
        val hourlyPeriods = NwsHourlyGridMerge.applyGridpointData(
            rawHourlyPeriods,
            gridpoints.skyCoverByHour,
            gridpoints.qpfIntervals,
        )

        NwsForecastBundle(
            rawHourlyPeriods = rawHourlyPeriods,
            hourlyPeriods = hourlyPeriods,
            forecastPeriods = forecastPeriods,
            gridpoints = gridpoints,
            gridpointsFailure = gridpointsFailure,
            elapsedHourlyPeriods = elapsedPeriodsFromGrid(gridpoints, rawHourlyPeriods),
        )
    }

    /**
     * Grid hours strictly before the first live hourly period, as [NwsApi.HourlyForecastPeriod]s.
     * Bounded by the live start rather than "now" so the two lists never overlap whatever the
     * clock skew between issuance and fetch; the writer applies its own elapsed boundary on top.
     */
    internal fun elapsedPeriodsFromGrid(
        gridpoints: NwsApi.GridpointsBundle,
        rawHourlyPeriods: List<NwsApi.HourlyForecastPeriod>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<NwsApi.HourlyForecastPeriod> {
        val liveStartMs = rawHourlyPeriods.minOfOrNull { it.startTime } ?: return emptyList()
        return gridpoints.temperatureByHour
            .filterKeys { it < liveStartMs }
            .toSortedMap()
            .map { (hourMs, tempF) ->
                val local = Instant.ofEpochMilli(hourMs).atZone(zone)
                val cover = gridpoints.skyCoverByHour[local.format(HOUR_KEY_FORMAT)]
                NwsApi.HourlyForecastPeriod(
                    startTime = hourMs,
                    localDate = local.toLocalDate().toString(),
                    localHour = local.hour,
                    temperature = tempF,
                    shortForecast = NwsSkyCoverCondition.shortForecastFor(cover),
                    precipProbability = gridpoints.precipProbabilityByHour[hourMs],
                    cloudCover = cover,
                )
            }
    }

    private val HOUR_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")
}

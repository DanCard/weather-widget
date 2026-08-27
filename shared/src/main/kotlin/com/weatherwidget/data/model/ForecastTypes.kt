package com.weatherwidget.data.model

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.actuals.BlendContribution

data class HourlyForecast(
    val dateTime: Long,
    val temperature: Float,
    val condition: String,
    val precipProbability: Int? = null,
    val precipAmountMm: Float? = null,
    val cloudCover: Int? = null,
    val source: String? = null,
    val fetchedAt: Long = 0L,
    // Storage-key coordinates, carried so the shared selection logic can collapse same-site
    // fragments (float-keyed rows that GPS jitter splits into per-precision silos). Null for
    // consumers that don't read from the location-keyed tables.
    val locationLat: Double? = null,
    val locationLon: Double? = null,
    /**
     * Cloud cover in the low layer only (roughly below 3 km), where Open-Meteo reports it.
     *
     * Kept separate from [cloudCover] rather than replacing it because the two answer different
     * questions and both have consumers: [cloudCover] is the total column, which is what dims the
     * sun and what the daily condition icon is derived from, while this is what someone standing
     * outside would call the cloudiness, and what the cloud graph draws on every curve.
     */
    val cloudCoverLow: Int? = null,
    /** Cloud cover in the middle layer (roughly 3-8 km), where the provider reports it. */
    val cloudCoverMid: Int? = null,
    /** Cloud cover in the high layer (roughly above 8 km), where the provider reports it. */
    val cloudCoverHigh: Int? = null,
    /**
     * Transport-only provider envelope values used when elapsed hours are re-filed as observations.
     * They are deliberately not added to the large hourly forecast tables; the resulting observation
     * row is the durable record.
     */
    val cloudEnvelopeBaseMeters: Int? = null,
    val cloudEnvelopeTopMeters: Int? = null,
)

data class DailyForecast(
    val date: String,
    val highTemp: Float,
    val lowTemp: Float,
    val condition: String,
    val iconToken: String? = null,
    val precipProbability: Int? = null,
    val precipAmountMm: Float? = null,
    val isClimateNormal: Boolean = false,
    // Source this row was produced for, and NWS's native 12-hour daytime/nighttime period rain
    // chances — used as a fallback by the shared daily rain-label selection
    // (DailyRainLabels.resolveDailyLabelPrecip) when hourly rows are missing, keeping desktop and
    // Android identical.
    val source: String? = null,
    val daytimePrecipProbability: Int? = null,
    val nighttimePrecipProbability: Int? = null,
)

data class DailyForecastSnapshot(
    val date: String,
    val highTemp: Float?,
    val lowTemp: Float?,
    val condition: String,
    val iconToken: String? = null,
    val precipProbability: Int? = null,
    val precipAmountMm: Float? = null,
    val fetchedAt: Long,
    // Daytime/nighttime period chance from the original forecast — needed so the desktop daily view can
    // keep showing a past day's forecast rain chance (the snapshot is the only precip source for past
    // days, since the live `DailyForecast` list holds only today + future).
    val daytimePrecipProbability: Int? = null,
    val nighttimePrecipProbability: Int? = null,
)

data class DailyActual(
    val date: String,
    val computedHighTemp: Float,
    val computedLowTemp: Float,
    val condition: String,
    val apiHighTemp: Float? = null,
    val apiLowTemp: Float? = null,
)

/**
 * Describes how the flat vertical-cloud fields on an observation should be interpreted.
 *
 * [dbCode] is an explicit persistence contract shared by Android Room and desktop SQLite. Never
 * persist [ordinal]: enum ordering is an implementation detail and may change independently of the
 * database representation.
 */
enum class CloudVerticalKind(val dbCode: Int) {
    NONE(0),
    PROVIDER_BANDS(10),
    CUMULATIVE_LAYERS(20),
    TOTAL_ENVELOPE(30),
    OTHER(127),
    ;

    companion object {
        fun fromDbCode(dbCode: Int): CloudVerticalKind =
            entries.firstOrNull { it.dbCode == dbCode } ?: OTHER
    }
}

/**
 * A single weather observation, in the pure model layer so [ForecastSnapshot] doesn't depend on the
 * desktop persistence package. The persistence layer maps this to its own entity for storage.
 */
data class ObservationReading(
    val stationId: String,
    val stationName: String,
    val timestamp: Long, // epoch ms
    val temperature: Float, // Fahrenheit
    val condition: String,
    val locationLat: Double,
    val locationLon: Double,
    val distanceKm: Float = 0f,
    val stationType: String = "UNKNOWN",
    val maxTempLast24h: Float? = null, // Fahrenheit
    val minTempLast24h: Float? = null, // Fahrenheit
    val api: String,
    val fetchedAt: Long = System.currentTimeMillis(),
    val precipAmountMm: Float? = null,
    val isWebFallback: Boolean = false,
    // Reading failed the upstream QC check (see NwsApi.Observation.qcFailed). Stored and shown
    // in the stations UI for transparency, but excluded from blends and extrema.
    val qcFailed: Boolean = false,
    /**
     * True when this reading came from an actual METAR rather than the ASOS 5-minute feed.
     *
     * `/stations/{id}/observations` interleaves both, distinguishable only by a populated
     * `rawMessage`. The METAR's sky condition is a 30-minute rolling ceilometer assessment; the
     * 5-minute rows are instantaneous single-point samples that flip CLR<->SCT as the beam passes
     * in and out of scattered cloud. They answer different questions, and only the METAR answers
     * "how cloudy is it". See [com.weatherwidget.shared.actuals.MetarCloudBlender].
     *
     * False for every non-NWS row and for rows written before the column existed; those keep
     * resolving by nearest-to-the-hour exactly as before.
     */
    val isMetar: Boolean = false,
    /**
     * Total-column and low-layer cloud cover, 0-100.
     *
     * Nullable because most rows have neither: NWS station observations carry a layer list rather
     * than a percent, and only the Open-Meteo backfill populates these today. A missing value must
     * stay missing — a zero here would be an observation of a clear sky nobody made.
     */
    val cloudCover: Int? = null,
    val cloudCoverLow: Int? = null,
    /**
     * The original raw METAR report string (e.g. `KSJC 231653Z ... RMK ...`), preserved for
     * diagnostics, inspection, and re-parsing. Null when observation did not originate from a METAR.
     */
    val rawMetar: String? = null,
    /** Middle/high graph values when this observation supplies them; null is unknown, never clear. */
    val cloudCoverMid: Int? = null,
    val cloudCoverHigh: Int? = null,
    /** Representative reported base in each graph band, rounded to whole metres. */
    val cloudBaseLowMeters: Int? = null,
    val cloudBaseMidMeters: Int? = null,
    val cloudBaseHighMeters: Int? = null,
    /** Provider-wide vertical envelope, distinct from an independently measured cloud band. */
    val cloudEnvelopeBaseMeters: Int? = null,
    val cloudEnvelopeTopMeters: Int? = null,
    val cloudVerticalKind: CloudVerticalKind = CloudVerticalKind.NONE,
)

data class RawFetch(
    val hourly: List<HourlyForecast> = emptyList(),
    /** Optional provider history at its native sub-hour cadence, when provenance permits actuals. */
    val subHourly: List<HourlyForecast> = emptyList(),
    val daily: List<DailyForecast> = emptyList(),
    val rawObservations: List<ObservationReading> = emptyList(),
    val dailyActuals: Map<String, DailyHistory> = emptyMap(),
    val dailySnapshots: Map<String, List<DailyForecastSnapshot>> = emptyMap(),
    val nwsDailyExtremes: NwsApi.DailyTemperatureExtremes? = null,
    val providerCurrentTemp: Float? = null,
    val providerCurrentCondition: String? = null,
    val providerCurrentObservedAt: Long? = null,
    val providerCurrentCloudCover: Int? = null,
    val providerCurrentCloudCoverLow: Int? = null,
    val providerCurrentCloudCoverMid: Int? = null,
    val providerCurrentCloudCoverHigh: Int? = null,
)

/**
 * What to display now, produced by the resolver. The five fields this holds are the only display
 * state in the pipeline; they must come from a single resolution owner, never from a raw fetch.
 */
data class ResolvedView(
    val currentTemp: Float? = null,
    val currentCondition: String? = null,
    val currentObservedAt: Long? = null,
    val appliedDelta: Float? = null,
    val deltaFromYesterday: Float? = null,
)

/** Full snapshot: raw data + resolved display values, for consumers that need both. */
data class ForecastSnapshot(
    val raw: RawFetch,
    val resolved: ResolvedView,
    /**
     * Day-ago cloud-cover predictions by top-of-hour epoch ms, backing the cloud graph's frozen
     * forecast curve (see `PriorDayCloudForecast`). Empty when the prior-run fetch has not run or
     * failed — the curve then falls back to the live value and marks itself unfrozen, so an empty
     * map degrades the display rather than breaking it.
     */
    val priorDayCloudForecast: Map<Long, Int> = emptyMap(),
    /** Source-isolated low-cloud history by its native timestamps (hourly or sub-hourly). */
    val retroCloudActual: Map<Long, Int> = emptyMap(),
    /**
     * Day-ago mid/high band predictions, from our own hourly snapshots rather than the Previous
     * Runs API, which serves no band data (see `PriorDayBandForecast`). Empty for every source but
     * Open-Meteo, and for hours the app was not running a day beforehand.
     */
    val priorDayBandForecast: Map<Long, com.weatherwidget.shared.graph.CloudBands> = emptyMap(),
    /** Observed low/mid/high layers by native timestamp; provider bands or blended station layers. */
    val retroCloudBands: Map<Long, com.weatherwidget.shared.graph.CloudBands> = emptyMap(),
    /** Dominant station contribution for borrowed cloud actuals. */
    val dominantCloudContribution: BlendContribution? = null,
)

sealed class DataStatus {
    data object Loading : DataStatus()
    data class Live(val updatedAt: Long) : DataStatus()
    data class Stale(val updatedAt: Long, val reason: StaleReason) : DataStatus()
    data object NoData : DataStatus()
    data class Error(val message: String) : DataStatus()
}

enum class StaleReason { OFFLINE, SOURCE_ERROR }

// Name-based half of [isOfflineException], usable where only the class name survives (e.g. the
// CURRENT_TEMP_STATUS app_logs rows the desktop UI reads back across the process boundary).
fun isOfflineExceptionName(name: String): Boolean =
    name.contains("ConnectException") ||
        name.contains("UnknownHostException") ||
        // Ktor CIO surfaces DNS failure as java.nio.channels.UnresolvedAddressException, whose
        // message is null — the message fallbacks in isOfflineException can never catch it.
        name.contains("UnresolvedAddressException") ||
        name.contains("SocketTimeoutException") ||
        name.contains("NoRouteToHostException") ||
        name.contains("NetworkUnreachableException")

fun isOfflineException(e: Throwable): Boolean {
    if (isOfflineExceptionName(e::class.qualifiedName ?: "")) return true
    val msg = e.message?.lowercase() ?: return false
    return msg.contains("connection refused") ||
        msg.contains("connection timed out") ||
        msg.contains("no route to host") ||
        msg.contains("network is unreachable") ||
        msg.contains("failed to connect") ||
        msg.contains("resolve")
}

fun deriveDataStatus(
    cachePresent: Boolean,
    lastFetchMs: Long?,
    refreshFailed: Boolean,
    failureIsOffline: Boolean,
    now: Long = System.currentTimeMillis(),
): DataStatus {
    if (!cachePresent && !refreshFailed) return DataStatus.Loading
    if (!cachePresent && refreshFailed) return DataStatus.NoData
    val updatedAt = lastFetchMs ?: now
    return if (refreshFailed) {
        val reason = if (failureIsOffline) StaleReason.OFFLINE else StaleReason.SOURCE_ERROR
        DataStatus.Stale(updatedAt, reason)
    } else {
        DataStatus.Live(updatedAt)
    }
}

package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.MetarFetchPolicy
import com.weatherwidget.shared.util.MetarFetchWindow
import kotlinx.coroutines.CancellationException

/**
 * Shared station-network observation refresh rule (METAR) for both platforms
 * (plans/261123-targeted-sync-tail-parity.md).
 *
 * Owns tier gating ([MetarFetchPolicy.tierFor] over the host's background sources), window sizing
 * ([MetarFetchWindow.hours] or an explicit deep window), the shared write ([ObservationWriteRun]),
 * and the `METAR_OBS_STORED` / `METAR_OBS_FAIL` log lines. Each platform supplies only I/O through
 * [Host].
 */
class StationNetworkRefreshRun(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Host {
        val lat: Double
        val lon: Double

        /** Sources eligible for background fetching at this site (after `SourceFetchGate`). */
        suspend fun backgroundSources(): List<WeatherSource>

        /** Source IDs currently displayed on screen / widgets. */
        suspend fun displayedSourceIds(): Set<String>

        /** Per-source actuals provider override, or null for the default. */
        fun actualsPreference(source: WeatherSource): WeatherSource? =
            ActualsProviderResolver.preferenceSource().invoke(source)

        /** Timestamp (epoch ms) of the newest stored row for [api] at this site, or null. */
        suspend fun newestStoredTimestampMs(api: String): Long?

        /** Fetch METAR observations covering the last [hours] hours at ([lat], [lon]). */
        suspend fun fetchMetar(hours: Int): List<ObservationReading>

        /** Shared observation write host for persisting fetched readings. */
        fun writeHost(): ObservationWriteRun.Host

        suspend fun log(tag: String, message: String, level: String)
    }

    data class Result(
        val tier: MetarFetchPolicy.Tier,
        val fetched: Int,
        val hours: Int? = null,
    )

    /**
     * Fetches and stores METAR observations when the derived tier is in [acceptTiers]. Never throws
     * (except [CancellationException]): a failure in the supplementary feed is logged and swallowed.
     *
     * @param hours explicit lookback in hours (e.g. [MetarFetchWindow.DEEP_HOURS] for full syncs and
     *   targeted-sync tails), or null to size from the gap since the newest stored row.
     */
    suspend fun refreshIfDue(
        host: Host,
        acceptTiers: Set<MetarFetchPolicy.Tier>,
        reason: String,
        hours: Int? = null,
    ): Result {
        val tier = MetarFetchPolicy.tierFor(
            visibleSources = host.backgroundSources(),
            activeDisplaySourceIds = host.displayedSourceIds(),
            actualsPreference = host::actualsPreference,
        )
        if (tier !in acceptTiers) return Result(tier = tier, fetched = 0)
        return try {
            val nowMs = clock()
            val resolvedHours = hours ?: MetarFetchWindow.hours(
                host.newestStoredTimestampMs(WeatherSource.METAR.id),
                nowMs,
            )
            val rows = host.fetchMetar(resolvedHours)
            if (rows.isEmpty()) return Result(tier = tier, fetched = 0, hours = resolvedHours)
            ObservationWriteRun(host.writeHost()).write(ObservationWriteRun.Write(rows), nowMs)
            host.log(
                TAG_STORED,
                "reason=$reason tier=${tier.name} hours=$resolvedHours rows=${rows.size} " +
                    "stations=${rows.map { it.stationId }.distinct().joinToString("|")}",
                "INFO",
            )
            Result(tier = tier, fetched = rows.size, hours = resolvedHours)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log(
                TAG_FAIL,
                "reason=$reason tier=${tier.name}: ${e.javaClass.simpleName}: ${e.message}",
                "ERROR",
            )
            Result(tier = tier, fetched = 0)
        }
    }

    companion object {
        const val TAG_STORED = "METAR_OBS_STORED"
        const val TAG_FAIL = "METAR_OBS_FAIL"
    }
}

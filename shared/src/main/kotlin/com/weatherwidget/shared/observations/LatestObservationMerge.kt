package com.weatherwidget.shared.observations

/**
 * Prefer-newest resolution for the latest-observation path (plan 260721). Given the API's latest
 * reading and the parallel web-transport readings for a single station, chooses the reading that should
 * anchor the station's current temperature: the **newest non-QC-flagged** reading across both
 * sources, with ties going to the API (official) value. It also surfaces the two newest timestamps
 * so the caller can log the web-vs-API freshness metric.
 *
 * Generic over the reading type `T` so Android and desktop share the exact same rule despite
 * parsing timestamps differently. Pure and dependency-free — unit-tested in `:shared`.
 *
 * Why ties go to the API: an identical observation time means the same physical METAR; the web
 * source's real contribution is the *fresher* timestamps the API does not have yet. So
 * "prefer newest" reduces to "take web only when it is strictly newer", and the fresh-web win
 * happens precisely because the API lacks that timestamp.
 *
 * A QC-flagged web reading can be the newest yet must never be chosen ([KPAO 2026-07-13]); the
 * flag filter is applied to the web side before selecting the newest.
 */
object LatestObservationMerge {

    data class Result<T>(
        /** The reading to display/store as the station's latest, or null when neither side had one. */
        val chosen: T?,
        /** True when [chosen] came from the web source (used strictly newer than the API). */
        val chosenIsWeb: Boolean,
        /** Newest usable API timestamp (millis), for the freshness metric; null when absent. */
        val apiNewestMs: Long?,
        /** Newest usable (non-flagged) web timestamp (millis), for the freshness metric; null when absent. */
        val webNewestMs: Long?,
    )

    fun <T> preferNewest(
        apiLatest: T?,
        apiNewestMs: Long?,
        webReadings: List<T>,
        isQcFailed: (T) -> Boolean,
        observedAtMillis: (T) -> Long?,
    ): Result<T> {
        val webUsable = webReadings
            .asSequence()
            .filterNot(isQcFailed)
            .mapNotNull { reading -> observedAtMillis(reading)?.let { reading to it } }
            .maxByOrNull { it.second }
        val webNewestMs = webUsable?.second
        val useWeb = webNewestMs != null && (apiNewestMs == null || webNewestMs > apiNewestMs)
        return Result(
            chosen = if (useWeb) webUsable.first else apiLatest,
            chosenIsWeb = useWeb,
            apiNewestMs = apiNewestMs,
            webNewestMs = webNewestMs,
        )
    }
}

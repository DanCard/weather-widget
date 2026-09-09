package com.weatherwidget.shared.observations

/**
 * Single implementation of the NWS latest-observation merge + cloud-carrier rule, shared by
 * Android's `NwsObservationSource.fetchLatest` and desktop's `fetchObservationBundles`
 * (Phase 3d of plans/260909-nws-fetch-unification.md).
 *
 * Generic over the reading type so Android's `ObservationEntity` and the shared
 * `ObservationReading` both work without a conversion at the decision boundary.
 */
object NwsObservationPlanner {
    data class LatestMerge<T>(
        /** The row to store as the station's "current" observation (API unless the web row won). */
        val chosen: T?,
        /** True when [chosen] is the web reading; callers stamp `isWebFallback` with it. */
        val chosenIsWeb: Boolean,
        /**
         * The API row to store *alongside* a web-won latest. The web swap is a TEMPERATURE
         * decision, but it drops the API row's sky condition (and 24 h extremes / precipitation),
         * so when the API row carries low cloud it is kept as a second observation.
         */
        val cloudCarrier: T?,
        /** Newest usable API timestamp (millis) for the freshness metric; null when absent. */
        val apiNewestMs: Long?,
        /** Newest usable (non-flagged) web timestamp (millis); null when absent. */
        val webNewestMs: Long?,
    )

    /**
     * @param useWebForLatest the station's tier decision (`ObservationFallbackPolicy.shouldFetchWeb`);
     *   when false the web readings are metrics-only and never become [LatestMerge.chosen].
     * @param hasLowCloud whether a reading carries low-layer cloud cover. Both platforms derive
     *   this from `MetarSkyCover.lowPercent(...)`, so `cloudCoverLow != null` is the same test.
     */
    fun <T> mergeLatest(
        apiLatest: T?,
        apiNewestMs: Long?,
        webReadings: List<T>,
        useWebForLatest: Boolean,
        isQcFailed: (T) -> Boolean,
        observedAtMillis: (T) -> Long?,
        hasLowCloud: (T) -> Boolean,
    ): LatestMerge<T> {
        val merge = LatestObservationMerge.preferNewest(
            apiLatest = apiLatest,
            apiNewestMs = apiNewestMs,
            webReadings = webReadings,
            isQcFailed = isQcFailed,
            observedAtMillis = observedAtMillis,
        )
        val useWeb = useWebForLatest && merge.chosenIsWeb
        val chosen = if (useWeb) merge.chosen else apiLatest
        val cloudCarrier = if (useWeb && apiLatest != null && hasLowCloud(apiLatest)) apiLatest else null
        return LatestMerge(
            chosen = chosen,
            chosenIsWeb = useWeb,
            cloudCarrier = cloudCarrier,
            apiNewestMs = merge.apiNewestMs,
            webNewestMs = merge.webNewestMs,
        )
    }
}

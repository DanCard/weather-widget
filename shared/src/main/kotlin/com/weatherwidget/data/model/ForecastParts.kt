package com.weatherwidget.data.model

/**
 * Which parts of a source's forecast one fetch asks for and stores: the daily rows, the hourly rows,
 * or both. Every scheduled or forced forecast fetch takes both
 * ([com.weatherwidget.data.remote.ForecastHorizonCadence]: daily and hourly share one cadence, user
 * 2026-10-16) — less only when a quota refuses one product ([unblocked]).
 *
 * A source whose API can leave one part out of the request ([splits]) does so. The others always
 * make their one full request: [HOURLY] then stores the hourly rows only.
 */
enum class ForecastParts(val daily: Boolean, val hourly: Boolean) {
    BOTH(daily = true, hourly = true),
    DAILY(daily = true, hourly = false),
    HOURLY(daily = false, hourly = true),
    ;

    companion object {
        /**
         * Sources whose request can drop one part: Google (`forecast/hours` vs `forecast/days`), NWS
         * (`/forecast/hourly` vs `/forecast`), Silurian (`/forecast/hourly` vs `/forecast/daily`),
         * Open-Meteo (`hourly=` vs `daily=`), Tomorrow.io (`timesteps=1h` vs `1d`). WeatherAPI's past
         * hours feed its actuals and OpenWeatherMap's daily is built from its 3-hourly list, so neither
         * splits.
         */
        private val SPLITTING = setOf(
            WeatherSource.GOOGLE_WEATHER,
            WeatherSource.NWS,
            WeatherSource.SILURIAN,
            WeatherSource.OPEN_METEO,
            WeatherSource.TOMORROW_IO,
        )

        fun splits(source: WeatherSource): Boolean = source in SPLITTING

        /** The parts due, or null when neither is. */
        fun of(daily: Boolean, hourly: Boolean): ForecastParts? = when {
            daily && hourly -> BOTH
            daily -> DAILY
            hourly -> HOURLY
            else -> null
        }

        /**
         * What a fetch of [source] actually requests for [parts]: a non-splitting source asks for
         * everything whatever is due.
         */
        fun requested(source: WeatherSource, parts: ForecastParts): ForecastParts =
            if (splits(source)) parts else BOTH

        /**
         * The parts a fetch takes: both, less any product refused until a known time ([blocked],
         * [com.weatherwidget.data.remote.SourceQuotaBlocks.blockedProducts]); null when both are. A
         * blocked `forecast/hours` once stayed due and made every 1-min desktop wake a billed request
         * (performance/261012-forecast-loop-quota-blocked-part-is-not-due.md).
         */
        fun unblocked(blocked: Set<ForecastProduct>): ForecastParts? =
            of(ForecastProduct.DAILY !in blocked, ForecastProduct.HOURLY !in blocked)

        /**
         * `FETCH_PLAN` app_logs message, both platforms: `parts=NWS:BOTH,OPEN_METEO:BOTH
         * horizons=NWS:SHORT(long=8h/3h,short=4h/4h),…` — what is fetched, and each source's horizon
         * decision ([com.weatherwidget.data.remote.ForecastHorizonCadence.logFragment]).
         */
        /** Tag of [planLogLine], both platforms. */
        const val PLAN_TAG = "FETCH_PLAN"

        fun planLogLine(plan: Map<String, ForecastParts>, horizonFragments: List<String>): String =
            "parts=${plan.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }} " +
                "horizons=${horizonFragments.joinToString(",").ifEmpty { "unjudged" }}"
    }
}

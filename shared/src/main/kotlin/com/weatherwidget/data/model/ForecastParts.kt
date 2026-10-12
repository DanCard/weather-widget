package com.weatherwidget.data.model

/**
 * Which parts of a source's forecast one fetch asks for and stores: the daily rows, the hourly rows,
 * or both. Hourly runs on its own cadence ([com.weatherwidget.data.remote.HourlyCadence]); daily,
 * current and actuals keep the forecast cadence (user, 2026-10-11 —
 * performance/261011-hourly-cadence-by-last-view-and-fuzzy-full-window.md).
 *
 * A source whose API can leave one part out of the request ([splits]) does so. The others always
 * make their one full request: [DAILY] then stores everything, exactly as before; [HOURLY] stores the
 * hourly rows only, so their daily rows stay on the daily cadence.
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
         * A forced fetch (Refresh, a source switch, a location change, screen on): both parts, except
         * an [hourlyLimited] one (screen on / wake, a daily-view toggle), which takes hourly only when
         * [hourlyDue]. Null [hourlyDue] (not judged) takes it.
         */
        fun forForced(hourlyLimited: Boolean, hourlyDue: Boolean?): ForecastParts =
            if (hourlyLimited && hourlyDue == false) DAILY else BOTH

        /**
         * A scheduled fetch: daily when [dailyDue] (the forecast cadence), hourly when [hourlyDue]
         * ([com.weatherwidget.data.remote.HourlyCadence]), null when neither. Null [hourlyDue] (not
         * judged: no context or site) lets the daily fetch carry the hours, as before the split. Hourly
         * due in a FULL window takes daily too ([withFullWindow]).
         *
         * A product in [blocked] (refused until a known time,
         * [com.weatherwidget.data.remote.SourceQuotaBlocks.blockedProducts]) is not due, and never
         * pulls the other part along. Required, with no default, so no platform can plan around it: a
         * blocked `forecast/hours` once stayed due and, in a FULL window, made every 1-min wake a
         * billed daily + current request (performance/261012-forecast-loop-quota-blocked-part-is-not-due.md).
         */
        fun forRoutine(
            dailyDue: Boolean,
            hourlyDue: Boolean?,
            isFull: Boolean,
            blocked: Set<ForecastProduct>,
        ): ForecastParts? {
            val dailyBlocked = ForecastProduct.DAILY in blocked
            val hourlyBlocked = ForecastProduct.HOURLY in blocked
            val daily = dailyDue && !dailyBlocked
            if (hourlyDue == null) return if (!daily) null else if (hourlyBlocked) DAILY else BOTH
            val parts = of(daily, hourlyDue && !hourlyBlocked)
            return if (dailyBlocked) parts else withFullWindow(parts, isFull)
        }

        /**
         * `FETCH_PLAN` app_logs message, both platforms: `parts=NWS:BOTH,OPEN_METEO:HOURLY
         * hourly=NWS:4h(view=…,lastHourly=…,due=…),…` — what is fetched, and each source's hourly cadence
         * ([com.weatherwidget.data.remote.HourlyCadence.logFragment]).
         */
        fun planLogLine(plan: Map<String, ForecastParts>, hourlyFragments: List<String>): String =
            "parts=${plan.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }} " +
                "hourly=${hourlyFragments.joinToString(",").ifEmpty { "unjudged" }}"

        /**
         * Hourly due inside a FULL window ([com.weatherwidget.data.remote.HourlyWindowPolicy]) takes
         * the daily rows too: the far days' noon cloud and rain maxima, the reason for FULL, live on
         * the daily rows ([com.weatherwidget.shared.util.DailyHourlySummaries]), and an hourly-only
         * fetch writes none.
         */
        fun withFullWindow(parts: ForecastParts?, isFull: Boolean): ForecastParts? =
            if (parts == HOURLY && isFull) BOTH else parts
    }
}

package com.weatherwidget.shared.observations

/**
 * The guard tests' rule for [CurrentConditionsFetcher]: platform code must not call a model feed's
 * current-conditions endpoint itself, so the per-feed mapping cannot fork again
 * (plans/261013-shared-current-conditions-fetch.md). Android and desktop each scan their sources
 * with it.
 */
object CurrentConditionsCallSites {
    /** Open-Meteo's current endpoint and Tomorrow.io's five-minute history. */
    val directCall = Regex("""\.getCurrent\(|\.getFiveMinuteHistory\(""")

    /**
     * Files allowed a direct call, and how many: each platform's FULL forecast refresh pulls
     * Tomorrow.io's deeper history window into the forecast response, which is forecast fetching, not
     * current conditions. A count, not a bare file name, so a new current-conditions call in the same
     * file still fails.
     */
    val allowed: Map<String, Int> = mapOf(
        "ForecastFetchCoordinator.kt" to 1,
        "DesktopWeatherService.kt" to 1,
    )

    /** Offending `file: count` entries for [sources] (file name → text). */
    fun offenders(sources: Map<String, String>): List<String> =
        sources.mapNotNull { (name, text) ->
            val count = text.lines().count { line -> directCall.containsMatchIn(line.substringBefore("//")) }
            if (count > (allowed[name] ?: 0)) "$name: $count direct call(s), allowed ${allowed[name] ?: 0}" else null
        }
}

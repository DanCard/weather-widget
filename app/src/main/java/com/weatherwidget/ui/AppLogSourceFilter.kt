package com.weatherwidget.ui

import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.SourceFetchLogTags

/**
 * The provider chips on the App Logs screen. A row belongs to a provider when its tag or message names
 * it by id (`OPEN_METEO`), failure tag (`FETCH_METEO_FAIL`) or short name (`source=Meteo`). The free-text
 * filter alone missed rows like `SOURCE_RETRY fire source=OPEN_METEO` when searching "Meteo"/"METEO"
 * plus the fetch-failure tag (user, 2026-11-15, diagnosing a stale Open-Meteo).
 *
 * Tokens match whole: not inside a longer word, but `_` counts as a separator so
 * `missing_actuals_OPEN_METEO_today` still matches. Rows that list every source (`FETCH_PLAN`,
 * `SOURCE_FETCH_GATE`) match every chip, by design.
 */
object AppLogSourceFilter {
    /** The forecast providers: the sources with their own fetch, in enum order. */
    val sources: List<WeatherSource> = WeatherSource.entries.filter { SourceFetchLogTags.failureTag(it) != null }

    /** What names [source] in a row: id, failure tag, short name. Always three, for the DAO's pre-filter. */
    fun tokens(source: WeatherSource): List<String> =
        listOf(source.id, SourceFetchLogTags.failureTag(source) ?: source.id, source.shortDisplayName)

    private val patterns: Map<WeatherSource, Regex> = sources.associateWith { source ->
        val tokens = tokens(source).distinct()
        Regex(
            tokens.joinToString("|", prefix = "(?<![A-Za-z0-9])(?:", postfix = ")(?![A-Za-z0-9])") { Regex.escape(it) },
            RegexOption.IGNORE_CASE,
        )
    }

    /** True when [source] is null (All) or [log] names it. */
    fun matches(log: AppLogEntity, source: WeatherSource?): Boolean {
        if (source == null) return true
        val pattern = patterns[source] ?: return true
        return pattern.containsMatchIn(log.tag) || pattern.containsMatchIn(log.message)
    }
}

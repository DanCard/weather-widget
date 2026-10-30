package com.weatherwidget.shared.graph

/**
 * The guard tests' rule for the forecast delta: platform code must not subtract a forecast from an
 * actual itself, so a view cannot print a different delta from its neighbors
 * (plans/261030-one-forecast-delta-all-views.md). Android and desktop each scan their sources with it.
 */
object ForecastDeltaCallSites {
    /** `actualTemp - forecastTemp`, `point.actualTemp!! - hour.temperature`, and the reverse order. */
    val directCall = Regex(
        """actual(Temp|Temperature)\b[!?.\w]*\s+-\s+[\w.]*(forecastTemp|[tT]emperature)\b""" +
            """|forecastTemp\b[!?.\w]*\s+-\s+[\w.]*actual(Temp|Temperature)\b""",
    )

    /** Offending `file: line` entries for [sources] (file name → text). */
    fun offenders(sources: Map<String, String>): List<String> =
        sources.flatMap { (name, text) ->
            text.lines().filter { line -> directCall.containsMatchIn(line.substringBefore("//")) }
                .map { line -> "$name: ${line.trim()}" }
        }
}

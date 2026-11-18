package com.weatherwidget.widget

/**
 * The startup paint's second pass: which widgets the live daily actuals change.
 *
 * The first paint uses stored rows ([com.weatherwidget.data.repository.DailyActualsStore.getDailyActualsStored])
 * so it does not wait for the blend; the live read follows. A widget is repainted only when its
 * display source's actuals differ — on the 2026-11-18 Pixel cold start they were equal to the
 * last digit, so most starts push nothing twice.
 * performance/261118-cold-start-daily-actuals-cost.md
 */
internal object StartupActualsRefresh {

    /** Sources whose actuals differ between the passes, ignoring when each row was written. */
    fun changedSources(provisional: DailyActualsBySource, live: DailyActualsBySource): Set<String> =
        (provisional.keys + live.keys).filterTo(HashSet()) { source ->
            comparable(provisional[source]) != comparable(live[source])
        }

    /**
     * Widgets the live pass repaints, in [widgetSources] order: their source's actuals [changed], or
     * the provisional map had no today for it. The provisional paint makes no missing-actuals
     * request (WeatherData.actualsProvisional), so a today missing from both maps still needs the
     * live render to ask for it.
     */
    fun widgetsToRepaint(
        widgetSources: Map<Int, String>,
        changed: Set<String>,
        provisional: DailyActualsBySource,
        today: java.time.LocalDate,
    ): IntArray =
        widgetSources.filterValues { it in changed || provisional[it]?.get(today) == null }.keys.toIntArray()

    /**
     * What differs for [source], for the log line: each differing date with the names of its
     * differing fields (`2026-11-18:observedFogLevel,precipAmountMm`), or `added`/`removed`.
     */
    fun describeChange(provisional: DailyActualsBySource, live: DailyActualsBySource, source: String): String {
        val before = comparable(provisional[source])
        val after = comparable(live[source])
        return (before.keys + after.keys).sorted().mapNotNull { date ->
            val a = before[date]
            val b = after[date]
            when {
                a == b -> null
                a == null -> "$date:added"
                b == null -> "$date:removed"
                else -> "$date:" + FIELDS.filter { (_, get) -> get(a) != get(b) }.joinToString(",") { it.first }
            }
        }.joinToString(" ")
    }

    private val FIELDS: List<Pair<String, (com.weatherwidget.data.model.DailyHistory) -> Any?>> = listOf(
        "computedHighTemp" to { it.computedHighTemp },
        "computedLowTemp" to { it.computedLowTemp },
        "computedHighAt" to { it.computedHighAt },
        "computedLowAt" to { it.computedLowAt },
        "condition" to { it.condition },
        "precipAmountMm" to { it.precipAmountMm },
        "precipDayMm" to { it.precipDayMm },
        "precipNightMm" to { it.precipNightMm },
        "observedFogLevel" to { it.observedFogLevel },
        "observedFogTopTemp" to { it.observedFogTopTemp },
        "observedFogVisibilityMi" to { it.observedFogVisibilityMi },
        "observedFogPredawn" to { it.observedFogPredawn },
        "location" to { it.locationLat to it.locationLon },
        "forecastColumns" to { listOf(it.forecastHighTemp, it.forecastLowTemp, it.forecastDayPrecipChance, it.forecastNightPrecipChance, it.priorForecastHighTemp, it.priorForecastLowTemp, it.noonCloudPercent) },
        "provenance" to { listOf(it.actualsSource, it.lastWriter, it.apiHighTemp, it.apiLowTemp, it.actualsBorrowedFromKm, it.borrowedWithoutLocalRow) },
    )

    // updatedAt is the write time: a live blend stamps "now", a stored row its recompute's time.
    private fun comparable(byDate: DailyActualMap?) = byDate.orEmpty().mapValues { it.value.copy(updatedAt = 0L) }
}

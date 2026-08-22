package com.weatherwidget.shared.actuals

/**
 * Pure planner for forecast-only `daily_history` rows (see [DailyHistoryWriter.FORECAST_ONLY_ROW]).
 *
 * Past days for a source whose actuals product does not exist (Open-Meteo, Silurian) — or which a
 * real-actuals source never resolved (Tomorrow.io before its tracking started) — previously had
 * NO daily_history row, leaving past-day widget/desktop columns without high/low labels and
 * entirely dependent on the forecasts table's rolling retention. These rows freeze the day's
 * final forecast into the row's existing `forecastHighTemp`/`forecastLowTemp` overlay columns so
 * daily history renders from the row alone.
 *
 * `computedHighTemp`/`computedLowTemp` stay NULL: the null is the "no actuals" marker that keeps
 * the row out of accuracy baselines and scoring, and a later real-actuals write simply fills
 * them in.
 *
 * Platform writers feed this their DAO-shaped inputs and map [PlannedRow]s back to entities;
 * the rules live here so Android and desktop agree exactly.
 */
object ForecastOnlyHistoryPlanner {

    /** One forecast batch candidate for (date, source) at the user's site. */
    data class Candidate(
        /** UTC midnight epoch millis of the forecast's target day. */
        val dateMs: Long,
        val source: String,
        val locationLat: Double,
        val locationLon: Double,
        val highTemp: Float?,
        val lowTemp: Float?,
        val precipAmountMm: Float?,
        val condition: String,
        val fetchedAt: Long,
        val isClimateNormal: Boolean,
    )

    /** A row this planner decided must be created. */
    data class PlannedRow(
        val dateMs: Long,
        val source: String,
        val locationLat: Double,
        val locationLon: Double,
        val forecastHighTemp: Float,
        val forecastLowTemp: Float,
        val forecastPrecipAmountMm: Float?,
        val condition: String,
    )

    /**
     * Returns the rows to insert for past (date, source) pairs that have a usable forecast batch
     * but no daily_history row at any nearby location fragment.
     *
     * Selection mirrors the past-day overlay: the most recently fetched complete batch (non-null
     * high AND low, non-climate-normal). Only days strictly before [todayMs] qualify — today is
     * still live and belongs to the observation/blend writers. Idempotent by construction: every
     * (date, source) already present in [existing] is skipped.
     *
     * @param genericGapSourceId the id of the climate-normal filler source; its rows never seed
     *        history. Passed in (not hard-coded) to keep this object free of the model enum.
     */
    fun plan(
        candidates: List<Candidate>,
        existing: Set<Pair<Long, String>>,
        todayMs: Long,
        genericGapSourceId: String,
    ): List<PlannedRow> {
        return candidates
            .asSequence()
            .filter { it.dateMs < todayMs }
            .filter { it.source != genericGapSourceId && !it.isClimateNormal }
            .filter { it.highTemp != null && it.lowTemp != null }
            .filter { (it.dateMs to it.source) !in existing }
            .groupBy { it.dateMs to it.source }
            .mapValues { (_, rows) -> rows.maxBy { it.fetchedAt } }
            .values
            .map { winner ->
                PlannedRow(
                    dateMs = winner.dateMs,
                    source = winner.source,
                    locationLat = winner.locationLat,
                    locationLon = winner.locationLon,
                    forecastHighTemp = winner.highTemp!!,
                    forecastLowTemp = winner.lowTemp!!,
                    forecastPrecipAmountMm = winner.precipAmountMm,
                    condition = winner.condition,
                )
            }
            .sortedWith(compareBy({ it.dateMs }, { it.source }))
    }
}

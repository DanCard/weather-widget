package com.weatherwidget.shared.stats

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.TempUtils
import java.time.LocalDate

/**
 * Shared per-day forecast-accuracy breakdown loop.
 *
 * The Android `AccuracyCalculator` and the desktop `DesktopAccuracyCalculator` used to carry
 * near-identical copies of this date loop (same epoch math, same forecast pick, same baseline
 * resolution, same per-day scoring). It now lives once, in `:shared`, with platform DAOs supplying
 * the raw rows. Baseline selection, temperature resolution and rounding still delegate to the
 * existing shared primitives ([ActualsBaselineResolver], [resolveBaselineTemps], [AccuracyPure]).
 */
object AccuracyBreakdown {

    const val MS_IN_A_DAY = 86_400_000L

    /** Slim forecast projection: just the columns the loop needs to match a forecast to its actual. */
    data class ForecastRow(
        val targetDate: Long,
        val dateOfPrediction: Long,
        val highTemp: Float?,
        val lowTemp: Float?,
        val fetchedAt: Long,
    )

    /**
     * One day's scored result, including which row supplied the "actual" (baseline provenance).
     * Provenance is surfaced per-row so the UI can disclose a borrowed baseline or a blend fallback.
     */
    data class DailyResult(
        val date: String,
        val computedHighTemp: Int,
        val computedLowTemp: Int,
        val forecastHigh: Int,
        val forecastLow: Int,
        val source: String,
        val highError: Int,
        val lowError: Int,
        val baselineSourceId: String? = null,
        val baselineStationId: String? = null,
        val baselineFellBackToBlend: Boolean = false,
    ) {
        /** The core fields as a pure scoring entry (provenance dropped). */
        fun toPureDailyAccuracy(): AccuracyPure.DailyAccuracy = AccuracyPure.DailyAccuracy(
            date = date,
            computedHighTemp = computedHighTemp,
            computedLowTemp = computedLowTemp,
            forecastHigh = forecastHigh,
            forecastLow = forecastLow,
            source = source,
            highError = highError,
            lowError = lowError,
        )
    }

    /**
     * Computes the per-day breakdown over [startDate]..[endDate] (inclusive).
     *
     * @param allExtremes every source's daily_history rows in the window.
     * @param forecasts the graded source's forecast rows in the window.
     * @param gradedSource the source whose forecast is being scored.
     * @param orderedVisibleSources the user's enabled sources, primary first (used to break
     *   baseline ties and to pick a borrowed baseline when [gradedSource] has no native actuals).
     */
    fun compute(
        startDate: LocalDate,
        endDate: LocalDate,
        allExtremes: List<DailyHistory>,
        forecasts: List<ForecastRow>,
        gradedSource: WeatherSource,
        orderedVisibleSources: List<WeatherSource>,
        baselineField: AccuracyBaselineField,
        lat: Double,
        lon: Double,
    ): List<DailyResult> {
        val startEpoch = startDate.toEpochDay() * MS_IN_A_DAY
        val endEpoch = endDate.toEpochDay() * MS_IN_A_DAY

        val result = mutableListOf<DailyResult>()
        var current = startDate
        while (!current.isAfter(endDate)) {
            val targetDate = current
            current = current.plusDays(1)

            val targetEpoch = targetDate.toEpochDay() * MS_IN_A_DAY
            val forecastEpoch = targetDate.minusDays(1).toEpochDay() * MS_IN_A_DAY

            val forecast = forecasts
                .filter { it.targetDate == targetEpoch && it.dateOfPrediction == forecastEpoch }
                .maxByOrNull { it.fetchedAt }
                ?: continue

            val rowsForDate = allExtremes.filter { it.date == targetEpoch }
            val baselineSource = ActualsBaselineResolver.resolveBaselineSource(
                gradedSource = gradedSource,
                orderedVisibleSources = orderedVisibleSources,
                // Forecast-only rows (null computed*) carry no observations — they must never
                // serve as an accuracy baseline.
                hasRowForDate = { candidate ->
                    rowsForDate.any { it.source == candidate.id && it.hasActuals }
                },
            ) ?: continue // no trustworthy actual for this day — exclude it rather than guess

            val baselineRow = rowsForDate
                .filter { it.source == baselineSource.id }
                .minByOrNull { TempUtils.distanceSq(it.locationLat, it.locationLon, lat, lon) }
                ?: continue
            val baselineHigh = baselineRow.computedHighTemp ?: continue
            val baselineLow = baselineRow.computedLowTemp ?: continue

            val temps = resolveBaselineTemps(
                field = baselineField,
                computedHigh = baselineHigh,
                computedLow = baselineLow,
                apiHigh = baselineRow.apiHighTemp,
                apiLow = baselineRow.apiLowTemp,
            )

            val entry = AccuracyPure.buildDailyAccuracy(
                date = targetDate.toString(),
                computedHighTemp = temps.high,
                computedLowTemp = temps.low,
                forecastHigh = forecast.highTemp,
                forecastLow = forecast.lowTemp,
                source = gradedSource.displayName,
            ) ?: continue

            result.add(
                DailyResult(
                    date = entry.date,
                    computedHighTemp = entry.computedHighTemp,
                    computedLowTemp = entry.computedLowTemp,
                    forecastHigh = entry.forecastHigh,
                    forecastLow = entry.forecastLow,
                    source = entry.source,
                    highError = entry.highError,
                    lowError = entry.lowError,
                    baselineSourceId = baselineSource.id.takeIf { it != gradedSource.id },
                    baselineStationId = baselineRow.apiStationId
                        ?.takeIf { baselineField == AccuracyBaselineField.NATIVE_ACTUAL && !temps.fellBackToBlend },
                    baselineFellBackToBlend = temps.fellBackToBlend,
                ),
            )
        }
        return result.sortedBy { it.date }
    }
}

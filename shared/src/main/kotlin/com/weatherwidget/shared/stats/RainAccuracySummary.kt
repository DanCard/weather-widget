package com.weatherwidget.shared.stats

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyRainLabels
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The "Rain amount" section of History of Forecasts' accuracy card, decided once for both platforms:
 * which lines appear, in what order, and which source wears the ★. Android renders these with
 * localized string resources; desktop renders English in `DesktopRainAccuracyText` (`:desktop`).
 */
object RainAccuracySummary {
    sealed interface ScoreLine {
        /** [rank] is 1-based. */
        data class Ranked(val rank: Int, val score: RainSourceScore) : ScoreLine

        /** Scored days, but fewer than [RainScore.MIN_RAIN_DAYS] rain days. */
        data class TooFewRainDays(val score: RainSourceScore) : ScoreLine

        /** No day had enough paired hours yet. */
        data class NoScoredDays(val source: WeatherSource) : ScoreLine
    }

    sealed interface DayLine {
        data class Result(val date: LocalDate, val actualMm: Float, val entries: List<Entry>) : DayLine

        /** Too few hours both measured and forecast by every source. */
        data class Insufficient(val date: LocalDate, val hours: Int) : DayLine

        /** Today: the day is scored once it is over. */
        data object Today : DayLine
    }

    data class Entry(val source: WeatherSource, val predMm: Float?, val closest: Boolean)

    /** Empty when no source has a single scored day. */
    fun scoreLines(report: RainAccuracyReport): List<ScoreLine> {
        if (report.scores.all { it.countedDays == 0 }) return emptyList()
        var rank = 0
        return report.scores.map { score ->
            when {
                score.ranked -> ScoreLine.Ranked(++rank, score)
                score.countedDays > 0 -> ScoreLine.TooFewRainDays(score)
                else -> ScoreLine.NoScoredDays(score.source)
            }
        }
    }

    /** The viewed date's line; null for a future date or one outside the report's window. */
    fun dayLine(report: RainAccuracyReport, viewed: LocalDate, today: LocalDate): DayLine? {
        if (viewed == today) return DayLine.Today
        if (viewed.isBefore(report.firstDate) || viewed.isAfter(report.lastDate)) return null
        val comparison = report.comparisons[viewed] ?: return null
        val actual = comparison.actualMm ?: return DayLine.Insufficient(viewed, comparison.commonHours)
        return DayLine.Result(
            date = viewed,
            actualMm = actual,
            entries = comparison.predMm.map { (source, pred) -> Entry(source, pred, source in comparison.closest) },
        )
    }

    // ---- Shared number formatting (units follow DailyRainLabels: inches for US/GB, else mm) ----

    fun amount(mm: Float): String = DailyRainLabels.formatPrecipAmount(abs(mm))

    fun measuredAmount(mm: Float): String = DailyRainLabels.formatObservedPrecipAmount(mm)

    /** "+.02in" / "−.06in". */
    fun signedAmount(mm: Float): String = (if (mm < 0f) "−" else "+") + amount(mm)

    fun percent(fraction: Float): String = "${(fraction * 100f).roundToInt()}%"

    /** A forecast that ran too wet (+), too dry (−) or neither. */
    enum class Lean { WET, DRY, EVEN }

    fun lean(biasMm: Float): Lean = when {
        biasMm > 0f -> Lean.WET
        biasMm < 0f -> Lean.DRY
        else -> Lean.EVEN
    }
}

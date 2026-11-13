package com.weatherwidget.desktop

import com.weatherwidget.shared.graph.ForecastHistoryHeader
import com.weatherwidget.shared.stats.RainAccuracyReport
import com.weatherwidget.shared.stats.RainAccuracySummary
import com.weatherwidget.shared.stats.RainAccuracySummary.DayLine
import com.weatherwidget.shared.stats.RainAccuracySummary.ScoreLine
import java.time.LocalDate

/**
 * Desktop's English for the accuracy card's "Rain amount" section. Which lines appear, their order
 * and the ★ come from the shared [RainAccuracySummary]; Android renders the same lines from string
 * resources (`ForecastHistoryActivity.rainScoreText` / `rainDayText`).
 */
internal object DesktopRainAccuracyText {
    fun score(line: ScoreLine): String = when (line) {
        is ScoreLine.Ranked -> {
            val s = line.score
            val bias = s.biasMm ?: 0f
            val lean = when (RainAccuracySummary.lean(bias)) {
                RainAccuracySummary.Lean.WET -> "wet ${RainAccuracySummary.signedAmount(bias)}"
                RainAccuracySummary.Lean.DRY -> "dry ${RainAccuracySummary.signedAmount(bias)}"
                RainAccuracySummary.Lean.EVEN -> "even"
            }
            "${line.rank}. ${s.source.displayName}: off by ${RainAccuracySummary.amount(s.meanAbsErrorMm ?: 0f)} on avg · " +
                "$lean · rain/no-rain right ${RainAccuracySummary.percent(s.hitRate ?: 0f)} · ${s.rainDays} rain days"
        }
        is ScoreLine.TooFewRainDays -> "–  ${line.score.source.displayName}: too few rain days (${line.score.rainDays})"
        is ScoreLine.NoScoredDays -> "–  ${line.source.displayName}: no scored days yet"
    }

    fun day(line: DayLine): String = when (line) {
        is DayLine.Result -> "${ForecastHistoryHeader.dateLabel(line.date)}: actual " +
            "${RainAccuracySummary.measuredAmount(line.actualMm)}\n" +
            line.entries.joinToString(" · ") { e ->
                (if (e.closest) "★ " else "") + "${e.source.displayName} ${e.predMm?.let(RainAccuracySummary::amount) ?: "—"}"
            }
        is DayLine.Insufficient -> "${ForecastHistoryHeader.dateLabel(line.date)}: not enough measured hours (${line.hours} of 24)"
        DayLine.Today -> "Today: scored after midnight"
    }

    /** The whole section body: ranking lines, then the viewed date's line. */
    fun section(report: RainAccuracyReport, viewed: LocalDate, today: LocalDate): String {
        val lines = RainAccuracySummary.scoreLines(report).map(::score)
            .ifEmpty { listOf("No scored rain days yet — needs measured rain and a forecast from the day before.") }
        val dayLine = RainAccuracySummary.dayLine(report, viewed, today)?.let(::day)
        return (lines + listOfNotNull(dayLine)).joinToString("\n")
    }
}

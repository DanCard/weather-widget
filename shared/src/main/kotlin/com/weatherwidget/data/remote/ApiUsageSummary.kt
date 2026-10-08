package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One `api_usage_stats` row, as both platforms read it. [dateMs] is the row's day key. */
data class ApiUsageRow(
    val dateMs: Long,
    val apiSource: String,
    val endpoint: String,
    val callCount: Int,
    val errorCount: Int = 0,
    val quotaRefusedCount: Int = 0,
)

/** Requests in the usage screen's four columns. */
data class UsageCounts(
    val today: Int = 0,
    val thisMonth: Int = 0,
    val lastMonth: Int = 0,
    val last90Days: Int = 0,
) {
    operator fun plus(other: UsageCounts) = UsageCounts(
        today + other.today,
        thisMonth + other.thisMonth,
        lastMonth + other.lastMonth,
        last90Days + other.last90Days,
    )
}

/** [endpoint] is blank for requests counted before endpoints were recorded (2026-10-08). */
data class EndpointUsage(val endpoint: String, val counts: UsageCounts)

/**
 * One source's requests. [errors] and [quotaRefused] cover the 90 days. [pacificDays]: its today and
 * months are Pacific ones (Google), so the screen can say so.
 */
data class SourceUsage(
    val sourceId: String,
    val displayName: String,
    val counts: UsageCounts,
    val errors: Int,
    val quotaRefused: Int,
    val endpoints: List<EndpointUsage>,
    val pacificDays: Boolean,
)

/**
 * Settings → Usage stats: `api_usage_stats` rows grouped per source and endpoint, in the columns
 * Today · This month · Last month · 90 days. One rule for Android and desktop; the screens only lay
 * the list out.
 *
 * "Today" and the months are the source's quota days ([ApiUsageClassifier.quotaZone]: Pacific for
 * Google, matching its Cloud Console), else the device's local days — the same rule that filed the
 * rows ([ApiUsageClassifier.usageDayMs]).
 */
object ApiUsageSummary {
    const val WINDOW_DAYS = 90L
    private const val DAY_MS = 86_400_000L

    fun summarize(rows: List<ApiUsageRow>, now: Instant, localZone: ZoneId): List<SourceUsage> =
        rows.groupBy { it.apiSource }
            .map { (source, sourceRows) -> summarizeSource(source, sourceRows, now, localZone) }
            .filter { it.counts.last90Days > 0 }
            .sortedWith(compareByDescending<SourceUsage> { it.counts.last90Days }.thenBy { it.displayName })

    private fun summarizeSource(source: String, rows: List<ApiUsageRow>, now: Instant, localZone: ZoneId): SourceUsage {
        val zone = ApiUsageClassifier.quotaZone(source) ?: localZone
        val today = now.atZone(zone).toLocalDate()
        val thisMonth = YearMonth.from(today)
        val firstInWindow = today.minusDays(WINDOW_DAYS - 1)

        fun counts(row: ApiUsageRow): UsageCounts {
            val day = LocalDate.ofEpochDay(Math.floorDiv(row.dateMs, DAY_MS))
            if (day < firstInWindow || day > today) return UsageCounts()
            val month = YearMonth.from(day)
            val n = row.callCount
            return UsageCounts(
                today = if (day == today) n else 0,
                thisMonth = if (month == thisMonth) n else 0,
                lastMonth = if (month == thisMonth.minusMonths(1)) n else 0,
                last90Days = n,
            )
        }

        val inWindow = rows.filter { counts(it).last90Days > 0 }
        val endpoints = inWindow.groupBy { it.endpoint }
            .map { (endpoint, endpointRows) ->
                EndpointUsage(endpoint, endpointRows.fold(UsageCounts()) { acc, row -> acc + counts(row) })
            }
            // Busiest first; the not-broken-down remainder always last.
            .sortedWith(compareBy<EndpointUsage> { it.endpoint.isEmpty() }.thenByDescending { it.counts.last90Days }.thenBy { it.endpoint })
        return SourceUsage(
            sourceId = source,
            displayName = WeatherSource.entries.firstOrNull { it.id == source }?.displayName ?: source,
            counts = endpoints.fold(UsageCounts()) { acc, e -> acc + e.counts },
            errors = inWindow.sumOf { it.errorCount },
            quotaRefused = inWindow.sumOf { it.quotaRefusedCount },
            endpoints = endpoints,
            pacificDays = ApiUsageClassifier.quotaZone(source) != null,
        )
    }
}

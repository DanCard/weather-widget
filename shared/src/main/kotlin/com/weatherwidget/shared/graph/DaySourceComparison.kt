package com.weatherwidget.shared.graph

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.daily.DailyColumnBuilder
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.PartialForecastDays
import com.weatherwidget.shared.util.TempUtils
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Forecast History screen's "All sources for <day>" table, both platforms
 * (plans/261105-all-sources-day-comparison.md): every visible source's forecast for one day,
 * sorted by high.
 *
 * Each row's values come from the daily column rule ([DailyColumnBuilder]) run with that source as
 * the display source, so a row shows the same high, rain % and noon cloud the widget's daily view
 * shows for that source. This object owns the whole rule (source scope, site choice, today's row,
 * sort, cell text and the log line). Platforms supply rows through [Host].
 *
 * Tapping a column header sorts by that column ([Sort.tapped], [sort]; plans/261112-all-sources-column-sort.md).
 * The choice is remembered across opens on both platforms as [Sort.encode] text.
 */
object DaySourceComparison {
    private const val TAG = "DaySourceComparison"
    private const val MS_IN_A_DAY = 86_400_000L
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L

    /**
     * Ages past this read as stale (dimmed): the background forecast interval on battery below 70 %,
     * so an older row missed at least one scheduled fetch.
     */
    const val STALE_AGE_MS = 24L * HOUR_MS

    /** One stored `forecasts` row for the day: the shared models of it plus its storage-key site. */
    data class StoredRow(
        val forecast: DailyForecast,
        val snapshot: DailyForecastSnapshot,
        val lat: Double,
        val lon: Double,
    )

    data class Row(
        val source: WeatherSource,
        val high: Float?,
        val low: Float?,
        val dayRainPercent: Int?,
        val nightRainPercent: Int?,
        /** "2pm" when the widget draws the peak hour above the day %; else null. */
        val dayPeakLabel: String?,
        /** The instant [dayPeakLabel] names (sorts the Peak column); null whenever that label is. */
        val dayPeakAt: Long? = null,
        val amountMm: Float?,
        val noonCloudPercent: Int?,
        /** 30-day high bias in °F, forecast − actual (+ = runs warm); null with no accuracy history. */
        val highBias: Double?,
        /** When a fetch last confirmed this source's forecast for the day; null = no row. */
        val lastConfirmedAt: Long?,
        /** Position in the user's visible-source order; every sort breaks ties by it. */
        val sourceOrder: Int = 0,
    ) {
        val hasForecast: Boolean get() = lastConfirmedAt != null
    }

    /** The text each column prints, identical on both platforms. Blank = "—". */
    data class Cells(
        val source: String,
        val high: String,
        val low: String,
        val dayRain: String,
        val peak: String,
        val nightRain: String,
        val amount: String,
        val cloud: String,
        val bias: String,
        val age: String,
        val ageIsStale: Boolean,
    ) {
        /** The cells in column order ([SortColumn]), blanks as "—". */
        fun values(): List<String> =
            listOf(source, high, low, dayRain, peak, nightRain, amount, cloud, bias, age).map { it.ifEmpty { "—" } }
    }

    interface Host {
        /** The user's visible sources in their order (ties in the sort keep it). */
        suspend fun visibleSources(): List<WeatherSource>

        /** Every stored `forecasts` row for [targetDateEpoch] in the [LocationMatch] box, all sources. */
        suspend fun forecastRows(targetDateEpoch: Long, lat: Double, lon: Double): List<StoredRow>

        /** `daily_history` rows for [targetDateEpoch] in the box, all sources. */
        suspend fun dailyHistory(targetDateEpoch: Long, lat: Double, lon: Double): List<DailyHistory>

        /** Newest stored hourly row per hour/source/site in the box, carrying source and site. */
        suspend fun hourly(startMs: Long, endMs: Long, lat: Double, lon: Double, sourceIds: List<String>): List<HourlyForecast>

        /**
         * 30-day `AccuracyStatistics.highBias` by source id (°F), as stored: actual − forecast, so
         * positive = the forecast ran low. A source without history is absent. [Row.highBias] flips it.
         */
        suspend fun highBias(sources: List<WeatherSource>, lat: Double, lon: Double): Map<String, Double>
    }

    suspend fun load(
        host: Host,
        date: LocalDate,
        lat: Double,
        lon: Double,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
        use24HourClock: Boolean = DailyRainLabels.localeUses24HourClock(),
    ): List<Row> {
        val sources = host.visibleSources().filter { it != WeatherSource.GENERIC_GAP }
        if (sources.isEmpty()) {
            Log.d(TAG, "DAY_SOURCE_COMPARE date=$date rows=0 reason=no_visible_sources")
            return emptyList()
        }
        val targetEpoch = date.toEpochDay() * MS_IN_A_DAY
        val stored = host.forecastRows(targetEpoch, lat, lon)
        val history = host.dailyHistory(targetEpoch, lat, lon)
        // The day plus its night (8pm until 8am the next morning), as the daily column reads.
        val hourly = host.hourly(
            date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            lat, lon,
            sources.map { it.id } + WeatherSource.GENERIC_GAP.id,
        )
        val bias = host.highBias(sources, lat, lon)
        val rows = build(date, now, lat, lon, sources, stored, history, hourly, bias, use24HourClock, zone)
        Log.d(
            TAG,
            "DAY_SOURCE_COMPARE date=$date site=%.4f,%.4f rows=${rows.size} stored=${stored.size} hourly=${hourly.size} order=${rows.joinToString(",") { it.source.id }} missing=${rows.filterNot { it.hasForecast }.joinToString(",") { it.source.id }.ifEmpty { "none" }}"
                .format(Locale.US, lat, lon),
        )
        return rows
    }

    fun build(
        date: LocalDate,
        now: LocalDateTime,
        lat: Double,
        lon: Double,
        sources: List<WeatherSource>,
        stored: List<StoredRow>,
        history: List<DailyHistory>,
        hourly: List<HourlyForecast>,
        highBias: Map<String, Double>,
        use24HourClock: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Row> {
        val today = now.toLocalDate()
        val rows = sources.filter { it != WeatherSource.GENERIC_GAP }.mapIndexed { index, source ->
            // Stored as actual − forecast; the table shows forecast − actual ("+2.1°" = runs warm),
            // the reading the accuracy card's "(2.1° high)" gives.
            val bias = highBias[source.id]?.let { -it }
            rowFor(source, date, today, now, lat, lon, stored, history, hourly, bias, use24HourClock, zone)
                .copy(sourceOrder = index)
        }
        // Highest high first; no high last; ties keep the user's source order.
        return sort(rows, Sort.DEFAULT)
    }

    /** The table's columns, in display order: [ordinal] is the column index. */
    enum class SortColumn(
        /** Direction of the first tap on this column. */
        val firstDescending: Boolean,
    ) {
        SOURCE(false), HIGH(true), LOW(true), DAY_RAIN(true), PEAK(false), NIGHT_RAIN(true),
        AMOUNT(true), CLOUD(true), BIAS(true), AGE(false),
    }

    data class Sort(val column: SortColumn, val descending: Boolean) {
        /** The sort after a tap on [tapped]'s header: the same column flips, another starts in its first direction. */
        fun tapped(tapped: SortColumn): Sort =
            if (tapped == column) copy(descending = !descending) else Sort(tapped, tapped.firstDescending)

        /** Stored form, both platforms: `DAY_RAIN:desc`. */
        fun encode(): String = "${column.name}:${if (descending) "desc" else "asc"}"

        companion object {
            /** Highest high first, as the table opened before sorting existed. */
            val DEFAULT = Sort(SortColumn.HIGH, descending = true)

            /** [encode]'s text back; null, blank or unknown text reads as [DEFAULT]. */
            fun decode(text: String?): Sort {
                val parts = text?.split(':') ?: return DEFAULT
                val column = SortColumn.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return DEFAULT
                return when (parts.getOrNull(1)) {
                    "desc" -> Sort(column, true)
                    "asc" -> Sort(column, false)
                    else -> DEFAULT
                }
            }
        }
    }

    /** The value a row sorts on for [column]; null = blank cell. */
    private fun sortKey(row: Row, column: SortColumn): Comparable<*>? = when (column) {
        SortColumn.SOURCE -> row.source.shortDisplayName.lowercase(Locale.ROOT)
        SortColumn.HIGH -> row.high
        SortColumn.LOW -> row.low
        SortColumn.DAY_RAIN -> row.dayRainPercent
        // Earliest peak first on the first tap.
        SortColumn.PEAK -> row.dayPeakAt
        SortColumn.NIGHT_RAIN -> row.nightRainPercent
        // The cell is blank for a zero amount, so a zero sorts with the blanks.
        SortColumn.AMOUNT -> row.amountMm?.takeIf { it > 0f }
        SortColumn.CLOUD -> row.noonCloudPercent
        SortColumn.BIAS -> row.highBias
        // Newest confirmation = youngest age.
        SortColumn.AGE -> row.lastConfirmedAt?.let { -it }
    }

    /**
     * [rows] ordered by [sort]. Blank cells go last in either direction; ties keep the user's
     * source order ([Row.sourceOrder]).
     */
    fun sort(rows: List<Row>, sort: Sort): List<Row> {
        val (valued, blank) = rows.partition { sortKey(it, sort.column) != null }
        val byValue = if (sort.descending) {
            compareByDescending<Row> { sortKey(it, sort.column) }
        } else {
            compareBy<Row> { sortKey(it, sort.column) }
        }
        return valued.sortedWith(byValue.thenBy { it.sourceOrder }) + blank.sortedBy { it.sourceOrder }
    }

    /** A header's text with the sort arrow on the sorted column: "Rain ▾" (descending) or "Rain ▴". */
    fun headerLabel(base: String, column: SortColumn, sort: Sort): String =
        if (column != sort.column) base else "$base ${if (sort.descending) "▾" else "▴"}"

    /** The sparse log line for a header tap, both platforms. */
    fun logSortTap(date: LocalDate, sort: Sort, rows: List<Row>) {
        Log.d(TAG, "DAY_SOURCE_SORT date=$date sort=${sort.encode()} order=${rows.joinToString(",") { it.source.id }}")
    }

    private fun rowFor(
        source: WeatherSource,
        date: LocalDate,
        today: LocalDate,
        now: LocalDateTime,
        lat: Double,
        lon: Double,
        stored: List<StoredRow>,
        history: List<DailyHistory>,
        hourly: List<HourlyForecast>,
        highBias: Double?,
        use24HourClock: Boolean,
        zone: ZoneId,
    ): Row {
        val isPast = date.isBefore(today)
        // As the newest-row-per-day reads: the source's own real forecasts with a temperature.
        val mine = stored.filter {
            it.forecast.source == source.id && !it.forecast.isClimateNormal &&
                (it.forecast.highTemp != null || it.forecast.lowTemp != null)
        }
        val sited = LocationMatch.selectNearestSite(mine, lat, lon, { it.lat }, { it.lon })
        val newest = sited.maxWithOrNull(
            compareBy<StoredRow> { it.forecast.lastConfirmedAt ?: 0L }.thenBy { it.forecast.fetchedAt ?: 0L },
        )
        val shown: StoredRow? = when {
            isPast -> null
            date == today -> todayRow(newest, mine)
            else -> newest
        }
        val older = sited.filter { it !== shown }.map { it.snapshot }
        val actual = LocationMatch.selectNearestSite(
            history.filter { it.source == source.id }, lat, lon, { it.locationLat }, { it.locationLon },
        ).firstOrNull()
        val sourceHourly = siteHourly(hourly, source.id, lat, lon) + siteHourly(hourly, WeatherSource.GENERIC_GAP.id, lat, lon)

        val column = DailyColumnBuilder.build(
            date = date,
            label = source.shortDisplayName,
            today = today,
            now = now,
            forecast = shown?.forecast,
            actual = actual,
            snapshots = older,
            hourly = sourceHourly,
            currentTemp = null,
            displaySourceId = source.id,
            centerLat = lat,
            centerLon = lon,
            use24HourClock = use24HourClock,
        )
        val isFuture = date.isAfter(today)
        // The forecast, never the observed line: future days' bar, today's and past days' forecast bar.
        val high = if (isFuture) column.solidHigh else column.forecastHigh
        val low = if (isFuture) column.solidLow else column.forecastLow
        val amount = if (isPast) actual?.forecastPrecipAmountMm ?: column.snapshot?.precipAmountMm else shown?.forecast?.precipAmountMm
        val lastConfirmed = sited.maxOfOrNull { it.forecast.lastConfirmedAt ?: it.forecast.fetchedAt ?: 0L }?.takeIf { it > 0L }
        return Row(
            source = source,
            high = high,
            low = low,
            dayRainPercent = column.dayPrecipProbability,
            nightRainPercent = column.nightPrecipProbability,
            dayPeakLabel = column.dailyRainPeakLabelText,
            dayPeakAt = column.dailyRainPeakAt,
            amountMm = amount,
            noonCloudPercent = column.cloudCoverRatio?.let { (it * 100f).roundToInt() },
            highBias = highBias,
            lastConfirmedAt = lastConfirmed,
        )
    }

    /**
     * Today's row by the shared rule ([PartialForecastDays.todayRowNearestSite]). When a stored
     * complete row stands in for a partial newest one, it lends its temperatures and condition and
     * the newest keeps its newer rain fields, as desktop's daily read does.
     */
    private fun todayRow(newest: StoredRow?, boxRows: List<StoredRow>): StoredRow? {
        val anchorLat = newest?.lat ?: return null
        val anchorLon = newest.lon
        val chosen = PartialForecastDays.todayRowNearestSite(
            newest, boxRows, anchorLat, anchorLon,
            { it.lat }, { it.lon }, { it.forecast.highTemp }, { it.forecast.lowTemp }, { it.forecast.fetchedAt ?: 0L },
        ) ?: return null
        if (chosen === newest) return newest
        return newest.copy(
            forecast = newest.forecast.copy(
                highTemp = chosen.forecast.highTemp,
                lowTemp = chosen.forecast.lowTemp,
                condition = chosen.forecast.condition,
            ),
        )
    }

    /** [sourceId]'s hourly rows at the nearest site; rows without coordinates cannot be placed. */
    private fun siteHourly(hourly: List<HourlyForecast>, sourceId: String, lat: Double, lon: Double): List<HourlyForecast> =
        LocationMatch.selectNearestSite(
            hourly.filter { it.source == sourceId && it.locationLat != null && it.locationLon != null },
            lat, lon, { it.locationLat ?: lat }, { it.locationLon ?: lon },
        )

    fun cells(row: Row, nowMs: Long, useCelsius: Boolean): Cells {
        val age = row.lastConfirmedAt?.let { (nowMs - it).coerceAtLeast(0L) }
        return Cells(
            source = row.source.shortDisplayName,
            high = TempUtils.formatTemp(row.high, useCelsius).orEmpty(),
            low = TempUtils.formatTemp(row.low, useCelsius).orEmpty(),
            dayRain = row.dayRainPercent?.let { "$it%" }.orEmpty(),
            peak = row.dayPeakLabel.orEmpty(),
            nightRain = row.nightRainPercent?.let { "$it%" }.orEmpty(),
            amount = row.amountMm?.takeIf { it > 0f }?.let { DailyRainLabels.formatPrecipAmount(it) }.orEmpty(),
            cloud = row.noonCloudPercent?.let { "$it%" }.orEmpty(),
            bias = row.highBias?.let { formatBias(it, useCelsius) }.orEmpty(),
            age = age?.let { formatAge(it) }.orEmpty(),
            ageIsStale = age != null && age > STALE_AGE_MS,
        )
    }

    /** Index of the Amount column in the shared column order ([SortColumn]). */
    val AMOUNT_COLUMN = SortColumn.AMOUNT.ordinal

    /** Relative column widths, both platforms, in the shared column order. */
    val COLUMN_WEIGHTS = listOf(1.25f, 0.8f, 0.8f, 0.85f, 0.85f, 0.85f, 1.1f, 0.85f, 0.95f, 0.75f)

    /** Narrowest table (dp) that still fits the Amount column on one line at 12sp. */
    const val AMOUNT_MIN_WIDTH_DP = 380f

    /**
     * Both platforms: Amount is shown only when the table is wide enough and some row has one;
     * a narrow table drops it rather than wrapping.
     */
    fun showAmountColumn(tableWidthDp: Float, cells: List<Cells>): Boolean =
        tableWidthDp >= AMOUNT_MIN_WIDTH_DP && cells.any { it.amount.isNotEmpty() }

    /**
     * The legend under the table, both platforms: the columns whose header alone is ambiguous, in
     * column order. Amount only while its column is shown. Source, High and Low need no note.
     * The wording is per platform (desktop `LEGEND_TEXT`, Android `source_compare_legend_*`) and says
     * exactly what each cell holds: Rain/Night are the windowed hourly max ([DailyRainLabels]), Amount
     * the stored midnight-to-midnight row total, Bias the 30-day mean high error as forecast − actual.
     */
    fun legendColumns(showAmount: Boolean): List<SortColumn> =
        LEGEND_COLUMNS.filter { showAmount || it != SortColumn.AMOUNT }

    private val LEGEND_COLUMNS = listOf(
        SortColumn.DAY_RAIN, SortColumn.PEAK, SortColumn.NIGHT_RAIN, SortColumn.AMOUNT,
        SortColumn.CLOUD, SortColumn.BIAS, SortColumn.AGE,
    )

    /** Signed bias in display units: "+2.1°", "−1.2°" (true minus), "0.0°". */
    fun formatBias(biasF: Double, useCelsius: Boolean): String {
        val v = TempUtils.displayDelta(biasF, useCelsius)
        val text = String.format(Locale.getDefault(), "%.1f°", kotlin.math.abs(v))
        return when {
            text.startsWith("0.0") || text.startsWith("0,0") -> text
            v > 0 -> "+$text"
            else -> "−$text"
        }
    }

    /** Compact age: "12m" under an hour, "3h" under a day, then "1d". */
    fun formatAge(ageMs: Long): String {
        val ms = ageMs.coerceAtLeast(0L)
        return when {
            ms < HOUR_MS -> "${ms / MINUTE_MS}m"
            ms < MS_IN_A_DAY -> "${ms / HOUR_MS}h"
            else -> "${ms / MS_IN_A_DAY}d"
        }
    }
}

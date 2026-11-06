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
        val amountMm: Float?,
        val noonCloudPercent: Int?,
        /** 30-day high bias in °F, forecast − actual (+ = runs warm); null with no accuracy history. */
        val highBias: Double?,
        /** When a fetch last confirmed this source's forecast for the day; null = no row. */
        val lastConfirmedAt: Long?,
    ) {
        val hasForecast: Boolean get() = lastConfirmedAt != null
    }

    /** The text each column prints, identical on both platforms. Blank = "—". */
    data class Cells(
        val source: String,
        val high: String,
        val low: String,
        val dayRain: String,
        val nightRain: String,
        val amount: String,
        val cloud: String,
        val bias: String,
        val age: String,
        val ageIsStale: Boolean,
    )

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
        val rows = sources.filter { it != WeatherSource.GENERIC_GAP }.map { source ->
            // Stored as actual − forecast; the table shows forecast − actual ("+2.1°" = runs warm),
            // the reading the accuracy card's "(2.1° high)" gives.
            val bias = highBias[source.id]?.let { -it }
            rowFor(source, date, today, now, lat, lon, stored, history, hourly, bias, use24HourClock, zone)
        }
        // Highest high first; no high last; ties keep the user's source order (stable sort).
        return rows.sortedWith(compareBy<Row> { it.high == null }.thenByDescending { it.high ?: 0f })
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
            dayRain = row.dayRainPercent?.let { pct -> "$pct%" + (row.dayPeakLabel?.let { " $it" } ?: "") }.orEmpty(),
            nightRain = row.nightRainPercent?.let { "$it%" }.orEmpty(),
            amount = row.amountMm?.takeIf { it > 0f }?.let { DailyRainLabels.formatPrecipAmount(it) }.orEmpty(),
            cloud = row.noonCloudPercent?.let { "$it%" }.orEmpty(),
            bias = row.highBias?.let { formatBias(it, useCelsius) }.orEmpty(),
            age = age?.let { formatAge(it) }.orEmpty(),
            ageIsStale = age != null && age > STALE_AGE_MS,
        )
    }

    /** Index of the Amount column in the shared column order (Source, High, Low, Rain, Night, Amount, Cloud, Bias, Age). */
    const val AMOUNT_COLUMN = 5

    /** Relative column widths, both platforms, in the shared column order. */
    val COLUMN_WEIGHTS = listOf(1.25f, 0.8f, 0.8f, 1.3f, 0.85f, 1.1f, 0.85f, 0.95f, 0.75f)

    /** Narrowest table (dp) that still fits the Amount column on one line at 12sp. */
    const val AMOUNT_MIN_WIDTH_DP = 380f

    /**
     * Both platforms: Amount is shown only when the table is wide enough and some row has one;
     * a narrow table drops it rather than wrapping.
     */
    fun showAmountColumn(tableWidthDp: Float, cells: List<Cells>): Boolean =
        tableWidthDp >= AMOUNT_MIN_WIDTH_DP && cells.any { it.amount.isNotEmpty() }

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

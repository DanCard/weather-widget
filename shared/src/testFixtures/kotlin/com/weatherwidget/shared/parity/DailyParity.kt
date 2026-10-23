package com.weatherwidget.shared.parity

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One set of days, two daily-column builders (plans/261023-shared-daily-column-builder.md).
 *
 * Android's `DailyViewLogic.prepareGraphDays` and desktop's `DesktopDailyForecastModel.build` each
 * read [FIXTURES] (mapped to their own input types), reduce what they produce to [Column] and write
 * [render]ed text under `shared/build/daily-parity/<platform>/`, and must equal the agreed
 * [expected] text (`testFixtures/resources/daily-parity/<fixture>.txt`) — so a change to either
 * builder that alters a column fails that platform's test. `scripts/daily_parity_diff.py` diffs the
 * two platforms' latest outputs line by line.
 *
 * Changing a rule on purpose: change it on both platforms (or in `:shared`), rerun both tests, and
 * copy the new output over the expected file — the diff of that file is the review.
 */
object DailyParity {
    const val LAT = 37.417
    const val LON = -122.089
    val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    val TODAY: LocalDate = LocalDate.of(2026, 10, 23)

    fun ms(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli()

    fun utcMidnight(date: LocalDate): Long = date.toEpochDay() * 86_400_000L

    /** One stored daily forecast row (a fetch's view of one date). */
    data class Row(
        val date: LocalDate,
        val fetchedAt: Long,
        val high: Float?,
        val low: Float?,
        val condition: String,
        val source: String,
        val iconToken: String? = null,
        val precip: Int? = null,
        val dayPrecip: Int? = null,
        val nightPrecip: Int? = null,
        val noonCloud: Int? = null,
        val isClimateNormal: Boolean = false,
    )

    data class Fixture(
        val name: String,
        val now: LocalDateTime,
        val displaySource: String,
        val rows: List<Row>,
        val history: List<DailyHistory> = emptyList(),
        val hourly: List<HourlyForecast> = emptyList(),
        val currentTemp: Float? = null,
    ) {
        val today: LocalDate get() = now.toLocalDate()

        /** Each date's newest row of [source] (what both platforms' daily reads return). */
        fun newestRows(source: String = displaySource): Map<LocalDate, Row> =
            rows.filter { it.source == source || it.isClimateNormal }
                .groupBy { it.date }
                .mapValues { (_, rs) -> rs.maxBy { it.fetchedAt } }

        /** Every row of [source] per date (the prior / "yesterday's forecast" candidates). */
        fun allRows(source: String = displaySource): Map<LocalDate, List<Row>> =
            rows.filter { it.source == source }.groupBy { it.date }
    }

    /** What a column shows, in one vocabulary for both platforms. */
    data class Column(
        val date: LocalDate,
        val barTop: Float?,
        val solidLow: Float?,
        val fcstHigh: Float?,
        val fcstLow: Float?,
        val priorHigh: Float?,
        val priorLow: Float?,
        val ghost: Float?,
        val icon: String?,
        val priorIcon: String?,
        val noonCloud: Int?,
        val dayRain: Int?,
        val nightRain: Int?,
        val rainLabel: String?,
        val peakLabel: String?,
        val nightLabel: String?,
        val priorRain: Int?,
        val climate: Boolean,
        val promoted: Boolean,
        val priorStale: Boolean,
        val fcstFallback: Boolean,
        val otherSite: Boolean,
    )

    private fun f(v: Float?) = v?.let { "%.1f".format(it) } ?: "-"
    private fun v(x: Any?) = x?.toString() ?: "-"

    /** Fields only a today / past column draws (the "yesterday's forecast" bar). */
    private val PRIOR_FIELDS = setOf("priorHigh", "priorLow", "priorIcon", "priorRain", "priorStale")

    /**
     * One line per field, `date field=value`, so a line diff names the field. A future column draws
     * no prior bar, so its prior fields are left out.
     */
    fun render(fixture: Fixture, columns: List<Column>): String = buildString {
        val (from, to) = window(fixture)
        for (c in columns.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.date }) {
            val d = c.date
            val future = d.isAfter(fixture.today)
            listOf(
                "barTop" to f(c.barTop), "solidLow" to f(c.solidLow),
                "fcstHigh" to f(c.fcstHigh), "fcstLow" to f(c.fcstLow),
                "priorHigh" to f(c.priorHigh), "priorLow" to f(c.priorLow), "ghost" to f(c.ghost),
                "icon" to v(c.icon), "priorIcon" to v(c.priorIcon), "noonCloud" to v(c.noonCloud),
                "dayRain" to v(c.dayRain), "nightRain" to v(c.nightRain),
                "rainLabel" to v(c.rainLabel), "peakLabel" to v(c.peakLabel), "nightLabel" to v(c.nightLabel),
                "priorRain" to v(c.priorRain), "climate" to v(c.climate), "promoted" to v(c.promoted),
                "priorStale" to v(c.priorStale), "fcstFallback" to v(c.fcstFallback), "otherSite" to v(c.otherSite),
            ).filterNot { future && it.first in PRIOR_FIELDS }
                .forEach { (k, value) -> appendLine("$d $k=$value") }
        }
    }

    /** Dates compared: what both builders show for a centred-on-today view. */
    fun window(fixture: Fixture): Pair<LocalDate, LocalDate> = fixture.today.minusDays(2) to fixture.today.plusDays(6)

    /** The agreed columns for [fixture], one `date field=value` line per field. */
    fun expected(fixture: Fixture): String =
        DailyParity::class.java.getResource("/daily-parity/${fixture.name}.txt")?.readText()
            ?: error("no expected file daily-parity/${fixture.name}.txt in :shared testFixtures resources")

    fun write(platform: String, fixture: Fixture, text: String) {
        val root = listOf(File("../shared/build"), File("shared/build")).first { it.parentFile?.isDirectory ?: false }
        val dir = File(root, "daily-parity/$platform").apply { mkdirs() }
        File(dir, "${fixture.name}.txt").writeText(text)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────

    private const val GOOGLE = "GOOGLE_WEATHER"
    private const val NWS = "NWS"

    private fun history(
        date: LocalDate,
        source: String,
        high: Float?,
        low: Float?,
        condition: String,
        noonCloud: Int? = null,
        forecastHigh: Float? = null,
        forecastLow: Float? = null,
        dayChance: Int? = null,
        nightChance: Int? = null,
        priorHigh: Float? = null,
        priorLow: Float? = null,
    ) = DailyHistory(
        date = utcMidnight(date),
        source = source,
        locationLat = LAT,
        locationLon = LON,
        computedHighTemp = high,
        computedLowTemp = low,
        condition = condition,
        updatedAt = ms(date.plusDays(1), 1),
        forecastDayPrecipChance = dayChance,
        forecastNightPrecipChance = nightChance,
        forecastHighTemp = forecastHigh,
        forecastLowTemp = forecastLow,
        noonCloudPercent = noonCloud,
        priorForecastHighTemp = priorHigh,
        priorForecastLowTemp = priorLow,
    )

    /** 24 hourly rows for [date] of [source]: a gentle day curve, [cloud] all day, [pop] by day. */
    fun hourlyDay(date: LocalDate, source: String, low: Float, high: Float, cloud: Int, pop: Int = 0, fetchedAt: Long) =
        (0 until 24).map { h ->
            val t = low + (high - low) * when {
                h < 6 -> 0f
                h <= 15 -> (h - 6) / 9f
                else -> (1f - (h - 15) / 9f).coerceAtLeast(0f)
            }
            HourlyForecast(
                dateTime = ms(date, h),
                temperature = t,
                condition = if (cloud > 60) "Cloudy" else "Clear",
                precipProbability = if (h in 8..19) pop else 0,
                cloudCover = cloud,
                source = source,
                fetchedAt = fetchedAt,
                locationLat = LAT,
                locationLon = LON,
                cloudCoverLow = cloud,
            )
        }

    /**
     * A week of [source] forecasts: rows fetched yesterday 05:00 and 15:00 (the prior picks) and
     * today 04:00 (the newest), for every date from 3 days back to 8 ahead. [day] tweaks one date.
     */
    private fun week(
        today: LocalDate,
        source: String,
        day: (LocalDate, Row) -> Row = { _, r -> r },
    ): List<Row> {
        val fetches = listOf(ms(today.minusDays(1), 5), ms(today.minusDays(1), 15), ms(today, 4))
        return (-3..8).flatMap { offset ->
            val date = today.plusDays(offset.toLong())
            fetches.mapIndexed { i, at ->
                day(
                    date,
                    Row(
                        date = date, fetchedAt = at, high = 68f + offset + i * 0.5f, low = 50f + offset,
                        condition = "Clear", source = source, precip = 0, dayPrecip = 0, nightPrecip = 0,
                        noonCloud = 10,
                    ),
                )
            }.filter { it.fetchedAt < ms(it.date.plusDays(1), 0) } // no fetch after its day ended
        }
    }

    private val pastHistory: (LocalDate, String) -> List<DailyHistory> = { today, source ->
        (1..3).map { back ->
            val d = today.minusDays(back.toLong())
            history(d, source, 66f - back, 52f - back, "Clear", noonCloud = 20, forecastHigh = 67f - back, forecastLow = 51f - back)
        }
    }

    val FIXTURES: List<Fixture> = run {
        val today = TODAY
        val now = today.atTime(12, 0)
        val todayFetch = ms(today, 4)
        listOf(
            // Google, noon cloud 91 % today under a "Clear" word; day 8 past the download.
            Fixture(
                name = "google-cloudy-today",
                now = now,
                displaySource = GOOGLE,
                rows = week(today, GOOGLE) { d, r ->
                    when (d) {
                        today -> r.copy(noonCloud = 91, precip = 20, dayPrecip = 20)
                        today.plusDays(8) -> r.copy(noonCloud = null, condition = "Cloudy")
                        else -> r
                    }
                },
                history = pastHistory(today, GOOGLE),
                hourly = hourlyDay(today, GOOGLE, 52f, 67f, cloud = 91, pop = 20, fetchedAt = todayFetch) +
                    hourlyDay(today.plusDays(1), GOOGLE, 48f, 71f, cloud = 0, fetchedAt = todayFetch),
                currentTemp = 58.9f,
            ),
            // Rain today and tomorrow, with a dry prior forecast for today.
            Fixture(
                name = "rainy-days",
                now = now,
                displaySource = GOOGLE,
                rows = week(today, GOOGLE) { d, r ->
                    when {
                        d == today && r.fetchedAt == todayFetch ->
                            r.copy(condition = "Rain", noonCloud = 95, precip = 80, dayPrecip = 70, nightPrecip = 40)
                        d == today.plusDays(1) ->
                            r.copy(condition = "Showers", noonCloud = 80, precip = 65, dayPrecip = 65, nightPrecip = 30)
                        else -> r
                    }
                },
                history = pastHistory(today, GOOGLE),
                hourly = hourlyDay(today, GOOGLE, 52f, 60f, cloud = 95, pop = 70, fetchedAt = todayFetch),
                currentTemp = 55f,
            ),
            // NWS in the evening: the newest batch has no high for today, an earlier one does.
            Fixture(
                name = "nws-evening-partial",
                now = today.atTime(21, 0),
                displaySource = NWS,
                rows = week(today, NWS) + Row(
                    date = today, fetchedAt = ms(today, 20), high = null, low = 49f, condition = "Clear",
                    source = NWS, precip = 0, dayPrecip = null, nightPrecip = 0,
                ),
                history = pastHistory(today, NWS) +
                    history(today, NWS, 64f, 51f, "Clear", noonCloud = 15),
                hourly = hourlyDay(today, NWS, 51f, 64f, cloud = 15, fetchedAt = ms(today, 20)),
                currentTemp = 55f,
            ),
            // Yesterday has no actuals: a forecast-only history row; two days back has nothing.
            Fixture(
                name = "past-forecast-only",
                now = now,
                displaySource = GOOGLE,
                rows = week(today, GOOGLE),
                history = listOf(
                    history(today.minusDays(1), GOOGLE, null, null, "Clear", forecastHigh = 66f, forecastLow = 50f),
                ),
                currentTemp = 58f,
            ),
            // The source's forecast ends after day 5; climate-normal rows fill days 6–8.
            Fixture(
                name = "climate-gap",
                now = now,
                displaySource = NWS,
                rows = week(today, NWS).filter { it.date <= today.plusDays(5) } +
                    (6..8).map { off ->
                        Row(
                            date = today.plusDays(off.toLong()), fetchedAt = todayFetch, high = 70f, low = 52f,
                            condition = "Climate Normal", source = WeatherSource.GENERIC_GAP.id, isClimateNormal = true,
                        )
                    },
                history = pastHistory(today, NWS),
                currentTemp = 60f,
            ),
            // Silurian's evening batch starts at tomorrow: today only in older fetches.
            Fixture(
                name = "today-missing-from-batch",
                now = today.atTime(20, 30),
                displaySource = "SILURIAN",
                rows = week(today, "SILURIAN").filterNot { it.date == today && it.fetchedAt == todayFetch } +
                    (1..6).map { off ->
                        Row(
                            date = today.plusDays(off.toLong()), fetchedAt = ms(today, 20), high = 70f + off,
                            low = 51f + off, condition = "Clear", source = "SILURIAN", precip = 0, noonCloud = 30,
                        )
                    },
                history = pastHistory(today, "SILURIAN") + history(today, "SILURIAN", 67f, 52f, "Clear"),
                currentTemp = 61f,
            ),
            // Prior fetches older than the 48 h pick limit, and none before yesterday's cutoffs.
            Fixture(
                name = "stale-prior",
                now = now,
                displaySource = GOOGLE,
                rows = week(today, GOOGLE).filter { it.fetchedAt == todayFetch } +
                    (-3..1).map { off ->
                        val d = today.plusDays(off.toLong())
                        Row(
                            date = d, fetchedAt = ms(d.minusDays(4), 9), high = 60f, low = 45f,
                            condition = "Cloudy", source = GOOGLE, precip = 30, dayPrecip = 30, noonCloud = 80,
                        )
                    },
                history = pastHistory(today, GOOGLE),
                currentTemp = 58f,
            ),
            // Early morning: today's actual low still falling, no actual high yet.
            Fixture(
                name = "early-morning",
                now = today.atTime(6, 30),
                displaySource = GOOGLE,
                rows = week(today, GOOGLE),
                history = pastHistory(today, GOOGLE) + history(today, GOOGLE, null, 49f, "Clear"),
                hourly = hourlyDay(today, GOOGLE, 48f, 68f, cloud = 20, fetchedAt = todayFetch),
                currentTemp = 49f,
            ),
        )
    }
}

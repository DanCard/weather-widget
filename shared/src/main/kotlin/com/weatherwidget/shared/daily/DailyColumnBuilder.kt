package com.weatherwidget.shared.daily

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.PartialForecastDays
import com.weatherwidget.shared.util.PastDayForecastOverlay
import com.weatherwidget.shared.util.PriorDayForecast
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One daily-view column's values, both platforms: bar and line values, the prior bar, icon names,
 * noon cloud, day / night rain and their labels, stand-in flags. No paint, no drawable ids.
 */
data class DailyColumn(
    val date: LocalDate,
    val label: String,
    val forecast: DailyForecast?,
    val actual: DailyHistory?,
    val snapshot: DailyForecastSnapshot?,
    val solidHigh: Float?,
    val solidLow: Float?,
    val forecastHigh: Float?,
    val forecastLow: Float?,
    /**
     * Thermostat bar top (shared [com.weatherwidget.shared.util.DailyDayValueResolver.TodayLineValues.barTopHigh]):
     * [solidHigh] ?: [forecastHigh]. Today's middle bar draws to this so it still rises to
     * today's high when the current reading and observed peak are both missing.
     */
    val barTopHigh: Float?,
    /** Today-only faint high-water mark (peak observed so far); ghost line reaches up to this. */
    val ghostHigh: Float?,
    val snapshotHigh: Float?,
    val snapshotLow: Float?,
    val iconCondition: String?,
    /**
     * Resolved + gated daily icon NAME (e.g. "ic_weather_mostly_clear"). Derived from [iconCondition]
     * with the source-filtered noon cloud % threaded in and the daily partly-cloudy floor applied,
     * so the displayed icon (and its painter/colors/tap-routing) match Android.
     */
    val iconName: String,
    val snapshotIconName: String? = null,
    /** The "yesterday's forecast" bar's rain chance (shared AdaptiveBarStrokes.priorBarRainPercent). */
    val priorBarRainPercent: Int? = null,
    val isToday: Boolean,
    val isPast: Boolean,
    /** True when this past day's solid line is a promoted forecast (no actuals) — render as forecast. */
    val solidIsForecastFallback: Boolean,
    val cloudCoverRatio: Float?,
    /** Daytime rain label drawn on top of the bar (observed amount, forecast amount, or prob%). */
    val dailyRainLabelText: String?,
    /** When the daytime chance peaks ("1pm"), drawn above [dailyRainLabelText]; null = none. */
    val dailyRainPeakLabelText: String? = null,
    /** Nighttime rain label tucked between columns (observed amount or prob%). */
    val nightRainLabelText: String?,
    /** Resolved day/night precip chance driving the rain-label FONT SIZE (shared scaling rule). */
    val dayPrecipProbability: Int?,
    val nightPrecipProbability: Int?,
    /** Signed offset from today (today=0, tomorrow=1, yesterday=-1); feeds the distance font term. */
    val daysFromToday: Int,
    val isClimateNormal: Boolean,
    /** Local hour-of-day (0–23) for the today column's high cutoff; null = legacy. */
    val nowHour: Int? = null,
    /** Today's low is settled ([com.weatherwidget.shared.util.TodayLowSettle]) — print the observed actual. */
    val lowSettled: Boolean = false,
    /** Today's left bar was last confirmed more than a day before its anchor — drawn dashed (StandInBarStyle). Mirrors Android. */
    val snapshotIsStale: Boolean = false,
    /** This past day's actuals were measured at a previous site (PreviousSiteHistory) — drawn dashed. */
    val actualsFromOtherSite: Boolean = false,
    /** A past day's right (settled forecast) bar has a fallback side (PastDayForecastOverlay) — dashed. */
    val forecastIsFallback: Boolean = false,
)

/**
 * The daily column rule, both platforms (plans/261023-shared-daily-column-builder.md): which row,
 * which values per line, which icon, which rain chances and labels, for a past, today or future
 * column. Moved from desktop's `DesktopDailyForecastModel.buildDay`; the parity fixtures
 * (`:shared` testFixtures `DailyParity`) pin what it produces.
 *
 * Inputs are what both platforms' daily reads return: `forecast` the row the day shows (today and
 * future only — from the newest batch's first day on; today's by [PartialForecastDays.todayRow]),
 * `snapshots` the day's older fetches (the shown row skipped).
 */
object DailyColumnBuilder {

    /**
     * The high a text-mode column prints (1-row widget, desktop text popup): past and future the
     * column's own (actual, else the forecast it stands on); today the highest it shows — tracked,
     * forecast or the day's peak so far. Never "yesterday's forecast" (desktop used to fold it in).
     */
    fun textHigh(c: DailyColumn): Float? =
        if (c.isToday) listOfNotNull(c.solidHigh, c.forecastHigh, c.ghostHigh).maxOrNull() else c.solidHigh

    /** The low a text-mode column prints: today the tracked low, else the forecast low; otherwise its own. */
    fun textLow(c: DailyColumn): Float? = if (c.isToday) c.solidLow ?: c.forecastLow else c.solidLow

    fun build(
        date: LocalDate,
        /** The column's caption ("Today", "Sat") — a platform string. */
        label: String,
        today: LocalDate,
        now: LocalDateTime,
        forecast: DailyForecast?,
        actual: DailyHistory?,
        snapshots: List<DailyForecastSnapshot>,
        hourly: List<HourlyForecast>,
        currentTemp: Float?,
        displaySourceId: String,
        centerLat: Double,
        centerLon: Double,
        use24HourClock: Boolean = com.weatherwidget.shared.util.DailyRainLabels.localeUses24HourClock(),
        /** Today's rain-chance label; every production caller allows it. */
        allowTodayRainChanceLabel: Boolean = true,
    ): DailyColumn {
        val isToday = date == today
        val isPast = date.isBefore(today)
        // The past-day row (shared rule, PastDayForecastOverlay). A one-sided row still supplies the
        // non-overlay fields (rain chance, condition); it never draws the overlay bar — see below.
        val snapshot = PastDayForecastOverlay.pick(snapshots, { it.highTemp }, { it.lowTemp }, { it.fetchedAt })
            ?: snapshots
                .filter { it.highTemp != null || it.lowTemp != null }
                .maxByOrNull { it.fetchedAt }

        // Today's left bar: "yesterday's forecast" — low from the newest fetch before 06:00 yesterday,
        // high from the newest before 16:00 yesterday (PriorDayForecast, shared with Android and the
        // history freeze). Its condition follows the high's row.
        val zone = ZoneId.systemDefault()
        val todayPick = PriorDayForecast.select(
            snapshots, date, zone,
            fetchedAt = { it.fetchedAt }, high = { it.highTemp }, low = { it.lowTemp },
            fallbackToEarliest = true,
        )
        val todaySnapshot = todayPick.highRow ?: todayPick.lowRow
        val displaySnapshot = if (isToday) todaySnapshot else snapshot
        // A past day's left bar: the pair frozen into daily_history (planPriorForecasts), else picked
        // live from the stored fetches until the freeze has run. Drawn only when both ends are known.
        val pastPrior = if (isPast) {
            PriorDayForecast.resolvePast(
                actual?.priorForecastHighTemp, actual?.priorForecastLowTemp, snapshots, date, zone,
                fetchedAt = { it.fetchedAt }, high = { it.highTemp }, low = { it.lowTemp },
                hindcastHigh = { it.hindcastHighTemp }, hindcastLow = { it.hindcastLowTemp },
            )
        } else {
            null
        }

        val solidHigh: Float?
        val solidLow: Float?
        val forecastHigh: Float?
        val forecastLow: Float?
        // Ghost (high-water mark) is a today-only affordance, matching Android.
        var ghostHigh: Float? = null
        var barTopHigh: Float? = null
        var solidIsForecastFallback = false
        var forecastIsFallback = false
        var lowSettled = false

        when {
            isPast -> {
                // Prefer the overlay frozen into daily_history while the day was live (see
                // DailyHistoryFreeze) — it survives the forecasts table's retention and can't
                // hindcast-drift. High/low are written as a unit, so checking both guards against
                // mixing a frozen value with a snapshot one. Pre-feature rows fall back to the
                // snapshot table.
                // Shared chain with Android (PastDayForecastOverlay.resolve): frozen, newest pair,
                // per-side stored value, then the dashed fallbacks — post-cutoff value, hourly forecast
                // range, hourly hindcast range.
                // `snapshots` excludes the newest fetch batch; its row for this day is `forecast`,
                // which ranks newest.
                val pastSourceId = WeatherSource.fromDisplaySource(displaySourceId).id
                val dayHourly = hourly.filter {
                    Instant.ofEpochMilli(it.dateTime).atZone(zone).toLocalDate() == date &&
                        (it.source == null || it.source == pastSourceId)
                }
                val overlay = PastDayForecastOverlay.resolve(
                    frozenHigh = actual?.forecastHighTemp,
                    frozenLow = actual?.forecastLowTemp,
                    candidates = snapshots +
                        listOfNotNull(
                            forecast?.let {
                                DailyForecastSnapshot(
                                    date = it.date, highTemp = it.highTemp, lowTemp = it.lowTemp,
                                    condition = it.condition, fetchedAt = Long.MAX_VALUE,
                                )
                            },
                        ),
                    high = { it.highTemp },
                    low = { it.lowTemp },
                    fetchedAt = { it.fetchedAt },
                    hindcastHigh = { it.hindcastHighTemp },
                    hindcastLow = { it.hindcastLowTemp },
                    hourlyTemps = PastDayForecastOverlay.forecastHourlyTemps(
                        dayHourly, { it.dateTime }, { it.fetchedAt }, { it.temperature },
                    ),
                    hourlyHindcastTemps = dayHourly.map { it.temperature },
                )
                forecastHigh = overlay?.high
                forecastLow = overlay?.low
                forecastIsFallback = overlay?.isFallback == true
                // A past day may have no daily_history actual row (forecast-only sources like
                // Open-Meteo, or pre-tracking days for Tomorrow.io). Fall back to the forecast
                // values so the column still labels its high/low (parity with Android; see
                // DailyDayValueResolver.resolvePastLineValues).
                val pastValues = com.weatherwidget.shared.util.DailyDayValueResolver.resolvePastLineValues(
                    actualHigh = actual?.computedHighTemp,
                    actualLow = actual?.computedLowTemp,
                    forecastHigh = forecastHigh,
                    forecastLow = forecastLow,
                )
                solidHigh = pastValues.solidHigh
                solidLow = pastValues.solidLow
                solidIsForecastFallback = pastValues.solidIsForecastFallback
                barTopHigh = solidHigh ?: forecastHigh
            }
            isToday -> {
                // A partial today row (after the DAO's complete-row swap found nothing) falls back to
                // the day's hourly max/min — shared with Android's DailyActualsEstimator.
                val sourceId = WeatherSource.fromDisplaySource(displaySourceId).id
                val zone = ZoneId.systemDefault()
                val todayHourly = hourly.filter {
                    Instant.ofEpochMilli(it.dateTime).atZone(zone).toLocalDate() == today &&
                        (it.source == null || it.source == sourceId || it.source == WeatherSource.GENERIC_GAP.id)
                }
                // Settled from sunrise + 30 min if the next 3 h are not forecast colder; at 09:00 regardless (shared with Android).
                lowSettled = com.weatherwidget.shared.util.TodayLowSettle.decide(
                    now = now,
                    lat = centerLat,
                    lon = centerLon,
                    zone = zone,
                    actualLow = actual?.computedLowTemp,
                    todayHourly = todayHourly,
                    epochMs = { it.dateTime },
                    temperature = { it.temperature },
                ).settled
                val (todayForecastHigh, todayForecastLow) = PartialForecastDays.todayForecastRange(
                    forecast?.highTemp, forecast?.lowTemp, todayHourly.map { it.temperature },
                )
                val todayValues = com.weatherwidget.shared.util.DailyDayValueResolver.resolveTodayLineValues(
                    actualHigh = actual?.computedHighTemp,
                    actualLow = actual?.computedLowTemp,
                    forecastHigh = todayForecastHigh,
                    forecastLow = todayForecastLow,
                    currentTemp = currentTemp,
                )
                solidHigh = todayValues.solidHigh
                solidLow = todayValues.solidLow
                forecastHigh = todayValues.forecastHigh
                forecastLow = todayValues.forecastLow
                ghostHigh = todayValues.ghostHigh
                barTopHigh = todayValues.barTopHigh
            }
            else -> {
                solidHigh = forecast?.highTemp
                solidLow = forecast?.lowTemp
                forecastHigh = null
                forecastLow = null
                barTopHigh = solidHigh
            }
        }

        // Rain labels (text-building logic shared with the Android widget). The daytime label sits
        // on the bar; the night label tucks between columns. The day/night precip % is chosen by the
        // shared resolveDailyLabelPrecip so desktop and Android pick identical values. Non-past days
        // use the hourly 8am–8pm / 8pm–8am window max, with the row's period fields as fallback. Past
        // days replay the value snapshotted into daily_history while the day was still live
        // (actual?.forecastDayPrecipChance / forecastNightPrecipChance), falling back to the raw
        // period fields for history written before that snapshot existed.
        // Past days have no live `forecast` row (the daily list holds only today + future), so the
        // forecast rain chance to keep visible in history comes from the day's snapshot instead —
        // only then: a live row's missing chance stays missing, never an older fetch's (Android's
        // rule; a Silurian evening batch with no night % showed an old 0 %, 2026-10-23 parity harness).
        val chanceRow = if (forecast != null) null else displaySnapshot
        val resolvedPrecip = com.weatherwidget.shared.util.DailyRainLabels.resolveDailyLabelPrecipAtSite(
            isPast = isPast,
            displaySourceId = displaySourceId,
            daytimePrecipProbability = forecast?.daytimePrecipProbability ?: chanceRow?.daytimePrecipProbability,
            nighttimePrecipProbability = forecast?.nighttimePrecipProbability ?: chanceRow?.nighttimePrecipProbability,
            precipProbability = forecast?.precipProbability ?: chanceRow?.precipProbability,
            hourly = hourly,
            centerLat = centerLat,
            centerLon = centerLon,
            targetDate = date,
            storedDayPrecipChance = actual?.forecastDayPrecipChance,
            storedNightPrecipChance = actual?.forecastNightPrecipChance,
            hourlySummary = forecast?.hourlySummary,
        )
        // Past days prefer the amount frozen into daily_history while the day was live.
        val forecastAmountMm = (if (isPast) actual?.forecastPrecipAmountMm else null)
            ?: forecast?.precipAmountMm ?: snapshot?.precipAmountMm
        val dailyRainLabelText = com.weatherwidget.shared.util.DailyRainLabels.buildDailyRainLabel(
            date = date,
            today = today,
            isPastDate = isPast,
            precipAmountMm = forecastAmountMm,
            dayPrecipProbability = resolvedPrecip.dayPrecip,
            allowTodayRainChanceLabel = allowTodayRainChanceLabel,
            observedPrecipAmountMm = com.weatherwidget.shared.util.DailyRainLabels
                .resolveObservedDayPrecip(
                    dayMm = actual?.precipDayMm,
                    nightMm = actual?.precipNightMm,
                    totalMm = actual?.precipAmountMm,
                ),
        )
        val dailyRainPeakLabelText = com.weatherwidget.shared.util.DailyRainLabels.buildDailyRainPeakLabel(
            date = date,
            today = today,
            isPastDate = isPast,
            dailyRainLabelText = dailyRainLabelText,
            dayPrecipProbability = resolvedPrecip.dayPrecip,
            dayPeakAt = resolvedPrecip.dayPeakAt,
            nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            use24HourClock = use24HourClock,
        )
        val nightRainLabelText = com.weatherwidget.shared.util.DailyRainLabels.buildNightRainLabel(
            date = date,
            today = today,
            isPastDate = isPast,
            nightPrecipProbability = resolvedPrecip.nightPrecip,
            observedNightPrecipMm = actual?.precipNightMm,
        )

        // Source-filtered noon cloud cover (shared with Android) drives both the bar split ratio and
        // the daily icon. The GENERIC_GAP exception applies only to climate-normal future days.
        val rowSourceId = if (forecast?.isClimateNormal == true) WeatherSource.GENERIC_GAP.id else null
        // Past days prefer the noon cloud % frozen into daily_history while the day was live (see
        // DailyHistoryFreeze); live derivation stays for today/future and pre-feature rows.
        // Today/future: the forecast row's own noon cloud first (DailyHourlySummaries, same as
        // Android's DailyViewLogic — hours past the 72 h store may be older than the row), else the
        // hourly noon row for rows written before it existed.
        val measuredNoonCloudPercent = com.weatherwidget.shared.util.DailyNoonCloudCover.forColumn(
            isPast = isPast,
            frozenPercent = actual?.noonCloudPercent,
            rowPercent = forecast?.noonCloudPercent,
            // The forecast snapshot holds the displayed source's rows only.
            rowIsDisplaySource = true,
            rowIsClimateNormal = forecast?.isClimateNormal == true,
        ) {
            com.weatherwidget.shared.util.DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(
                hourly = hourly,
                date = date,
                displaySourceId = displaySourceId,
                rowSourceId = rowSourceId,
            )
        }
        val rawCondition = forecast?.condition ?: actual?.condition ?: displaySnapshot?.condition
        // The column icon is Android's rule (:shared DailyIconName): the day's forecast row — today and
        // future the live row, a past day its newest stored row, as Android's newest-row-per-day read —
        // with its native token, the day/night rain chances (a rain icon both fall below becomes
        // cloud), the noon cloud % and the partly-cloudy floor; today draws night after dark. Only a
        // day with no forecast row at all falls back to the observed condition.
        val iconRow = forecast?.let { DailyIconRow(it.source ?: displaySourceId, it.iconToken, it.condition, it.precipProbability) }
            ?: snapshots.takeIf { isPast }?.maxByOrNull { it.fetchedAt }
                ?.let { DailyIconRow(displaySourceId, it.iconToken, it.condition, it.precipProbability) }
        val gatedIconName = if (iconRow != null) {
            com.weatherwidget.shared.util.DailyIconName.resolve(
                sourceId = iconRow.sourceId,
                nativeToken = iconRow.nativeToken,
                condition = iconRow.condition,
                precipProbability = iconRow.precipProbability,
                targetDate = date,
                now = now,
                latitude = centerLat,
                longitude = centerLon,
                dayPrecipProbability = resolvedPrecip.dayPrecip,
                nightPrecipProbability = resolvedPrecip.nightPrecip,
                cloudCover = measuredNoonCloudPercent,
            )
        } else {
            com.weatherwidget.shared.util.WeatherConditionResolver.resolveIconName(
                condition = actual?.condition ?: displaySnapshot?.condition,
            )
        }
        // Today's left bar ("yesterday's forecast"): its own row's chances, no cloud %, as Android.
        val snapshotIconName = when {
            isToday && displaySnapshot != null -> com.weatherwidget.shared.util.DailyIconName.resolve(
                sourceId = displaySourceId,
                nativeToken = displaySnapshot.iconToken,
                condition = displaySnapshot.condition,
                precipProbability = displaySnapshot.precipProbability,
                targetDate = date,
                now = now,
                latitude = centerLat,
                longitude = centerLon,
                dayPrecipProbability = displaySnapshot.daytimePrecipProbability ?: displaySnapshot.precipProbability,
                nightPrecipProbability = displaySnapshot.nighttimePrecipProbability,
            )
            // Only when its bar is drawn (the day's icon, as Android: `snapshotIconRes ?: iconRes` when snapshotHigh != null).
            isPast && pastPrior?.high != null -> gatedIconName
            else -> null
        }

        return DailyColumn(
            date = date,
            label = label,
            forecast = forecast,
            actual = actual,
            snapshot = displaySnapshot,
            solidHigh = solidHigh,
            solidLow = solidLow,
            forecastHigh = forecastHigh,
            forecastLow = forecastLow,
            barTopHigh = barTopHigh,
            ghostHigh = ghostHigh,
            snapshotHigh = when {
                isToday -> todayPick.highRow?.highTemp
                isPast -> pastPrior?.high
                else -> displaySnapshot?.highTemp
            },
            snapshotLow = when {
                isToday -> todayPick.lowRow?.lowTemp
                isPast -> pastPrior?.low
                else -> displaySnapshot?.lowTemp
            },
            iconCondition = rawCondition,
            iconName = gatedIconName,
            snapshotIconName = snapshotIconName,
            priorBarRainPercent = com.weatherwidget.shared.graph.AdaptiveBarStrokes.priorBarRainPercent(
                isPast = isPast,
                priorRowDaytime = todaySnapshot?.daytimePrecipProbability.takeIf { isToday },
                priorRowWholeDay = todaySnapshot?.precipProbability.takeIf { isToday },
                dayRainPercent = resolvedPrecip.dayPrecip,
            ),
            isToday = isToday,
            isPast = isPast,
            solidIsForecastFallback = solidIsForecastFallback,
            snapshotIsStale = (isPast && pastPrior?.isFallback == true) ||
                isToday && PriorDayForecast.isPickStale(todayPick, date, zone) { it.lastConfirmedAt },
            forecastIsFallback = forecastIsFallback,
            actualsFromOtherSite = isPast && !solidIsForecastFallback && actual?.isActualsBorrowed == true,
            // Null = unknown: the bar falls back to the icon's sky (AdaptiveBarStrokes).
            cloudCoverRatio = measuredNoonCloudPercent?.let { it / 100f },
            dailyRainLabelText = dailyRainLabelText,
            dailyRainPeakLabelText = dailyRainPeakLabelText,
            nightRainLabelText = nightRainLabelText,
            dayPrecipProbability = resolvedPrecip.dayPrecip,
            nightPrecipProbability = resolvedPrecip.nightPrecip,
            daysFromToday = java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt(),
            isClimateNormal = forecast?.isClimateNormal == true,
            nowHour = if (isToday) now.hour else null,
            lowSettled = lowSettled,
        )
    }
}

/** The fields of a daily forecast row the shared daily icon rule reads. */
private data class DailyIconRow(
    val sourceId: String,
    val nativeToken: String?,
    val condition: String?,
    val precipProbability: Int?,
)

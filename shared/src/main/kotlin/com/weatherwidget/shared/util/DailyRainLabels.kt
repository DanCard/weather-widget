package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.HourlyForecastSelector
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure logic for the rain labels shown on the daily-forecast graph (both the Android widget and the
 * desktop port). Produces the *text* of the daytime label (drawn on top of each bar) and the
 * nighttime label (tucked between columns); the actual Canvas/Compose drawing stays platform-side.
 *
 * Ported from the Android-only logic that lived in `DailyForecastIconResolver`,
 * `WidgetFormatUtils.formatPrecipAmount`, and `DailyViewLogic.build{Daily,Night}RainLabel` so both
 * platforms render identical strings.
 */
object DailyRainLabels {

    // ── Shared placement constants (consumed by both Android and desktop renderers) ───────────────
    // The drawing math stays platform-specific (Canvas vs Compose), but these dp values are the
    // single source of truth so the two platforms stay visually identical and a tweak is made once.

    /**
     * Gap (dp) between the day rain label's BOTTOM and the high-temp label's TOP. Negative means a
     * slight overlap (the percentage tucks onto the top of the temperature). Each platform applies
     * its own density/scale. IMPORTANT: anchor to the high label's *rendered* top — a wide temp
     * (e.g. "75.6°") is shrunk to fit a narrow column, so its real top is lower than full-size
     * metrics imply; ignoring that leaves a large floating gap.
     */
    const val RAIN_HIGH_TEMP_GAP_DP = -3f

    /**
     * The peak-hour label ("1pm") above the day rain %: its font relative to the % label's, and the
     * gap (dp) between its bottom and the % label's top (negative = tucked into the %'s ascent
     * padding, as [RAIN_HIGH_TEMP_GAP_DP]). Drawn in the % label's own blue (user, 2026-10-18).
     */
    const val RAIN_PEAK_FONT_SCALE = 0.75f
    const val RAIN_PEAK_GAP_DP = -2f

    /** Edge margin (dp) keeping a rain label inside the widget bounds. */
    const val RAIN_LABEL_EDGE_MARGIN_DP = 4f

    // Night rain label tuck (interstitial label sitting in the low-temp band between two columns).
    /** Night label font is this fraction of the day label size. */
    const val NIGHT_SCALE = 0.72f
    const val NIGHT_TUCK_ROOM_MIN_DP = 10f
    const val NIGHT_TUCK_ROOM_MAX_DP = 22f
    const val NIGHT_TUCK_OVERLAP_BASE_DP = 5.0f

    /**
     * When there is room below the low-temp band, the night rain % drops a couple px below its snug
     * tuck so it reads as its own label. Scaled by roomFraction = 1 - tightFraction, so a cramped
     * column keeps the snug tuck. Horizontal position is not offset: the label starts at the gap
     * midpoint and only moves sideways to clear an obstacle (DailyRainLabelPlanner.placeNightLabel).
     */
    const val NIGHT_TUCK_ROOMY_DOWN_DP = 2.5f

    // Night rain label collision nudge (DailyRainLabelPlanner.placeNightLabel).
    /** How far right a night label slides after sinking below both lows. */
    const val NIGHT_SINK_RIGHT_DP = 3f
    /** Search step, both axes. */
    const val NIGHT_NUDGE_STEP_DP = 1f
    /** Furthest sideways move, as a fraction of the column width. */
    const val NIGHT_NUDGE_MAX_SHIFT_FRACTION = 0.3f
    /** Furthest downward move below the home tuck. */
    const val NIGHT_NUDGE_MAX_DOWN_DP = 12f
    /** An intrusion this shallow (either axis) is a slight overlap and costs nothing. */
    const val NIGHT_SLIGHT_OVERLAP_DP = 1.5f
    /** An intrusion covering at most this fraction of the label's area is also slight. */
    const val NIGHT_SLIGHT_OVERLAP_AREA_FRACTION = 0.15f
    /** With at least this much room under the label, it prefers moving down to moving sideways. */
    const val NIGHT_ROOMY_BELOW_DP = 12f
    /** Sideways movement costs this many times a move down when there is lots of room below. */
    const val NIGHT_ROOMY_SIDEWAYS_COST = 3f
    /** A night label this close to a neighbour, or overlapping it, is hugging it. */
    const val NIGHT_HUG_DP = 1.5f
    /** A hugging night label eases away only with at least this much free on the other side. */
    const val NIGHT_ROOMY_SIDE_DP = 2.5f
    /** Furthest a hugging night label drops to reach room on its left. */
    const val NIGHT_EASE_MAX_DROP_DP = 4f
    /** How far left a night label slides after that drop. */
    const val NIGHT_EASE_DROP_SLIDE_DP = 0.5f
    /**
     * The raised ° of a low-temp label fills only this top fraction of the label's text box; the
     * pocket under it is blank, so a night label may sit there (DailyRainLabelPlanner.lowLabelObstacles).
     */
    const val DEGREE_SIGN_HEIGHT_FRACTION = 0.5f

    // ── Rain-label font scaling (shared so Android and desktop size labels identically) ───────────

    /** Distance-scale slope: how aggressively far-out low-confidence labels shrink. */
    const val RAIN_FONT_SCALE_K = 0.6f
    /** Distance normalizer (days); the distance term saturates around this horizon. */
    const val RAIN_FONT_SCALE_MAX_DAYS = 7f
    /** Floor on the day count fed to the distance term so today/tomorrow aren't over-shrunk. */
    const val RAIN_FONT_DISTANCE_MIN_DAYS = 1.5f

    /**
     * Probability→font-scale step table for a rain label (0.3 at a trace chance up to 1.0 above ~64%).
     * Higher chance ⇒ larger label. Shared by the daily rain labels and the header precip text so the
     * two never drift.
     */
    fun precipProbabilityScaleFactor(precipProbability: Int): Float = when {
        precipProbability <= 1  -> 0.3f
        precipProbability <= 2  -> 0.4f
        precipProbability <= 4  -> 0.5f
        precipProbability <= 8  -> 0.6f
        precipProbability <= 16 -> 0.7f
        precipProbability <= 32 -> 0.8f
        precipProbability <= 64 -> 0.9f
        else                    -> 1.0f
    }

    /** Platform-neutral header scale; renderers apply their own base text size and UI scale. */
    fun headerPrecipFontScale(
        precipProbability: Int,
        isDailyView: Boolean,
        isNightPrecip: Boolean,
    ): Float {
        val nightScale = if (isDailyView && isNightPrecip) NIGHT_SCALE else 1f
        return precipProbabilityScaleFactor(precipProbability) * nightScale
    }

    /**
     * Font *scale* (a multiplier on the renderer's base rain-label text size) for a daily rain label.
     * - **Future / today:** probability-weighted AND distance-weighted — a far-out low-confidence
     *   drizzle shrinks, a near-term or near-certain day stays near full size.
     * - **Past (history):** probability-weighted ONLY — a settled day has no "days into the future",
     *   so the distance term is dropped and history sizes exactly like a same-probability future day
     *   at zero distance.
     *
     * Night labels multiply the result by [NIGHT_SCALE] at the call site; each renderer also applies
     * its own base size and any reduced-fit shrink.
     */
    fun rainLabelFontScale(
        isPastDate: Boolean,
        precipProbability: Int?,
        daysFromToday: Int,
    ): Float {
        val prob = precipProbability ?: 0
        val probScale = precipProbabilityScaleFactor(prob)
        if (isPastDate) return probScale
        val probFraction = prob.toFloat() / 100f
        val effectiveDays = daysFromToday.toFloat().coerceAtLeast(RAIN_FONT_DISTANCE_MIN_DAYS)
        val distanceScale = 1f - RAIN_FONT_SCALE_K * (1f - probFraction) * (effectiveDays / RAIN_FONT_SCALE_MAX_DAYS)
        return probScale * distanceScale
    }

    /**
     * Max precip probability over the daytime (8am–8pm) and nighttime (8pm–8am) windows.
     * [dayPeakAt] is the epoch-ms start of the first hour holding [dayMax] (a plateau's first hour).
     */
    data class DayNightPrecip(
        val dayMax: Int?,
        val nightMax: Int?,
        val dayPeakAt: Long? = null,
    )

    /**
     * Minimum daytime precip probability before a forecast day shows a rain label/icon. Scales with
     * distance so far-out low-confidence drizzle is suppressed: `4 * daysFromToday + 1`.
     */
    fun getMinimumPrecipProbabilityDay(daysFromToday: Int): Int = (4 * daysFromToday) + 1

    /** Nighttime threshold mirrors the daytime one. */
    fun getMinimumPrecipProbabilityNight(daysFromToday: Int): Int = getMinimumPrecipProbabilityDay(daysFromToday)

    /**
     * Formats a precip amount in mm as a compact string. US/GB locales get inches (".08in"),
     * everyone else gets millimeters ("3mm"). Leading zeros are trimmed.
     */
    fun formatPrecipAmount(amountMm: Float): String {
        val country = Locale.getDefault().country.uppercase(Locale.US)
        return if (country == "US" || country == "GB") {
            formatInches(amountMm / 25.4f)
        } else {
            formatMillimeters(amountMm)
        }
    }

    /**
     * [formatPrecipAmount] for an amount that was **observed**: a total of traces only reads
     * "Trace" ([PrecipTrace]). Forecast amounts never take this path — a tiny forecast is "<.001in".
     */
    fun formatObservedPrecipAmount(amountMm: Float): String =
        if (PrecipTrace.isTrace(amountMm)) PrecipTrace.LABEL else formatPrecipAmount(amountMm)

    private fun formatInches(amountInches: Float): String {
        val precision = when {
            amountInches < 0.01f -> 3
            amountInches < 0.1f -> 3
            amountInches < 1f -> 2
            else -> 1
        }
        return compact(amountInches, precision, smallest = ".001", unit = "in")
    }

    private fun formatMillimeters(amountMm: Float): String {
        val precision = if (amountMm < 10f) 1 else 0
        return compact(amountMm, precision, smallest = "0.1", unit = "mm")
    }

    /**
     * Trailing and leading zeros trimmed (".08in", "3mm"). An amount that rounds away entirely used
     * to trim to nothing and print a bare unit — "Pred in" on the Pixel's precip graph (2026-10-20)
     * for a 0.0004in total. Zero now reads "0in"; anything above zero but below the display
     * precision reads "<.001in" / "<0.1mm". Zeros are trimmed only after a decimal point.
     */
    private fun compact(value: Float, precision: Int, smallest: String, unit: String): String {
        if (value <= 0f) return "0$unit"
        val fixed = String.format(Locale.US, "%.${precision}f", value)
        // Only fractional zeros are padding: "20" is twenty, not "2" (whole-mm amounts ≥ 10mm used to
        // print "2mm" for 20mm).
        val trimmed = if ('.' in fixed) fixed.trimEnd('0').trimEnd('.') else fixed
        val formatted = trimmed.removePrefix("0")
        return if (formatted.isEmpty()) "<$smallest$unit" else "$formatted$unit"
    }

    /**
     * Computes the max precip probability over the daytime (8am–8pm) and nighttime (8pm–8am next
     * day) windows for [targetDate], reading from hourly rows of [displaySourceId] (falling back to
     * [fallbackSourceId] only if the display source has no rows at all).
     */
    fun calculateDayNightPrecipProbabilities(
        hourly: List<HourlyForecast>,
        targetDate: LocalDate,
        displaySourceId: String,
        fallbackSourceId: String = WeatherSource.GENERIC_GAP.id,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DayNightPrecip {
        val sourceForecasts = hourly.filter { it.source == displaySourceId }
        val candidates = if (sourceForecasts.isNotEmpty()) {
            sourceForecasts
        } else {
            hourly.filter { it.source == fallbackSourceId }
        }
        return periodMaxima(candidates, targetDate, zoneId)
    }

    /**
     * Max precip probability over the daytime (8am–8pm) and nighttime (8pm–8am next day) windows of
     * [targetDate], over [rows] as given (no source selection). The one window definition, used by
     * the display ([calculateDayNightPrecipProbabilities]) and by storage ([DailyPrecipPeriods]).
     */
    fun periodMaxima(
        rows: List<HourlyForecast>,
        targetDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DayNightPrecip {
        val dayStartMs = targetDate.atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
        val dayEndMs = targetDate.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli()
        val nightEndMs = targetDate.plusDays(1).atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
        fun maxIn(start: Long, end: Long) = rows
            .filter { it.dateTime in start until end }
            .mapNotNull { it.precipProbability }
            .maxOrNull()
        val dayMax = maxIn(dayStartMs, dayEndMs)
        val dayPeakAt = dayMax?.let { max ->
            rows.filter { it.dateTime in dayStartMs until dayEndMs && it.precipProbability == max }
                .minOfOrNull { it.dateTime }
        }
        return DayNightPrecip(dayMax = dayMax, nightMax = maxIn(dayEndMs, nightEndMs), dayPeakAt = dayPeakAt)
    }

    /**
     * The day/night precip % chosen for the daily label + icon (see [resolveDailyLabelPrecip]).
     * [dayPeakAt] is when [dayPrecip] peaks — set only when [dayPrecip] came from hourly data (the
     * row's stored max or the stored hours), never for a provider period value or a past day.
     */
    data class ResolvedDailyPrecip(
        val dayPrecip: Int?,
        val nightPrecip: Int?,
        val dayPeakAt: Long? = null,
    )

    /**
     * The day/night precip % for a currently-live (non-past) date — the hourly 8am–8pm / 8pm–8am
     * window max ([calculateDayNightPrecipProbabilities]), falling back to the row's period fields
     * only when there are no hourly rows. This is deliberate for NWS too: NWS's native 12-hour
     * periods run 6am/6pm, so its "tonight" chance excludes 6–8am rain that the app's 8pm–8am night
     * window (and users) consider part of tonight — e.g. a 14% chance at 7am showed as 9%.
     *
     * Used both for the live daily label ([resolveDailyLabelPrecip]) and to snapshot the displayed
     * value into daily_history while a day is still current, so history can later replay exactly
     * what was shown instead of recomputing from hindcast hourly rows (see
     * [com.weatherwidget.data.model.DailyHistory.forecastDayPrecipChance]).
     */
    /**
     * [resolveLiveDayNightChance] for a caller holding RAW hourly rows straight from the persistence
     * layer — i.e. every row the [LocationMatch][com.weatherwidget.data.local.LocationMatch]
     * proximity box gathered, including GPS-jitter fragments left at old fetch coordinates.
     *
     * Collapses those to the user's actual site (freshest row per hour) via [HourlyForecastSelector]
     * before taking the window max. Skipping this step is what produced the 2026-07-13 divergence:
     * the daily-history freeze read raw box rows and its `maxOrNull()` picked up a neighbouring
     * fragment's 9% while the real site (37.417,-122.089) said 4% — so the Samsung's daily bar
     * showed 9% for yesterday while its own hourly graph, which goes through the selector, showed 5%.
     * `max` is the worst possible reducer to run over a poisoned set: one bad fragment wins outright.
     *
     * Every display path already selects a site; snapshot/freeze paths must too, or the archive
     * disagrees with the graph drawn from the same database.
     */
    fun resolveLiveDayNightChanceAtSite(
        displaySourceId: String,
        daytimePrecipProbability: Int?,
        nighttimePrecipProbability: Int?,
        precipProbability: Int?,
        hourly: List<HourlyForecast>,
        centerLat: Double,
        centerLon: Double,
        targetDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        hourlySummary: DailyHourlySummaries.Summary? = null,
    ): ResolvedDailyPrecip {
        val sited = selectSiteHourly(hourly, displaySourceId, centerLat, centerLon)
        return resolveLiveDayNightChance(
            displaySourceId = displaySourceId,
            daytimePrecipProbability = daytimePrecipProbability,
            nighttimePrecipProbability = nighttimePrecipProbability,
            precipProbability = precipProbability,
            hourly = sited,
            targetDate = targetDate,
            zoneId = zoneId,
            hourlySummary = hourlySummary,
        )
    }

    /**
     * One row per hour for [displaySourceId] at the site centred on ([centerLat], [centerLon]),
     * preserving [calculateDayNightPrecipProbabilities]'s GENERIC_GAP fallback (a source with no
     * hourly rows of its own falls back to the climate-normal filler) — but resolving the site
     * first, so the fallback can't be triggered or poisoned by a neighbouring fragment.
     */
    fun selectSiteHourly(
        hourly: List<HourlyForecast>,
        displaySourceId: String,
        centerLat: Double,
        centerLon: Double,
    ): List<HourlyForecast> {
        val sourceRows = HourlyForecastSelector
            .selectForecastsByTime(hourly, displaySourceId, centerLat, centerLon)
            .values.toList()
        if (sourceRows.isNotEmpty()) return sourceRows
        return HourlyForecastSelector
            .selectForecastsByTime(hourly, WeatherSource.GENERIC_GAP.id, centerLat, centerLon)
            .values.toList()
    }

    fun resolveLiveDayNightChance(
        displaySourceId: String,
        daytimePrecipProbability: Int?,
        nighttimePrecipProbability: Int?,
        precipProbability: Int?,
        hourly: List<HourlyForecast>,
        targetDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        hourlySummary: DailyHourlySummaries.Summary? = null,
    ): ResolvedDailyPrecip {
        // The forecast row's own hourly max (DailyHourlySummaries, kept at fetch time from the whole
        // download), else the stored hours' window max for rows written before it existed.
        val sourceRows = hourly.filter { it.source == displaySourceId }
        val dayNight = DailyHourlySummaries.liveDayNight(
            sitedRows = sourceRows.ifEmpty { hourly.filter { it.source == WeatherSource.GENERIC_GAP.id } },
            date = targetDate,
            storedDayMax = hourlySummary?.dayPrecipMax,
            storedNightMax = hourlySummary?.nightPrecipMax,
            storedDayPeakAt = hourlySummary?.dayPrecipPeakAt,
            zoneId = zoneId,
        )
        return ResolvedDailyPrecip(
            dayPrecip = dayNight.dayMax ?: daytimePrecipProbability ?: precipProbability,
            nightPrecip = dayNight.nightMax ?: nighttimePrecipProbability,
            dayPeakAt = dayNight.dayPeakAt.takeIf { dayNight.dayMax != null },
        )
    }

    /**
     * Picks the day/night precip % shown on the daily label and used for the daily icon — the single
     * source of truth shared by Android and desktop so the two never drift (e.g. today showing 15% on
     * one and 2% on the other).
     *
     * Past days prefer the value snapshotted into daily_history while the day was current
     * ([storedDayPrecipChance]/[storedNightPrecipChance]), falling back to the row's raw period
     * fields for history written before that snapshot existed. Non-past days always use
     * [resolveLiveDayNightChance].
     */
    fun resolveDailyLabelPrecip(
        isPast: Boolean,
        displaySourceId: String,
        daytimePrecipProbability: Int?,
        nighttimePrecipProbability: Int?,
        precipProbability: Int?,
        hourly: List<HourlyForecast>,
        targetDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        storedDayPrecipChance: Int? = null,
        storedNightPrecipChance: Int? = null,
        hourlySummary: DailyHourlySummaries.Summary? = null,
    ): ResolvedDailyPrecip {
        if (isPast) {
            // Past days use the raw day/night period split with NO precipProbability→daytime fallback:
            // an NWS night-only chance (daytime=null, precipProbability=15) would otherwise surface as
            // a spurious daytime label on the bar. It also keeps Android (source-tagged row present)
            // and desktop (forecast row absent for past days) on the same path.
            return ResolvedDailyPrecip(
                dayPrecip = storedDayPrecipChance ?: daytimePrecipProbability,
                nightPrecip = storedNightPrecipChance ?: nighttimePrecipProbability,
            )
        }
        return resolveLiveDayNightChance(
            displaySourceId = displaySourceId,
            daytimePrecipProbability = daytimePrecipProbability,
            nighttimePrecipProbability = nighttimePrecipProbability,
            precipProbability = precipProbability,
            hourly = hourly,
            targetDate = targetDate,
            zoneId = zoneId,
            hourlySummary = hourlySummary,
        )
    }

    /**
     * Site-aware counterpart to [resolveDailyLabelPrecip] for display callers holding raw hourly
     * persistence rows. Past-day snapshot semantics remain identical; only live today/future
     * windows need site selection before their maximum is calculated.
     */
    fun resolveDailyLabelPrecipAtSite(
        isPast: Boolean,
        displaySourceId: String,
        daytimePrecipProbability: Int?,
        nighttimePrecipProbability: Int?,
        precipProbability: Int?,
        hourly: List<HourlyForecast>,
        centerLat: Double,
        centerLon: Double,
        targetDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
        storedDayPrecipChance: Int? = null,
        storedNightPrecipChance: Int? = null,
        hourlySummary: DailyHourlySummaries.Summary? = null,
    ): ResolvedDailyPrecip {
        if (isPast) {
            return resolveDailyLabelPrecip(
                isPast = true,
                displaySourceId = displaySourceId,
                daytimePrecipProbability = daytimePrecipProbability,
                nighttimePrecipProbability = nighttimePrecipProbability,
                precipProbability = precipProbability,
                hourly = hourly,
                targetDate = targetDate,
                zoneId = zoneId,
                storedDayPrecipChance = storedDayPrecipChance,
                storedNightPrecipChance = storedNightPrecipChance,
            )
        }
        return resolveLiveDayNightChanceAtSite(
            displaySourceId = displaySourceId,
            daytimePrecipProbability = daytimePrecipProbability,
            nighttimePrecipProbability = nighttimePrecipProbability,
            precipProbability = precipProbability,
            hourly = hourly,
            centerLat = centerLat,
            centerLon = centerLon,
            targetDate = targetDate,
            zoneId = zoneId,
            hourlySummary = hourlySummary,
        )
    }

    /**
     * Picks the observed amount the *daytime* label should show, given a `daily_history` row's
     * three precip fields.
     *
     * The subtlety is what a null [dayMm] means, and it is not always the same thing:
     * - When [nightMm] is also null, no day/night split was ever computed for the row (pre-split
     *   rows, and sources that only ever report a daily total). The day label is then the only
     *   place the amount can appear, so it shows [totalMm].
     * - When [nightMm] is non-null, the split *was* computed and [dayMm] null means the daytime
     *   window measured no rain. Falling back to [totalMm] there prints the night's rain a second
     *   time, on the day label — observed 2026-09-04 on the emulator, where Sept 3 rendered
     *   `.043in` for both day and night off `total=1.1, day=null, night=1.1` (every NWS reading
     *   with a non-null amount was between 22:31 and 23:55). Callers used to write
     *   `precipDayMm ?: precipAmountMm` inline on both platforms, which is exactly that bug.
     *
     * Returning null here lets [buildDailyRainLabel]'s past-day branch fall through to the
     * forecast chance%, which is the honest thing to show for a dry daytime.
     */
    fun resolveObservedDayPrecip(dayMm: Float?, nightMm: Float?, totalMm: Float?): Float? =
        dayMm ?: totalMm?.takeIf { nightMm == null }

    /**
     * Daytime rain label drawn on top of a day's bar.
     * - Past days: observed amount when measurable rain fell, else the forecast chance% (so a real
     *   forecast doesn't silently vanish the moment the day turns into history), else null.
     * - Today: amount when day-prob ≥ 95% (and amount known), else day-prob% (if allowed), else null.
     * - Future: suppressed below the distance-scaled threshold; else amount when prob ≥ 99%, else prob%.
     */
    fun buildDailyRainLabel(
        date: LocalDate,
        today: LocalDate,
        isPastDate: Boolean,
        precipAmountMm: Float?,
        dayPrecipProbability: Int?,
        allowTodayRainChanceLabel: Boolean,
        observedPrecipAmountMm: Float?,
    ): String? {
        if (isPastDate) {
            if (observedPrecipAmountMm != null && observedPrecipAmountMm > 0f) {
                return formatObservedPrecipAmount(observedPrecipAmountMm)
            }
            // No measurable rain fell, but keep the forecasted chance visible in history.
            if (dayPrecipProbability != null && dayPrecipProbability > 0) {
                return "$dayPrecipProbability%"
            }
            return null
        }
        if (date == today) {
            if (dayPrecipProbability != null && dayPrecipProbability >= 95 && precipAmountMm != null) {
                return formatPrecipAmount(precipAmountMm)
            }
            if (allowTodayRainChanceLabel && dayPrecipProbability != null && dayPrecipProbability > 0) {
                return "$dayPrecipProbability%"
            }
            return null
        }
        val daysFromToday = ChronoUnit.DAYS.between(today, date).toInt()
        val dayMinProb = getMinimumPrecipProbabilityDay(daysFromToday)
        val dayPrecip = dayPrecipProbability

        if (dayPrecip != null && dayPrecip < dayMinProb) {
            return null
        }
        return when {
            dayPrecip != null && dayPrecip >= 99 && precipAmountMm != null -> formatPrecipAmount(precipAmountMm)
            dayPrecip != null && dayPrecip > 0 -> "$dayPrecip%"
            else -> null
        }
    }

    /**
     * Nighttime rain label tucked between columns.
     * - Past days: observed night amount when measurable rain fell, else the forecast night chance%
     *   (so a real forecast doesn't silently vanish once the night turns into history), else null.
     * - Future: night-prob% when above the distance-scaled threshold, else null.
     */
    fun buildNightRainLabel(
        date: LocalDate,
        today: LocalDate,
        isPastDate: Boolean,
        nightPrecipProbability: Int?,
        observedNightPrecipMm: Float?,
    ): String? {
        if (isPastDate) {
            if (observedNightPrecipMm != null && observedNightPrecipMm > 0f) {
                return formatObservedPrecipAmount(observedNightPrecipMm)
            }
            // No measurable rain fell, but keep the forecasted night chance visible in history.
            if (nightPrecipProbability != null && nightPrecipProbability > 0) {
                return "$nightPrecipProbability%"
            }
            return null
        }
        val probability = nightPrecipProbability ?: return null
        val daysFromToday = ChronoUnit.DAYS.between(today, date).toInt()
        if (daysFromToday < 0) return null

        val threshold = getMinimumPrecipProbabilityNight(daysFromToday)
        if (probability < threshold) return null

        return "$probability%"
    }

    /** Lowest daytime chance (exclusive) that gets its peak hour written above the % (user, 2026-10-18). */
    const val PEAK_HOUR_MIN_EXCLUSIVE_PERCENT = 30

    /**
     * The hour the daytime chance peaks ("1pm" / "13:00"), drawn above the day rain label.
     * Only when that label shows a percentage above [PEAK_HOUR_MIN_EXCLUSIVE_PERCENT] — so never on
     * past days, nor when the label is an amount (≥ 95 % today / ≥ 99 % later) — and, for today,
     * only until the peak hour is over. [dayPeakAt] is [ResolvedDailyPrecip.dayPeakAt].
     */
    fun buildDailyRainPeakLabel(
        date: LocalDate,
        today: LocalDate,
        isPastDate: Boolean,
        dailyRainLabelText: String?,
        dayPrecipProbability: Int?,
        dayPeakAt: Long?,
        nowMs: Long,
        use24HourClock: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? {
        if (isPastDate || date.isBefore(today)) return null
        if (dailyRainLabelText == null || !dailyRainLabelText.endsWith("%")) return null
        val probability = dayPrecipProbability ?: return null
        if (probability <= PEAK_HOUR_MIN_EXCLUSIVE_PERCENT || probability >= 100) return null
        val peakAt = dayPeakAt ?: return null
        if (date == today && nowMs >= peakAt + HOUR_MS) return null
        val time = Instant.ofEpochMilli(peakAt).atZone(zoneId).toLocalTime()
        return formatPeakHour(time.hour, use24HourClock, locale)
    }

    /** "1pm" on a 12-hour clock (as the hourly view's labels); the locale's short time ("13:00") on 24. */
    fun formatPeakHour(hour: Int, use24HourClock: Boolean, locale: Locale = Locale.getDefault()): String =
        if (use24HourClock) {
            java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
                .withLocale(locale)
                .format(java.time.LocalTime.of(hour, 0))
        } else {
            HourLabelFormatter.hourLabel(hour) + "m"
        }

    /** True when [locale]'s short time pattern is a 24-hour clock (no `h`/`K` hour field). */
    fun localeUses24HourClock(locale: Locale = Locale.getDefault()): Boolean {
        val pattern = java.time.format.DateTimeFormatterBuilder.getLocalizedDateTimePattern(
            null,
            java.time.format.FormatStyle.SHORT,
            java.time.chrono.IsoChronology.INSTANCE,
            locale,
        )
        return 'h' !in pattern && 'K' !in pattern
    }

    private const val HOUR_MS = 3_600_000L
}

package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.widget.CurrentTemperatureResolver
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * What the header row shows — icon, current temp, delta from yesterday, rain chance, source label —
 * for Android's daily and hourly headers and desktop's, from one rule. Width fitting and layout stay
 * per platform; nothing here measures text. Each platform used to pick these inputs itself, and every
 * desktop header bug was a different input fed to the same helper (an NWS station's "CLR" for the
 * icon, `now` instead of the panned hour for the rain %, …): `plans/261021-header-shared-content-audit.md`.
 */
object HeaderContent {
    const val LOG_TAG = "HEADER_CONTENT"


    /** The view whose header is drawn; the hourly views differ only in the delta and 0 % rules. */
    enum class View {
        DAILY, TEMPERATURE, PRECIPITATION, CLOUD_COVER;

        val isHourly: Boolean get() = this != DAILY
    }

    /** A delta from yesterday smaller than this (°F) is noise and is not shown. */
    const val DELTA_VISIBILITY_THRESHOLD = 0.1f

    /** Today's daily row, for the icon when the displayed source has no hourly row for the hour. */
    data class DailyFallback(
        val sourceId: String,
        val nativeToken: String?,
        val condition: String?,
        val precipProbability: Int?,
    )

    data class Content(
        /** Null in an hourly view whose source has no row for this hour: no icon is drawn. */
        val iconName: String?,
        /** "62.7°", or null with no current temp. */
        val tempText: String?,
        /** "+2.9" in display units, or null when hidden. */
        val deltaText: String?,
        /** The rain chance shown, or null when hidden. */
        val precipProbability: Int?,
        /** The rain chance before the 0 % rule (Android's daily renderers read it). */
        val computedPrecipProbability: Int?,
        /** "85%", plus the nowcast in the daily view ("85% 14:15–"); null when hidden. */
        val precipText: String?,
        /** Multiplier on the platform's base rain-text size; null when hidden. */
        val precipFontScale: Float?,
        val isNightPrecip: Boolean,
        /** "Meteo"; an hourly view panned off today names the day ("Thursday • Meteo"). */
        val sourceLabel: String,
    )

    @Suppress("LongParameterList")
    fun build(
        view: View,
        hourly: List<HourlyForecast>,
        displaySourceId: String,
        dailyFallback: DailyFallback?,
        fallbackDailyPrecip: Int?,
        currentTemp: Float?,
        isStaleEstimate: Boolean,
        numColumns: Int,
        deltaFromYesterday: Float?,
        now: LocalDateTime,
        /** The hourly view's panned centre hour; ignored by the daily view. */
        centerTime: LocalDateTime,
        lat: Double,
        lon: Double,
        useCelsius: Boolean,
        widthDp: Int,
        /** Rows for the icon when they differ from [hourly] (Android's current-temp series). */
        iconHourly: List<HourlyForecast> = hourly,
        /**
         * The source to name, when it is not the one [displaySourceId]'s data came from: desktop's
         * label moves on the click, the rest when that source's data arrives.
         */
        labelSourceId: String = displaySourceId,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Content {
        val precip = precipitation(view, hourly, displaySourceId, fallbackDailyPrecip, now, centerTime, lat, lon)
        val precipText = precip.probability?.let { "$it%" }
        return Content(
            iconName = iconName(view, iconHourly, displaySourceId, dailyFallback, now, lat, lon, zone),
            tempText = currentTemp?.let {
                CurrentTemperatureResolver.formatDisplayTemperature(it, numColumns, isStaleEstimate, useCelsius)
            },
            deltaText = deltaText(view, currentTemp, deltaFromYesterday, useCelsius),
            precipProbability = precip.probability,
            computedPrecipProbability = precip.computed,
            precipText = precipText,
            precipFontScale = precip.probability?.let {
                DailyRainLabels.headerPrecipFontScale(it, isDailyView = view == View.DAILY, isNightPrecip = precip.isNight)
            },
            isNightPrecip = precip.isNight,
            sourceLabel = sourceLabel(view, labelSourceId, now, centerTime, widthDp),
        )
    }

    /** The [LOG_TAG] line, both platforms: what the header shows, and the source its data came from. */
    fun logLine(dataSourceId: String, view: View, content: Content): String =
        "source=$dataSourceId view=$view icon=${content.iconName} temp=${content.tempText} " +
            "delta=${content.deltaText} precip=${content.precipText} label=${content.sourceLabel}"

    /** "Meteo"; an hourly view panned off today names the day ("Thursday • Meteo"). */
    fun sourceLabel(view: View, sourceId: String, now: LocalDateTime, centerTime: LocalDateTime, widthDp: Int): String {
        val sourceName = WeatherSource.fromId(sourceId).shortDisplayName
        return if (view.isHourly) HeaderFormatter.formatSourceIndicator(centerTime, now, sourceName, widthDp) else sourceName
    }

    /**
     * Each of [sourceIds]' label and the rain % it would show now, for [HeaderLine]'s reserve.
     * [hourly] holds every source's rows.
     */
    @Suppress("LongParameterList")
    fun lineInputs(
        view: View,
        hourly: List<HourlyForecast>,
        sourceIds: List<String>,
        now: LocalDateTime,
        centerTime: LocalDateTime,
        lat: Double,
        lon: Double,
        widthDp: Int,
    ): HeaderLine.Inputs = HeaderLine.Inputs(
        sourceLabels = sourceIds.map { sourceLabel(view, it, now, centerTime, widthDp) },
        rainLabels = precipLabels(view, hourly, sourceIds, now, centerTime, lat, lon).values.filterNotNull(),
    )

    /** A rain % as [build] would show it: its text and font-size multiplier. */
    data class PrecipLabel(val text: String, val fontScale: Float)

    /**
     * The rain % [build] would show for each of [sourceIds] (null where hidden), by the same rule,
     * so a header can reserve room for the widest and a source tap does not move what sits beside
     * it (user, 2026-10-26). No daily fallback: [hourly] holds every source's live rows.
     */
    fun precipLabels(
        view: View,
        hourly: List<HourlyForecast>,
        sourceIds: List<String>,
        now: LocalDateTime,
        centerTime: LocalDateTime,
        lat: Double,
        lon: Double,
    ): Map<String, PrecipLabel?> = sourceIds.associateWith { sourceId ->
        val precip = precipitation(view, hourly, sourceId, null, now, centerTime, lat, lon)
        precip.probability?.let {
            PrecipLabel(
                text = "$it%",
                fontScale = DailyRainLabels.headerPrecipFontScale(it, isDailyView = view == View.DAILY, isNightPrecip = precip.isNight),
            )
        }
    }

    /**
     * The displayed source's coming hour (daily) or this hour (hourly views, with twilight); see
     * [HeaderConditionIcon]. With no such row the daily view draws today's daily row
     * ([DailyIconName]); an hourly view draws no icon rather than a "?" or another source's guess.
     */
    fun iconName(
        view: View,
        hourly: List<HourlyForecast>,
        displaySourceId: String,
        dailyFallback: DailyFallback?,
        now: LocalDateTime,
        lat: Double,
        lon: Double,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val row = HeaderConditionIcon.pickRow(hourly, displaySourceId, now, preferNextHour = !view.isHourly, zone = zone)
        if (row != null) return HeaderConditionIcon.iconName(row, now, lat, lon, withTwilight = view.isHourly)
        if (view.isHourly) return null
        val daily = dailyFallback ?: return WeatherConditionResolver.IC_UNKNOWN
        return DailyIconName.resolve(
            sourceId = daily.sourceId,
            nativeToken = daily.nativeToken,
            condition = daily.condition,
            precipProbability = daily.precipProbability,
            targetDate = now.toLocalDate(),
            now = now,
            latitude = lat,
            longitude = lon,
        )
    }

    /** Shown in the daily and temperature headers only; the precip and cloud views have no delta. */
    fun deltaText(view: View, currentTemp: Float?, deltaFromYesterday: Float?, useCelsius: Boolean): String? {
        if (view == View.PRECIPITATION || view == View.CLOUD_COVER) return null
        if (currentTemp == null || deltaFromYesterday == null || abs(deltaFromYesterday) < DELTA_VISIBILITY_THRESHOLD) return null
        return String.format("%+.1f", if (useCelsius) deltaFromYesterday / 1.8f else deltaFromYesterday)
    }

    private data class Precip(val probability: Int?, val computed: Int?, val isNight: Boolean)

    /**
     * Daily: the next 6 h from now, with the day/night split that sizes it. Hourly views: the next
     * 6 h from the panned hour. Hidden at 0 % except in the precip view, where 0 % confirms "dry".
     */
    private fun precipitation(
        view: View,
        hourly: List<HourlyForecast>,
        displaySourceId: String,
        fallbackDailyPrecip: Int?,
        now: LocalDateTime,
        centerTime: LocalDateTime,
        lat: Double,
        lon: Double,
    ): Precip {
        val gapId = WeatherSource.GENERIC_GAP.id
        val (probability, isNight) = if (view == View.DAILY) {
            val sun = SunPositionUtils.getSunInfoOrUnknown(now, lat, lon).sunTimes
            val p = PrecipProbabilityCalculator.resolveHeaderPrecipitation(
                hourly, displaySourceId, gapId, fallbackDailyPrecip, now, sun.sunriseHour, sun.sunsetHour,
            )
            p.probability to p.isPredominantlyNight
        } else {
            PrecipProbabilityCalculator.getNext6HourPrecipProbability(
                hourly, displaySourceId, gapId, fallbackDailyPrecip, centerTime,
            ) to false
        }
        val shown = if (view == View.PRECIPITATION) probability else probability?.takeIf { it > 0 }
        return Precip(shown, probability, isNight && shown != null)
    }
}

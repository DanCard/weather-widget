package com.weatherwidget.shared.util

/**
 * Pure-Kotlin resolver that maps weather condition text to canonical icon names,
 * derives boolean flags (sunny/rainy/mixed/night/twilight), computes cloud ratios,
 * and classifies icons for graph rendering.
 *
 * Returns icon **name** strings (e.g. `"ic_weather_rain"`). Each platform maps
 * these to its own resource type (`R.drawable.*` on Android, Compose resource paths on desktop).
 */
object WeatherConditionResolver {

    // ── Icon name constants ───────────────────────────────────────────────

    const val IC_UNKNOWN = "ic_weather_unknown"
    const val IC_CLEAR = "ic_weather_clear"
    const val IC_NIGHT = "ic_weather_night"
    const val IC_MOSTLY_CLEAR = "ic_weather_mostly_clear"
    const val IC_PARTLY_CLOUDY = "ic_weather_partly_cloudy"
    const val IC_PARTLY_CLOUDY_NIGHT = "ic_weather_partly_cloudy_night"
    const val IC_MOSTLY_CLOUDY = "ic_weather_mostly_cloudy"
    const val IC_MOSTLY_CLOUDY_NIGHT = "ic_weather_mostly_cloudy_night"
    const val IC_CLOUDY = "ic_weather_cloudy"
    const val IC_RAIN = "ic_weather_rain"
    const val IC_STORM = "ic_weather_storm"
    const val IC_SNOW = "ic_weather_snow"
    const val IC_FOG = "ic_weather_fog"
    const val IC_FOG_DENSE = "ic_weather_fog_dense"
    const val IC_FOG_LIGHT = "ic_weather_fog_light"
    const val IC_FOG_LIGHT_NIGHT = "ic_weather_fog_light_night"
    const val IC_FOG_NIGHT = "ic_weather_fog_night"
    const val IC_FOG_SUNNY = "ic_weather_fog_sunny"
    const val IC_FOG_CLOUDY = "ic_weather_fog_cloudy"
    const val IC_WIND = "ic_weather_wind"
    const val IC_HORIZON_SUN = "ic_weather_horizon_sun"
    const val IC_PARTLY_CLOUDY_CHANCE_RAIN = "ic_weather_partly_cloudy_chance_rain"
    const val IC_PARTLY_CLOUDY_CHANCE_RAIN_NIGHT = "ic_weather_partly_cloudy_chance_rain_night"
    const val IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN = "ic_weather_partly_cloudy_slight_chance_rain"
    const val IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN_NIGHT = "ic_weather_partly_cloudy_slight_chance_rain_night"
    const val IC_CLOUDY_CHANCE_RAIN = "ic_weather_cloudy_chance_rain"
    const val IC_CLOUDY_SLIGHT_CHANCE_RAIN = "ic_weather_cloudy_slight_chance_rain"

    // ── Icon sets for classification ──────────────────────────────────────

    private val PRECIPITATION_ICONS = setOf(IC_RAIN, IC_STORM, IC_SNOW)

    private val RAIN_INDICATOR_ICONS = PRECIPITATION_ICONS + setOf(
        IC_PARTLY_CLOUDY_CHANCE_RAIN,
        IC_PARTLY_CLOUDY_CHANCE_RAIN_NIGHT,
        IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN,
        IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN_NIGHT,
        IC_CLOUDY_CHANCE_RAIN,
        IC_CLOUDY_SLIGHT_CHANCE_RAIN,
    )

    private val MIXED_ICONS = setOf(
        IC_MOSTLY_CLOUDY,
        IC_MOSTLY_CLOUDY_NIGHT,
        IC_MOSTLY_CLEAR,
        IC_PARTLY_CLOUDY,
        IC_PARTLY_CLOUDY_NIGHT,
        IC_HORIZON_SUN,
        IC_PARTLY_CLOUDY_CHANCE_RAIN,
        IC_PARTLY_CLOUDY_CHANCE_RAIN_NIGHT,
        IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN,
        IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN_NIGHT,
        IC_CLOUDY_CHANCE_RAIN,
        IC_CLOUDY_SLIGHT_CHANCE_RAIN,
        IC_FOG_CLOUDY,
        IC_FOG_SUNNY,
        IC_FOG_NIGHT,
        IC_FOG_LIGHT,
        IC_FOG_LIGHT_NIGHT,
    )

    private val CLOUD_FORECAST_ELIGIBLE_ICONS = MIXED_ICONS + setOf(
        IC_FOG,
        IC_FOG_DENSE,
        IC_CLOUDY,
        IC_MOSTLY_CLEAR,
    )

    private val SUNNY_ICONS = setOf(IC_CLEAR, IC_NIGHT, IC_MOSTLY_CLEAR, IC_HORIZON_SUN)

    /**
     * Mixed icons whose forecast-bar bottom segment is painted rain-blue rather than cloud-grey.
     * Deliberately only "chance rain" (not "slight chance"); mirrors Android's CHANCE_RAIN_ICONS
     * so the cloud/rain bar split is identical across platforms.
     */
    val CHANCE_RAIN_ICONS = setOf(
        IC_PARTLY_CLOUDY_CHANCE_RAIN,
        IC_PARTLY_CLOUDY_CHANCE_RAIN_NIGHT,
        IC_CLOUDY_CHANCE_RAIN,
    )

    // ── Thresholds ────────────────────────────────────────────────────────

    private const val FULLY_CLOUDY_THRESHOLD = 97
    private const val MOSTLY_CLOUDY_UPPER_THRESHOLD = 90

    // ── Condition flags ───────────────────────────────────────────────────

    data class ConditionFlags(
        val isSunny: Boolean,
        val isRainy: Boolean,
        val isMixed: Boolean,
        val isNight: Boolean = false,
        val isTwilight: Boolean = false,
    )

    fun getConditionFlags(iconName: String, isNight: Boolean = false): ConditionFlags {
        return ConditionFlags(
            isSunny = iconName in SUNNY_ICONS,
            isRainy = iconName in PRECIPITATION_ICONS,
            isMixed = iconName in MIXED_ICONS,
            isNight = isNight,
        )
    }

    // ── Classification queries ────────────────────────────────────────────

    fun isSunny(iconName: String): Boolean = iconName in SUNNY_ICONS
    fun isPrecipitation(iconName: String): Boolean = iconName in PRECIPITATION_ICONS
    fun isRainIndicator(iconName: String): Boolean = iconName in RAIN_INDICATOR_ICONS
    fun isMixed(iconName: String): Boolean = iconName in MIXED_ICONS
    fun isCloudForecastEligible(iconName: String): Boolean = iconName in CLOUD_FORECAST_ELIGIBLE_ICONS
    fun isChanceOfRainIcon(iconName: String): Boolean = iconName in CHANCE_RAIN_ICONS

    /**
     * Minimum measured cloud cover (%) for a provider's "partly cloudy" wording to stand as a
     * partly-cloudy daily icon. Below this, the day is really only a few clouds → "mostly clear".
     */
    const val PARTLY_CLOUDY_MIN_CLOUD_COVER = 25

    /**
     * Daily-only floor: a provider's worded "partly cloudy" is necessary but NOT sufficient — it must
     * ALSO be at least [PARTLY_CLOUDY_MIN_CLOUD_COVER]% cloudy at noon. When the measured
     * [cloudCoverPercent] is known and below that, downgrade to the slightly-cloudy tier ("mostly
     * clear", via [getCloudCoverIcon]). Any other icon, or a null reading, passes through unchanged.
     *
     * Intentionally invoked only from the daily icon path (NOT from [resolveIconName], which also
     * drives per-hour icons) so hourly icons are untouched.
     */
    fun applyDailyPartlyCloudyFloor(iconName: String, cloudCoverPercent: Int?, isNight: Boolean): String {
        val isPartlyCloudy = iconName == IC_PARTLY_CLOUDY || iconName == IC_PARTLY_CLOUDY_NIGHT
        return if (isPartlyCloudy && cloudCoverPercent != null && cloudCoverPercent < PARTLY_CLOUDY_MIN_CLOUD_COVER) {
            getCloudCoverIcon(isNight, cloudCoverPercent)
        } else {
            iconName
        }
    }

    enum class IconHome { PRECIPITATION, CLOUD_COVER, HOURLY }

    fun resolveIconHome(iconName: String): IconHome = when {
        isRainIndicator(iconName) -> IconHome.PRECIPITATION
        isCloudForecastEligible(iconName) -> IconHome.CLOUD_COVER
        else -> IconHome.HOURLY
    }

    /** Minimum daily precip probability (%) for a daily-column tap to open the precipitation graph. */
    const val DAILY_CLICK_PRECIP_THRESHOLD = 16

    /**
     * Daily-column tap gate: open the precipitation graph only when the day reads as rain AND its daily
     * precip probability clears [DAILY_CLICK_PRECIP_THRESHOLD]. Platform-neutral so Android (Int res) and
     * desktop (icon name) feed it their own already-computed `isRainIndicator`.
     */
    fun shouldDailyClickShowPrecip(isRainIndicator: Boolean, precipProbability: Int?): Boolean =
        isRainIndicator && (precipProbability ?: 0) >= DAILY_CLICK_PRECIP_THRESHOLD

    /**
     * Name-based convenience for daily-column taps (desktop). Returns only [IconHome.PRECIPITATION] or
     * [IconHome.HOURLY] — unlike [resolveIconHome] (bottom-row taps), a daily tap never routes to
     * cloud cover, matching Android's `DayClickHelper.resolveDailyTargetViewMode`.
     */
    fun resolveDailyClickHome(iconName: String?, precipProbability: Int?): IconHome =
        if (iconName != null && shouldDailyClickShowPrecip(isRainIndicator(iconName), precipProbability)) {
            IconHome.PRECIPITATION
        } else {
            IconHome.HOURLY
        }

    // ── Cloud ratio ───────────────────────────────────────────────────────

    /** Returns cloud ratio (0.0 = clear, 1.0 = overcast) from condition text, or null. */
    fun cloudRatioFromCondition(condition: String?): Float? {
        if (condition == null) return null
        val lower = condition.lowercase()
        return when {
            lower.contains("mostly clear") || lower.contains("mostly sunny") -> 0.18f
            lower.contains("partly") -> 0.35f
            lower.contains("mostly cloudy") || lower.contains("broken") -> 0.70f
            lower.contains("cloudy") || lower.contains("overcast") -> 0.95f
            lower.contains("fog") -> 0.50f
            else -> null
        }
    }

    /** Returns cloud ratio for a resolved icon name, or null for non-mixed icons. */
    fun cloudRatioFromIcon(iconName: String): Float? = when (iconName) {
        IC_HORIZON_SUN -> 0.12f
        IC_MOSTLY_CLEAR -> 0.18f
        IC_FOG_SUNNY -> 0.15f
        IC_FOG_LIGHT, IC_FOG_LIGHT_NIGHT -> 0.30f
        IC_PARTLY_CLOUDY -> 0.35f
        IC_PARTLY_CLOUDY_NIGHT -> 0.25f
        IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN, IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN_NIGHT -> 0.38f
        IC_PARTLY_CLOUDY_CHANCE_RAIN, IC_PARTLY_CLOUDY_CHANCE_RAIN_NIGHT -> 0.40f
        IC_FOG_NIGHT -> 0.50f
        IC_CLOUDY_SLIGHT_CHANCE_RAIN -> 0.60f
        IC_CLOUDY_CHANCE_RAIN -> 0.65f
        IC_MOSTLY_CLOUDY, IC_MOSTLY_CLOUDY_NIGHT, IC_FOG_CLOUDY -> 0.70f
        else -> null
    }

    // ── Patchy fog normalization ──────────────────────────────────────────

    /**
     * "Patchy fog after 8am then sunny" should resolve to the post-transition
     * condition ("sunny"), not the pre-transition "patchy fog".
     */
    fun normalizePatchyFogTransitionCondition(condition: String): String {
        if (!condition.contains("patchy fog")) return condition
        val thenIndex = condition.indexOf(" then ")
        if (thenIndex == -1) return condition
        return condition.substring(thenIndex + " then ".length).trim()
    }

    // ── Main icon resolver ────────────────────────────────────────────────

    /**
     * Resolves a weather condition string to a canonical icon name.
     *
     * @param condition Weather condition text (e.g. "Partly Cloudy", "Rain Showers")
     * @param isNight Whether it is currently nighttime
     * @param cloudCover Cloud cover percentage (0-100), if available
     * @param precipProbability Precipitation probability (0-100), if available
     * @param isTwilight Whether it is sunrise/sunset twilight
     * @param isSunBoundary Whether the sun is at the horizon boundary
     * @return Canonical icon name string
     */
    fun resolveIconName(
        condition: String?,
        isNight: Boolean = false,
        cloudCover: Int? = null,
        precipProbability: Int? = null,
        isTwilight: Boolean = false,
        isSunBoundary: Boolean = false,
    ): String {
        if (condition == null) return IC_UNKNOWN

        val lowerCondition = condition.lowercase()
        val normalizedCondition = normalizePatchyFogTransitionCondition(lowerCondition)
        val isSlightChance = normalizedCondition.contains("slight chance") || normalizedCondition.contains("patchy")
        val isSubOvercastCloudy =
            normalizedCondition.contains("cloudy") &&
                !normalizedCondition.contains("mostly cloudy") &&
                !normalizedCondition.contains("partly") &&
                cloudCover != null &&
                cloudCover < FULLY_CLOUDY_THRESHOLD

        return when {
            normalizedCondition.contains("thunder") || normalizedCondition.contains("storm") || normalizedCondition.contains("hail") -> {
                val effectiveProb = precipProbability ?: if (isSlightChance) 20 else null
                getPrecipitationIcon(isNight, cloudCover, effectiveProb, IC_STORM)
            }
            normalizedCondition.contains("snow") || normalizedCondition.contains("flurries") || normalizedCondition.contains("blizzard") || normalizedCondition.contains("sleet") || normalizedCondition.contains("ice pellet") -> {
                val effectiveProb = precipProbability ?: if (isSlightChance) 20 else null
                getPrecipitationIcon(isNight, cloudCover, effectiveProb, IC_SNOW)
            }
            normalizedCondition.contains("rain") || normalizedCondition.contains("drizzle") || normalizedCondition.contains("shower") -> {
                val effectiveProb = precipProbability ?: if (isSlightChance) 20 else null
                getPrecipitationIcon(isNight, cloudCover, effectiveProb, IC_RAIN)
            }
            normalizedCondition.contains("fog") && (normalizedCondition.contains("sunny") || normalizedCondition.contains("clear")) -> {
                if (isNight) IC_FOG_NIGHT else IC_FOG_SUNNY
            }
            normalizedCondition.contains("fog") && (normalizedCondition.contains("cloudy") || normalizedCondition.contains("overcast")) -> IC_FOG_CLOUDY
            normalizedCondition.contains("dense fog") -> IC_FOG_DENSE
            normalizedCondition.contains("patchy fog") || normalizedCondition.contains("light fog") -> {
                if (isNight) IC_FOG_LIGHT_NIGHT else IC_FOG_LIGHT
            }
            normalizedCondition.contains("fog") || normalizedCondition.contains("mist") || normalizedCondition.contains("haze") -> {
                if (isNight) IC_FOG_NIGHT else IC_FOG
            }
            normalizedCondition.contains("(75%)") || normalizedCondition.contains("mostly cloudy") -> {
                if (isNight) IC_MOSTLY_CLOUDY_NIGHT else IC_PARTLY_CLOUDY
            }
            normalizedCondition.contains("broken") -> {
                if (isNight) IC_MOSTLY_CLOUDY_NIGHT else IC_MOSTLY_CLOUDY
            }
            normalizedCondition.contains("(25%)") || normalizedCondition.contains("mostly clear") || normalizedCondition.contains("mostly sunny") || normalizedCondition.contains("partly sunny") -> {
                if (isSunBoundary) IC_HORIZON_SUN
                else if (isNight && cloudCover != null && cloudCover > 25) getCloudCoverIcon(isNight, cloudCover)
                else if (isNight) IC_NIGHT else IC_MOSTLY_CLEAR
            }
            normalizedCondition.contains("partly") -> {
                if (isNight) IC_PARTLY_CLOUDY_NIGHT else IC_PARTLY_CLOUDY
            }
            normalizedCondition.contains("overcast") -> {
                if (isSunBoundary) IC_HORIZON_SUN
                // The daily word is a whole-day summary and can contradict the measured noon sky
                // (e.g. an Open-Meteo day coded 3 for midnight drizzle under a clear daytime), so
                // below the fully-cloudy threshold the hourly cloud percent picks the tier.
                else if (cloudCover != null && cloudCover < FULLY_CLOUDY_THRESHOLD) {
                    getCloudCoverIcon(isNight, cloudCover)
                }
                else IC_CLOUDY
            }
            // Worded "cloudy" takes the same measured-cover tier instead of a flat mostly-cloudy.
            isSubOvercastCloudy -> getCloudCoverIcon(isNight, cloudCover)
            normalizedCondition.contains("cloudy") -> IC_CLOUDY
            normalizedCondition.contains("wind") || normalizedCondition.contains("breez") || normalizedCondition.contains("gale") -> IC_WIND
            normalizedCondition.contains("clear") || normalizedCondition.contains("sunny") || normalizedCondition.contains("fair") || normalizedCondition.contains("observed") -> {
                if (isSunBoundary) IC_HORIZON_SUN
                else if (isNight && cloudCover != null && cloudCover > 25) getCloudCoverIcon(isNight, cloudCover)
                else if (isNight) IC_NIGHT
                else if (cloudCover != null && cloudCover > 25) getCloudCoverIcon(false, cloudCover)
                else IC_CLEAR
            }
            else -> {
                if (isSunBoundary) IC_HORIZON_SUN
                else if (isNight && cloudCover != null && cloudCover > 25) getCloudCoverIcon(isNight, cloudCover)
                else if (isNight) IC_NIGHT
                else if (cloudCover != null && cloudCover > 25) getCloudCoverIcon(false, cloudCover)
                else IC_CLEAR
            }
        }
    }

    // ── Precipitation icon sub-resolution ─────────────────────────────────

    private fun getPrecipitationIcon(
        isNight: Boolean,
        cloudCover: Int?,
        precipProbability: Int?,
        baseRainIcon: String,
    ): String {
        if (precipProbability == null || precipProbability >= 80) return baseRainIcon
        if (precipProbability < 8) return getCloudCoverIcon(isNight, cloudCover)

        val isChance = precipProbability >= 60
        val cloudPct = cloudCover ?: 50

        return when {
            cloudPct > 70 ->
                if (isChance) IC_CLOUDY_CHANCE_RAIN
                else IC_CLOUDY_SLIGHT_CHANCE_RAIN
            cloudPct > 30 -> when {
                isNight && isChance -> IC_PARTLY_CLOUDY_CHANCE_RAIN_NIGHT
                isNight -> IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN_NIGHT
                isChance -> IC_PARTLY_CLOUDY_CHANCE_RAIN
                else -> IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN
            }
            else -> when {
                isNight && isChance -> IC_PARTLY_CLOUDY_CHANCE_RAIN_NIGHT
                isNight -> IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN_NIGHT
                isChance -> IC_PARTLY_CLOUDY_CHANCE_RAIN
                else -> IC_PARTLY_CLOUDY_SLIGHT_CHANCE_RAIN
            }
        }
    }

    // ── Cloud cover icon ──────────────────────────────────────────────────

    fun getCloudCoverIcon(isNight: Boolean, cloudCover: Int?): String {
        if (cloudCover == null) return if (isNight) IC_PARTLY_CLOUDY_NIGHT else IC_PARTLY_CLOUDY
        return when (cloudCover.coerceIn(0, 100)) {
            in 0..25 -> if (isNight) IC_NIGHT else IC_MOSTLY_CLEAR
            in 26..74 -> if (isNight) IC_PARTLY_CLOUDY_NIGHT else IC_PARTLY_CLOUDY
            in 75..MOSTLY_CLOUDY_UPPER_THRESHOLD -> if (isNight) IC_MOSTLY_CLOUDY_NIGHT else IC_MOSTLY_CLOUDY
            else -> IC_CLOUDY
        }
    }
}

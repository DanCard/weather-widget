package com.weatherwidget.shared.util

/**
 * Locale-based default for the temperature unit, used only when the user has never
 * touched the Celsius toggle. An explicit preference always wins over this default.
 */
object UnitDefaults {
    /**
     * Regions whose customary temperature scale is Fahrenheit, per CLDR's
     * `unitPreferenceData` for temperature (US plus its territories, Bahamas, Belize,
     * Cayman Islands, Palau, Micronesia, Marshall Islands). Everywhere else — including
     * Liberia and Myanmar, which are imperial for distance but Celsius for weather — is
     * Celsius.
     */
    private val FAHRENHEIT_REGIONS = setOf(
        "US", "PR", "GU", "VI", "AS", "MP", "UM", // United States and territories
        "BS", "BZ", "KY", "PW", "FM", "MH",
    )

    /**
     * True when the region's customary weather unit is Celsius. An unknown or absent
     * region (language-only locales report an empty country) defaults to Celsius, the
     * world-majority convention — mirroring ICU, which treats unknown regions as SI.
     */
    fun defaultUseCelsius(countryCode: String?): Boolean =
        countryCode.isNullOrBlank() || countryCode.uppercase() !in FAHRENHEIT_REGIONS

    /**
     * Preference ladder: an explicit OS-level temperature unit (the locale's `-u-mu-`
     * Unicode extension — user-set via Android 14's Regional Preferences, surfaced by
     * `LocalePreferences.getTemperatureUnit(locale, resolved = false)`) beats the
     * region default. CLDR truncates keyword values to 8 chars, so Fahrenheit arrives
     * as "fahrenhe"; Kelvin users get Celsius, the nearer of the two scales we offer.
     * Anything absent or unrecognized falls through to [defaultUseCelsius] by region.
     */
    fun defaultUseCelsius(
        explicitTemperatureUnit: String?,
        countryCode: String?,
    ): Boolean =
        when (explicitTemperatureUnit?.lowercase()) {
            "celsius", "kelvin" -> true
            "fahrenhe", "fahrenheit" -> false
            else -> defaultUseCelsius(countryCode)
        }

    /** [defaultUseCelsius] ladder fed from a [java.util.Locale] (JVM/desktop path). */
    fun defaultUseCelsius(locale: java.util.Locale): Boolean =
        defaultUseCelsius(locale.getUnicodeLocaleType("mu"), locale.country)
}

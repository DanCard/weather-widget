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
}

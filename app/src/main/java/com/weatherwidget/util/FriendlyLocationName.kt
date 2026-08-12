package com.weatherwidget.util

import android.content.Context
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.repository.SharedLocationResolver
import java.util.Locale

/**
 * Resolves a human-readable name ("Mountain View, California") for coordinates anywhere the UI
 * would otherwise show bare lat/lon. Local sources first — no network on the common path:
 *
 * 1. User-set aliases from the observations screen (`alias_*` in `weather_widget_prefs`).
 * 2. Previously reverse-geocoded names cached here (`geo_name_*` in `weather_prefs`).
 * 3. Labels already stored with `historical_pois` entries (search and GPS handoff both save real names).
 *
 * [resolve] adds a Nominatim reverse lookup (cached on success) when none of those hit.
 */
object FriendlyLocationName {

    private const val WEATHER_PREFS = "weather_prefs"
    private const val ALIAS_PREFS = "weather_widget_prefs"

    /** Local-only lookup; safe on hot paths (prefs reads, no network). Null when unknown. */
    fun cached(context: Context, lat: Double, lon: Double): String? {
        // Deliberately default-locale: must match the key WeatherObservationsActivity writes.
        val aliasKey = String.format("alias_%.3f_%.3f", lat, lon)
        SharedPreferencesUtil.getPrefs(context, ALIAS_PREFS).getString(aliasKey, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val weatherPrefs = SharedPreferencesUtil.getPrefs(context, WEATHER_PREFS)
        weatherPrefs.getString(cacheKey(lat, lon), null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return nameFromPois(weatherPrefs.getString("historical_pois", null), lat, lon)
    }

    /** [cached], then a reverse geocode whose result is persisted for future [cached] calls. */
    suspend fun resolve(
        context: Context,
        resolver: SharedLocationResolver,
        lat: Double,
        lon: Double,
    ): String? {
        cached(context, lat, lon)?.let { return it }
        val name = resolver.friendlyName(lat, lon) ?: return null
        SharedPreferencesUtil.getPrefs(context, WEATHER_PREFS)
            .edit()
            .putString(cacheKey(lat, lon), name)
            .apply()
        return name
    }

    /**
     * Extracts a same-site POI label from the raw `historical_pois` string
     * (`label|lat|lon` entries joined by `;`). Coordinate-shaped labels — written by fetch
     * paths that had no name — don't count as friendly.
     */
    fun nameFromPois(rawPois: String?, lat: Double, lon: Double): String? {
        if (rawPois.isNullOrBlank()) return null
        return rawPois.split(";").firstNotNullOfOrNull { entry ->
            val parts = entry.split("|")
            if (parts.size < 3) return@firstNotNullOfOrNull null
            val poiLat = parts[parts.size - 2].toDoubleOrNull() ?: return@firstNotNullOfOrNull null
            val poiLon = parts[parts.size - 1].toDoubleOrNull() ?: return@firstNotNullOfOrNull null
            val label = parts.dropLast(2).joinToString("|").trim()
            label.takeIf {
                it.isNotEmpty() && !isCoordinateLabel(it) && LocationMatch.sameSite(poiLat, poiLon, lat, lon)
            }
        }
    }

    /** A label with no letters ("37.42, -122.08" in any locale's digits/separators) is not a name. */
    fun isCoordinateLabel(label: String): Boolean = label.none { it.isLetter() }

    private fun cacheKey(lat: Double, lon: Double): String =
        String.format(Locale.US, "geo_name_%.3f_%.3f", lat, lon)
}

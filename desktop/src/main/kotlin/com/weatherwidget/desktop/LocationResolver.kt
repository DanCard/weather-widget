package com.weatherwidget.desktop

import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.data.repository.SharedLocationResolver
import kotlin.math.roundToInt

class LocationResolver(
    private val phoneLocator: PhoneLocator,
    private val timezoneLocator: TimezoneLocator,
    private val sharedLocationResolver: SharedLocationResolver,
) {
    suspend fun acquire(log: (String) -> Unit = {}): ResolvedLocation? {
        log("Trying connected phone location first.")
        val phone = fromPhone(log) ?: return null
        if (!phone.isFresh) {
            log("Phone location is stale; falling back to location picker.")
        }
        return phone.takeIf { it.isFresh }
    }

    suspend fun suggestPrefill(log: (String) -> Unit = {}): ResolvedLocation? {
        val prefill = sharedLocationResolver.suggestPrefill(log)
        if (prefill != null) return prefill

        log("IP lookup unavailable; trying timezone fallback...")
        val timezone = timezoneLocator.locate() ?: return null
        log("Timezone fallback found ${timezone.zoneId}.")
        return ResolvedLocation(
            lat = timezone.lat,
            lon = timezone.lon,
            label = timezone.zoneId,
            source = "Timezone",
        )
    }

    suspend fun searchText(query: String): List<ResolvedLocation> =
        sharedLocationResolver.searchText(query)

    suspend fun fromCoordinates(
        lat: Double,
        lon: Double,
    ): ResolvedLocation =
        sharedLocationResolver.fromCoordinates(lat, lon)

    fun phoneAvailable(): Boolean = phoneLocator.isAvailable()

    suspend fun fromPhone(log: (String) -> Unit = {}): ResolvedLocation? {
        val phone = phoneLocator.locate(log) ?: return null
        return phone.toResolved()
    }

    companion object {
        private const val FRESH_FIX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    }

    private fun PhoneLocation.toResolved(): ResolvedLocation {
        val age = fixAgeMillis
        val accuracy = accuracyMeters
        val details = buildList {
            serial?.let { add(it) }
            add(provider)
            if (accuracy != null) add("${accuracy.roundToInt()}m")
            if (age != null) add(formatAge(age))
        }.joinToString(", ")
        return ResolvedLocation(
            lat = lat,
            lon = lon,
            label = "Phone GPS (${lat.formatCoord()}, ${lon.formatCoord()})",
            source = "Phone GPS",
            detail = details,
            isFresh = age != null && age < FRESH_FIX_AGE_MILLIS,
        )
    }
}

fun ResolvedLocation.toConfig(): DesktopConfig {
    val isUs = (lat in 24.0..50.0 && lon in -125.0..-66.0) || // CONUS
               (lat in 51.0..72.0 && lon in -180.0..-130.0) || // Alaska
               (lat in 18.0..23.0 && lon in -161.0..-154.0) || // Hawaii
               (lat in 17.0..19.0 && lon in -68.0..-65.0)      // Puerto Rico
    
    return DesktopConfig(
        lat = lat,
        lon = lon,
        label = label,
        weatherSource = if (isUs) "NWS" else "OPEN_METEO"
    )
}

private fun Double.formatCoord(): String = "%.4f".format(this)

private fun formatAge(ageMillis: Long): String {
    val minutes = ageMillis / 60_000
    val hours = minutes / 60
    return when {
        hours >= 24 -> "${hours / 24}d ${hours % 24}h old"
        hours > 0 -> "${hours}h ${minutes % 60}m old"
        else -> "${minutes}m old"
    }
}

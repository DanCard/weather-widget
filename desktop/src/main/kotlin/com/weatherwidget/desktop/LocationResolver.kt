package com.weatherwidget.desktop

import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.data.repository.SharedLocationResolver
import kotlin.math.roundToInt
import com.weatherwidget.shared.util.NwsCoverage

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

    private suspend fun PhoneLocation.toResolved(): ResolvedLocation {
        val age = fixAgeMillis
        val accuracy = accuracyMeters
        val details = buildList {
            serial?.let { add(it) }
            add(provider)
            if (accuracy != null) add("${accuracy.roundToInt()}m")
            if (age != null) add(formatAge(age))
        }.joinToString(", ")
        // Friendly place name alongside the raw fix; coordinates stay visible either way.
        val coords = "${lat.formatCoord()}, ${lon.formatCoord()}"
        val name = sharedLocationResolver.friendlyName(lat, lon)
        return ResolvedLocation(
            lat = lat,
            lon = lon,
            label = if (name != null) "$name ($coords)" else "Phone GPS ($coords)",
            source = "Phone GPS",
            detail = details,
            isFresh = age != null && age < FRESH_FIX_AGE_MILLIS,
        )
    }
}

fun ResolvedLocation.toConfig(): DesktopConfig {
    val isUs = NwsCoverage.covers(lat, lon)
    return DesktopConfig(
        lat = lat,
        lon = lon,
        label = label,
        settings = DesktopSettings(weatherSource = if (isUs) "NWS" else "OPEN_METEO")
    )
}

private fun Double.formatCoord(): String = com.weatherwidget.shared.util.formatCoord(this)

private fun formatAge(ageMillis: Long): String =
    com.weatherwidget.shared.util.AgeFormatter.formatAgeOld(ageMillis)


package com.weatherwidget.util

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.*

/**
 * A lightweight utility to calculate sunrise and sunset times based on latitude, longitude, and date.
 * This is based on the Sunrise Equation.
 * It's purely mathematical and has no battery impact.
 *
 * @throws IllegalArgumentException if lat is outside [-90, 90] or lon is outside [-180, 180]
 */
enum class SunPhase { DAY, TWILIGHT, NIGHT }

/**
 * Pre-computed sun information for a given date, time, and location.
 * Avoids repeated trigonometric calculations when multiple sun-related queries
 * are needed for the same inputs.
 */
data class SunInfo(
    val phase: SunPhase,
    val isNight: Boolean,
    val isSunBoundary: Boolean,
    val sunTimes: SunPositionUtils.SunTimes,
)

object SunPositionUtils {
    /**
     * The answer when there is no location to compute against: permanent daylight, no boundary.
     *
     * Sun position is a *decoration* — day/night shading and icon variants — so the honest response to
     * "we do not know where we are" is to skip the decoration, not to invent a place. Callers used to
     * substitute a hardcoded Google-HQ coordinate here, which silently rendered another city's
     * day/night cycle. Renderers that have no data rows to shade see no visible difference either way.
     */
    val UNKNOWN_LOCATION = SunInfo(
        phase = SunPhase.DAY,
        isNight = false,
        isSunBoundary = false,
        sunTimes = SunTimes(sunriseHour = 0.0, sunsetHour = 24.0),
    )

    /**
     * [getSunInfo] for a location that may be absent. Returns [UNKNOWN_LOCATION] rather than guessing
     * when [lat]/[lon] are null or non-finite.
     */
    fun getSunInfoOrUnknown(dateTime: LocalDateTime, lat: Double?, lon: Double?): SunInfo {
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return UNKNOWN_LOCATION
        return getSunInfo(dateTime, lat, lon)
    }

    /**
     * Computes all sun-related information for the given date, time, and location
     * in a single pass. Prefer this over calling [getSunPhase], [isNight], and
     * [isSunBoundary] separately, which would each recompute sunrise/sunset times.
     *
     * @param dateTime the date and time to evaluate
     * @param lat latitude in degrees, must be in [-90, 90]
     * @param lon longitude in degrees, must be in [-180, 180]
     */
    fun getSunInfo(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
    ): SunInfo {
        val sunTimes = getSunTimes(dateTime, lat, lon)

        if (sunTimes.sunsetHour >= 24.0) {
            return SunInfo(
                phase = SunPhase.DAY,
                isNight = false,
                isSunBoundary = false,
                sunTimes = sunTimes,
            )
        }
        if (sunTimes.sunriseHour <= 0.0 && sunTimes.sunsetHour <= 0.0) {
            return SunInfo(
                phase = SunPhase.NIGHT,
                isNight = true,
                isSunBoundary = false,
                sunTimes = sunTimes,
            )
        }

        val hourStart = dateTime.hour + dateTime.minute / 60.0
        val hourEnd = hourStart + 1.0

        val isNight = hourEnd <= sunTimes.sunriseHour || hourStart >= sunTimes.sunsetHour
        val isSunBoundary =
            (hourStart < sunTimes.sunriseHour && hourEnd > sunTimes.sunriseHour) ||
            (hourStart < sunTimes.sunsetHour && hourEnd > sunTimes.sunsetHour)

        val phase = when {
            isNight -> SunPhase.NIGHT
            isSunBoundary -> SunPhase.TWILIGHT
            hourStart >= sunTimes.sunriseHour && hourEnd <= sunTimes.sunsetHour -> {
                val nearSunset = hourEnd > sunTimes.sunsetHour - 1.0
                val nearSunrise = hourStart < sunTimes.sunriseHour + 1.0
                if (nearSunset || nearSunrise) SunPhase.TWILIGHT else SunPhase.DAY
            }
            else -> SunPhase.TWILIGHT
        }

        return SunInfo(
            phase = phase,
            isNight = isNight,
            isSunBoundary = isSunBoundary,
            sunTimes = sunTimes,
        )
    }

    fun getSunPhase(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
    ): SunPhase = getSunInfo(dateTime, lat, lon).phase

    /**
     * Returns true if this hour contains the civil twilight sunrise or sunset boundary
     * (the hour when the sun crosses the civil twilight line).
     */
    fun isSunBoundary(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
    ): Boolean = getSunInfo(dateTime, lat, lon).isSunBoundary

    /**
     * Determines if it is night at a given location and time.
     */
    fun isNight(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
    ): Boolean = getSunInfo(dateTime, lat, lon).isNight

    /**
     * Returns sunrise and sunset hours for a given date and location.
     * Each value is in the range 0.0–24.0 (hour of day).
     *
     * Special return values:
     * - `sunriseHour == 0.0` and `sunsetHour == 24.0`: sun never sets (polar day)
     * - `sunriseHour == 0.0` and `sunsetHour == 0.0`: sun never rises (polar night)
     * - `sunriseHour <= 0.0`: sunrise before midnight (polar regions)
     * - `sunsetHour >= 24.0`: sunset after midnight (polar regions)
     *
     * Useful for classifying hourly forecasts as day vs night.
     */
    data class SunTimes(val sunriseHour: Double, val sunsetHour: Double)

    fun getSunTimes(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
    ): SunTimes {
        return SunTimes(
            sunriseHour = calculateSunriseSunset(dateTime, lat, lon, true),
            sunsetHour = calculateSunriseSunset(dateTime, lat, lon, false),
        )
    }

    /**
     * Simple approximation of the sunrise/sunset hour using civil twilight zenith (96°).
     *
     * @return the hour of the day (0.0 to 24.0). Returns 0.0 if the sun never rises
     *   (polar night) and 24.0 if the sun never sets (polar day/midnight sun).
     */
    private fun calculateSunriseSunset(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
        isSunrise: Boolean,
    ): Double {
        require(lat in -90.0..90.0) { "lat must be in [-90, 90], was $lat" }
        require(lon in -180.0..180.0) { "lon must be in [-180, 180], was $lon" }

        val dayOfYear = dateTime.dayOfYear

        // Zenith 96° = civil twilight (sun 6° below horizon — visible light persists).
        // This makes sunrise/sunset boundaries match what users see, and aligns with
        // getSunInfo which also uses 96°.
        val zenith = 96.0

        // 1. Calculate the day of the year
        val n = dayOfYear.toDouble()

        // 2. Convert longitude to hour value and calculate an approximate time
        val lngHour = lon / 15.0
        val t =
            if (isSunrise) {
                n + ((6.0 - lngHour) / 24.0)
            } else {
                n + ((18.0 - lngHour) / 24.0)
            }

        // 3. Calculate the Sun's mean anomaly
        val m = (0.9856 * t) - 3.2891

        // 4. Calculate the Sun's true longitude
        var sunLongitude = m + (1.916 * sin(Math.toRadians(m))) + (0.020 * sin(Math.toRadians(2 * m))) + 282.634
        sunLongitude %= 360.0
        if (sunLongitude < 0) sunLongitude += 360.0

        // 5. Calculate the Sun's right ascension
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(sunLongitude))))
        ra %= 360.0
        if (ra < 0) ra += 360.0

        // sunLongitude and RA need to be in the same quadrant
        val lQuadrant = floor(sunLongitude / 90.0) * 90.0
        val raQuadrant = floor(ra / 90.0) * 90.0
        ra += (lQuadrant - raQuadrant)

        // Right ascension value in hours
        ra /= 15.0

        // 6. Calculate the Sun's declination
        val sinDec = 0.39782 * sin(Math.toRadians(sunLongitude))
        val cosDec = cos(asin(sinDec))

        // 7. Calculate the Sun's local hour angle
        val cosH = (cos(Math.toRadians(zenith)) - (sinDec * sin(Math.toRadians(lat)))) / (cosDec * cos(Math.toRadians(lat)))

        if (cosH > 1) return 0.0 // Sun never rises
        if (cosH < -1) return 24.0 // Sun never sets

        // 8. Finish calculating H and convert into hours
        val h =
            if (isSunrise) {
                360.0 - Math.toDegrees(acos(cosH))
            } else {
                Math.toDegrees(acos(cosH))
            }
        val hHours = h / 15.0

        // 9. Calculate local mean time of rising/setting
        val localT = hHours + ra - (0.06571 * t) - 6.622

        // 10. Adjust back to UTC
        var utcT = localT - lngHour
        utcT %= 24.0
        if (utcT < 0) utcT += 24.0

        // 11. Convert UTC to local time using the system default timezone offset.
        // Since the app only shows weather for the device's location, this is correct.
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(dateTime).totalSeconds / 3600.0
        var localTime = utcT + zoneOffset
        localTime %= 24.0
        if (localTime < 0) localTime += 24.0

        return localTime
    }
}

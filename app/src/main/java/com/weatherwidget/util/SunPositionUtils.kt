package com.weatherwidget.util

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.*

/**
 * A lightweight utility to calculate sunrise and sunset times based on latitude, longitude, and date.
 * This is based on the Sunrise Equation.
 * It's purely mathematical and has no battery impact.
 */
enum class SunPhase { DAY, TWILIGHT, NIGHT }

object SunPositionUtils {
    fun getSunPhase(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
    ): SunPhase {
        // Use civil twilight zenith (96°) for phase detection so that the
        // twilight transition icon covers the hour when light is visually
        // fading/appearing rather than the moment the sun's upper limb crosses
        // the horizon.  This pushes the DAY→NIGHT boundary ~30 min later and
        // the NIGHT→DAY boundary ~30 min earlier, matching what users see.
        val civilSunTimes = calculateSunTimesWithZenith(dateTime, lat, lon, 96.0)

        if (civilSunTimes.sunsetHour >= 24.0) return SunPhase.DAY
        if (civilSunTimes.sunriseHour <= 0.0 && civilSunTimes.sunsetHour <= 0.0) return SunPhase.NIGHT

        val hourStart = dateTime.hour + dateTime.minute / 60.0
        val hourEnd = hourStart + 1.0

        // Fully night: hour is entirely before sunrise or entirely after sunset
        if (hourEnd <= civilSunTimes.sunriseHour || hourStart >= civilSunTimes.sunsetHour) return SunPhase.NIGHT

        // Fully within daylight hours — check golden hour proximity
        if (hourStart >= civilSunTimes.sunriseHour && hourEnd <= civilSunTimes.sunsetHour) {
            val nearSunset = hourEnd > civilSunTimes.sunsetHour - 1.0
            val nearSunrise = hourStart < civilSunTimes.sunriseHour + 1.0
            return if (nearSunset || nearSunrise) SunPhase.TWILIGHT else SunPhase.DAY
        }

        // Hour spans a boundary (sunrise or sunset)
        return SunPhase.TWILIGHT
    }

    /**
     * Returns true if this hour contains the civil twilight sunrise or sunset boundary
     * (the hour when the sun crosses the civil twilight line). Uses the same zenith as
     * getSunPhase so the horizon-sun icon appears on the hour that matches the visual sunset.
     */
    fun isSunBoundary(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
    ): Boolean {
        val civilSunTimes = calculateSunTimesWithZenith(dateTime, lat, lon, 96.0)

        if (civilSunTimes.sunsetHour >= 24.0) return false
        if (civilSunTimes.sunriseHour <= 0.0 && civilSunTimes.sunsetHour <= 0.0) return false

        val hourStart = dateTime.hour + dateTime.minute / 60.0
        val hourEnd = hourStart + 1.0

        return (hourStart < civilSunTimes.sunriseHour && hourEnd > civilSunTimes.sunriseHour) ||
               (hourStart < civilSunTimes.sunsetHour && hourEnd > civilSunTimes.sunsetHour)
    }

    /**
     * Determines if it is night at a given location and time.
     */
    fun isNight(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
    ): Boolean {
        return getSunPhase(dateTime, lat, lon) == SunPhase.NIGHT
    }

    /**
     * Returns sunrise and sunset hours for a given date and location.
     * Each value is in the range 0.0–24.0 (hour of day).
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

    private fun calculateSunTimesWithZenith(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
        zenith: Double,
    ): SunTimes {
        return SunTimes(
            sunriseHour = calculateSunriseSunset(dateTime, lat, lon, true, zenith),
            sunsetHour = calculateSunriseSunset(dateTime, lat, lon, false, zenith),
        )
    }

    /**
     * Simple approximation of the sunrise/sunset hour.
     * Returns the hour of the day (0.0 to 24.0).
     */
    fun calculateSunriseSunset(
        dateTime: LocalDateTime,
        lat: Double,
        lon: Double,
        isSunrise: Boolean,
        zenith: Double = 93.33,
    ): Double {
        val dayOfYear = dateTime.dayOfYear

        // Zenith angle: 93.33° = official sunrise/sunset,
        // 96° = civil twilight (sun 6° below horizon — visible light persists).
        // Callers can override via the zenith parameter.
        val zenithValue = zenith

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
        var l = m + (1.916 * sin(Math.toRadians(m))) + (0.020 * sin(Math.toRadians(2 * m))) + 282.634
        l %= 360.0
        if (l < 0) l += 360.0

        // 5. Calculate the Sun's right ascension
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra %= 360.0
        if (ra < 0) ra += 360.0

        // L and RA need to be in the same quadrant
        val lQuadrant = floor(l / 90.0) * 90.0
        val raQuadrant = floor(ra / 90.0) * 90.0
        ra += (lQuadrant - raQuadrant)

        // Right ascension value in hours
        ra /= 15.0

        // 6. Calculate the Sun's declination
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))

        // 7. Calculate the Sun's local hour angle
        val cosH = (cos(Math.toRadians(zenithValue)) - (sinDec * sin(Math.toRadians(lat)))) / (cosDec * cos(Math.toRadians(lat)))

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

        // 11. Convert UTC to local time (Simplified approximation)
        // In a real app we'd use the proper timezone offset,
        // but for a widget we can use the system default offset.
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(dateTime).totalSeconds / 3600.0
        var localTime = utcT + zoneOffset
        localTime %= 24.0
        if (localTime < 0) localTime += 24.0

        return localTime
    }
}

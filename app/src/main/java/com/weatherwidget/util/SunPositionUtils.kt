package com.weatherwidget.util

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.*

/**
 * A lightweight utility to calculate sunrise and sunset times based on latitude, longitude, and date.
 * This is based on the Sunrise Equation. 
 * It's purely mathematical and has no battery impact.
 */
object SunPositionUtils {

    /**
     * Determines if it is night at a given location and time.
     */
    fun isNight(dateTime: LocalDateTime, lat: Double, lon: Double): Boolean {
        val sunriseTime = calculateSunriseSunset(dateTime, lat, lon, true)
        val sunsetTime = calculateSunriseSunset(dateTime, lat, lon, false)
        
        val hour = dateTime.hour + dateTime.minute / 60.0
        
        return hour < sunriseTime || hour > sunsetTime
    }

    /**
     * Simple approximation of the sunrise/sunset hour.
     * Returns the hour of the day (0.0 to 24.0).
     */
    private fun calculateSunriseSunset(dateTime: LocalDateTime, lat: Double, lon: Double, isSunrise: Boolean): Double {
        val dayOfYear = dateTime.dayOfYear
        
        // Zenith for sunrise/sunset (90.833 degrees is standard for atmospheric refraction)
        val zenith = 90.833
        
        // 1. Calculate the day of the year
        val n = dayOfYear.toDouble()
        
        // 2. Convert longitude to hour value and calculate an approximate time
        val lngHour = lon / 15.0
        val t = if (isSunrise) {
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
        val cosH = (cos(Math.toRadians(zenith)) - (sinDec * sin(Math.toRadians(lat)))) / (cosDec * cos(Math.toRadians(lat)))
        
        if (cosH > 1) return 0.0 // Sun never rises
        if (cosH < -1) return 24.0 // Sun never sets
        
        // 8. Finish calculating H and convert into hours
        val h = if (isSunrise) {
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

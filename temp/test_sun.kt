
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.OffsetDateTime
import kotlin.math.*

object SunPositionUtils {
    fun isNight(dateTime: LocalDateTime, lat: Double, lon: Double): Boolean {
        val sunriseTime = calculateSunriseSunset(dateTime, lat, lon, true)
        val sunsetTime = calculateSunriseSunset(dateTime, lat, lon, false)
        
        val hour = dateTime.hour + dateTime.minute / 60.0
        println("  Sunrise: $sunriseTime, Sunset: $sunsetTime, Current: $hour")
        
        return hour < sunriseTime || hour > sunsetTime
    }

    private fun calculateSunriseSunset(dateTime: LocalDateTime, lat: Double, lon: Double, isSunrise: Boolean): Double {
        val dayOfYear = dateTime.dayOfYear
        val zenith = 96.0
        val n = dayOfYear.toDouble()
        val lngHour = lon / 15.0
        val t = if (isSunrise) {
            n + ((6.0 - lngHour) / 24.0)
        } else {
            n + ((18.0 - lngHour) / 24.0)
        }
        val m = (0.9856 * t) - 3.2891
        var l = m + (1.916 * sin(Math.toRadians(m))) + (0.020 * sin(Math.toRadians(2 * m))) + 282.634
        l %= 360.0
        if (l < 0) l += 360.0
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra %= 360.0
        if (ra < 0) ra += 360.0
        val lQuadrant = floor(l / 90.0) * 90.0
        val raQuadrant = floor(ra / 90.0) * 90.0
        ra += (lQuadrant - raQuadrant)
        ra /= 15.0
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(Math.toRadians(zenith)) - (sinDec * sin(Math.toRadians(lat)))) / (cosDec * cos(Math.toRadians(lat)))
        if (cosH > 1) return 0.0
        if (cosH < -1) return 24.0
        val h = if (isSunrise) {
            360.0 - Math.toDegrees(acos(cosH))
        } else {
            Math.toDegrees(acos(cosH))
        }
        val hHours = h / 15.0
        val localT = hHours + ra - (0.06571 * t) - 6.622
        var utcT = localT - lngHour
        utcT %= 24.0
        if (utcT < 0) utcT += 24.0
        
        // Use -8 for PST
        val zoneOffset = -8.0 
        var localTime = utcT + zoneOffset
        localTime %= 24.0
        if (localTime < 0) localTime += 24.0
        
        return localTime
    }
}

fun main() {
    val lat = 37.422
    val lon = -122.0841
    val dt = LocalDateTime.of(2026, 2, 5, 7, 0)
    println("Checking 7:00 AM:")
    println("  isNight: ${SunPositionUtils.isNight(dt, lat, lon)}")
    
    val dt6 = LocalDateTime.of(2026, 2, 5, 6, 0)
    println("Checking 6:00 AM:")
    println("  isNight: ${SunPositionUtils.isNight(dt6, lat, lon)}")

    val dt8 = LocalDateTime.of(2026, 2, 5, 8, 0)
    println("Checking 8:00 AM:")
    println("  isNight: ${SunPositionUtils.isNight(dt8, lat, lon)}")
}

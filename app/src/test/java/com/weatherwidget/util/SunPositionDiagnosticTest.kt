package com.weatherwidget.util

import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

/**
 * Diagnostic test to find the exact day/night flip point for a given location.
 * Run with: ./gradlew testDebugUnitTest --tests "com.weatherwidget.util.SunPositionDiagnosticTest"
 */
@Category(ShortDuration::class)
class SunPositionDiagnosticTest {
    @Test
    fun calculateCurrentTimes() {
        // Mountain View, CA coordinates (emulator default)
        val lat = 37.422
        val lon = -122.0841
        val date = LocalDateTime.of(2026, 4, 12, 20, 0)
        
        println("Diagnostic for Lat: $lat, Lon: $lon on April 12, 2026")
        println("Checking hours 17:00 to 21:00...")
        
        for (h in 17..21) {
            for (m in listOf(0, 15, 30, 45)) {
                val time = LocalDateTime.of(2026, 4, 12, h, m)
                val isNight = SunPositionUtils.isNight(time, lat, lon)
                println("${time.toLocalTime()} -> Night: $isNight")
            }
        }
    }
}

package com.weatherwidget.desktop

import java.nio.file.Path
import java.time.ZoneId
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.math.pow

class TimezoneLocator(
    private val zoneTabPath: Path = Path.of("/usr/share/zoneinfo/zone1970.tab"),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun locate(): TimezoneLocation? {
        if (!zoneTabPath.exists()) return null
        val targetZone = zoneId.id
        return zoneTabPath.readLines()
            .asSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { parseZoneTabLine(it) }
            .firstOrNull { it.zoneId == targetZone }
    }

    companion object {
        fun parseZoneTabLine(line: String): TimezoneLocation? {
            if (line.isBlank() || line.startsWith("#")) return null
            val columns = line.split('\t')
            if (columns.size < 3) return null
            val coords = parseIso6709Coordinates(columns[1]) ?: return null
            return TimezoneLocation(
                lat = coords.first,
                lon = coords.second,
                zoneId = columns[2],
            )
        }

        fun parseIso6709Coordinates(raw: String): Pair<Double, Double>? {
            if (raw.length < 10) return null
            val lonSignIndex = raw.indexOfAny(charArrayOf('+', '-'), startIndex = 1)
            if (lonSignIndex <= 0) return null
            val lat = parseComponent(raw.substring(0, lonSignIndex), degreeDigits = 2) ?: return null
            val lon = parseComponent(raw.substring(lonSignIndex), degreeDigits = 3) ?: return null
            return lat to lon
        }

        private fun parseComponent(
            raw: String,
            degreeDigits: Int,
        ): Double? {
            if (raw.length != degreeDigits + 3 && raw.length != degreeDigits + 5) return null
            val sign = when (raw.firstOrNull()) {
                '+' -> 1
                '-' -> -1
                else -> return null
            }
            val degrees = raw.substring(1, 1 + degreeDigits).toIntOrNull() ?: return null
            val minutesStart = 1 + degreeDigits
            val minutes = raw.substring(minutesStart, minutesStart + 2).toIntOrNull() ?: return null
            val seconds = raw.substring(minutesStart + 2).takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            if (minutes !in 0..59 || seconds !in 0..59) return null
            return sign * (degrees + minutes / 60.0 + seconds / 3600.0).round(6)
        }

        private fun Double.round(places: Int): Double {
            val factor = 10.0.pow(places)
            return kotlin.math.round(this * factor) / factor
        }
    }
}

data class TimezoneLocation(
    val lat: Double,
    val lon: Double,
    val zoneId: String,
)

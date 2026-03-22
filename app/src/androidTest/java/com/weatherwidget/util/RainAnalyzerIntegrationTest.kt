package com.weatherwidget.util

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.widget.WeatherWidgetProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Integration test verifying that RainAnalyzer detects future rain when given
 * hourly forecasts queried with the standard HOURLY_LOOKAHEAD_HOURS window.
 *
 * This guards against the bug where WidgetIntentRouter used a narrow ±3h query
 * window, causing RainAnalyzer to receive zero forecasts for tomorrow/day-after.
 */
@RunWith(AndroidJUnit4::class)
class RainAnalyzerIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val lat = 37.422
    private val lon = -122.0841

    /**
     * Simulates the full data path: build hourly forecasts matching the standard
     * query window, pass them to RainAnalyzer, and verify rain is detected for
     * a day that is 2 days in the future.
     *
     * Also checks that RainAnalyzer emits the expected log line, which the widget
     * relies on for debugging rain display issues.
     */
    @Test
    fun rainDetectedForDayAfterTomorrow_withFullQueryWindow() {
        runShellCommand("logcat -c")

        val now = LocalDateTime.now()
        val targetDate = LocalDate.now().plusDays(2)
        val targetDateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Build hourly forecasts spanning the standard query window
        val hourlyStart = now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS)
        val hourlyEnd = now.plusHours(WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS)
        val forecasts = buildHourlyForecasts(hourlyStart, hourlyEnd, targetDate)

        // Verify we have forecasts for the target date (the whole point of the wide window)
        val targetDateForecasts = forecasts.filter {
            Instant.ofEpochMilli(it.dateTime).atZone(ZoneId.systemDefault()).toLocalDate() == targetDate
        }
        assertTrue(
            "Standard query window (${WeatherWidgetProvider.HOURLY_LOOKAHEAD_HOURS}h) should include " +
                "forecasts for $targetDateStr, but found ${targetDateForecasts.size}",
            targetDateForecasts.isNotEmpty(),
        )

        // Run RainAnalyzer — this is the same call DailyViewHandler makes
        val summary = RainAnalyzer.getRainSummary(forecasts, targetDate, "NWS", now)

        assertTrue(
            "RainAnalyzer should detect rain on $targetDateStr but returned null summary",
            summary != null,
        )

        // Verify the log line that confirms rain was found
        val logs = runShellCommand("logcat -d -s RainAnalyzer:D *:S")
        assertTrue(
            "Expected 'rain hours' log for $targetDateStr.\nLogs:\n$logs",
            logs.contains("rain hours for $targetDateStr"),
        )
    }

    /**
     * Verifies that a narrow query window (the old ±3h bug) would NOT include
     * forecasts for day-after-tomorrow, confirming the constants matter.
     */
    @Test
    fun narrowWindowMissesDayAfterTomorrow() {
        val now = LocalDateTime.now()
        val targetDate = LocalDate.now().plusDays(2)
        val targetDateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Build forecasts with the old narrow window
        val narrowStart = now.minusHours(3)
        val narrowEnd = now.plusHours(3)
        val forecasts = buildHourlyForecasts(narrowStart, narrowEnd, targetDate)

        val targetDateForecasts = forecasts.filter {
            Instant.ofEpochMilli(it.dateTime).atZone(ZoneId.systemDefault()).toLocalDate() == targetDate
        }
        assertTrue(
            "Narrow ±3h window should NOT include forecasts for $targetDateStr (found ${targetDateForecasts.size})",
            targetDateForecasts.isEmpty(),
        )
    }

    /**
     * Builds a list of hourly forecasts between [start] and [end].
     * Hours falling on [rainDate] get 50% precip probability; all others get 0%.
     */
    private fun buildHourlyForecasts(
        start: LocalDateTime,
        end: LocalDateTime,
        rainDate: LocalDate,
    ): List<HourlyForecastEntity> {
        val forecasts = mutableListOf<HourlyForecastEntity>()
        var hour = start.withMinute(0).withSecond(0).withNano(0)
        while (!hour.isAfter(end)) {
            val isRainHour = hour.toLocalDate() == rainDate
            forecasts.add(
                HourlyForecastEntity(
                    dateTime = hour.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    locationLat = lat,
                    locationLon = lon,
                    temperature = 55f,
                    condition = if (isRainHour) "Rain" else "Sunny",
                    source = "NWS",
                    precipProbability = if (isRainHour) 50 else 0,
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            hour = hour.plusHours(1)
        }
        return forecasts
    }

    private fun runShellCommand(command: String): String {
        val parcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(parcelFileDescriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                buildString {
                    while (true) {
                        val line = reader.readLine() ?: break
                        appendLine(line)
                    }
                }
            }
        }
    }
}

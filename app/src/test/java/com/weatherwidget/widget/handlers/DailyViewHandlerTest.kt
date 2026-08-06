package com.weatherwidget.widget.handlers

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.testutil.mockAppWidgetManager
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetRenderer
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyViewHandlerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Push-backing is process-static; reset it so each test starts with every widget unbacked
        // and the promote-unbacked-partial behaviour is deterministic regardless of test order.
        com.weatherwidget.widget.WidgetPushDispatcher.resetForTest()
    }

    private fun epoch(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun extreme(
        date: LocalDate,
        high: Float,
        low: Float,
        condition: String = "Clear",
        precipAmountMm: Float? = null,
        precipDayMm: Float? = null,
        precipNightMm: Float? = null,
        source: String = WeatherSource.NWS.id
    ) = com.weatherwidget.data.model.DailyHistory(
        date = date.toEpochDay() * 86_400_000L,
        source = source,
        locationLat = 0.0,
        locationLon = 0.0,
        computedHighTemp = high,
        computedLowTemp = low,
        condition = condition,
        updatedAt = System.currentTimeMillis(),
        precipAmountMm = precipAmountMm,
        precipDayMm = precipDayMm,
        precipNightMm = precipNightMm
    )

    @Test
    fun `prepareTextDays numColumns=2 shows only 2 slots`() {

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = createWeatherMap(today)

        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 2,
            displaySource = WeatherSource.NWS
        )

        assertEquals(8, result.size)
        assertEquals(2, result.count { it.isVisible })
        assertTrue(result[1].isVisible) // today
        assertTrue(result[2].isVisible) // tomorrow
    }

    @Test
    fun `prepareTextDays hides Today label in single-column mode only`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = createWeatherMap(today)

        val oneColumnResult = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 1,
            displaySource = WeatherSource.NWS,
        )
        val oneColumnToday = oneColumnResult.first { it.isVisible }
        assertEquals(today, oneColumnToday.date)
        assertEquals("Today", oneColumnToday.label)
        assertFalse(oneColumnToday.showLabel)

        val twoColumnResult = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 2,
            displaySource = WeatherSource.NWS,
        )
        val twoColumnToday = twoColumnResult.first { it.date == today }
        assertEquals("Today", twoColumnToday.label)
        assertTrue(twoColumnToday.showLabel)
    }

    @Test
    fun `prepareTextDays skipHistory shifts visible dates`() {

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = createWeatherMap(today)

        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipHistory = true
        )

        val visibleDates = result.filter { it.isVisible }.map { it.dateStr }
        assertEquals(
            listOf(
                today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE)
            ),
            visibleDates
        )
    }

    @Test
    fun `prepareTextDays skipHistory keeps today for 1 and 2 column widgets`() {
        // Narrow widgets always start from today regardless of skipHistory, mirroring
        // NavigationUtils.getDayOffsets (numColumns <= 2 -> startOffset 0).
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = createWeatherMap(today)

        for (numColumns in listOf(1, 2)) {
            val result = DailyViewLogic.prepareTextDays(
                todayLabel = "Today",
                now = now,
                centerDate = today,
                today = today,
                weatherByDate = weatherByDate,
                hourlyForecasts = emptyList(),
                numColumns = numColumns,
                displaySource = WeatherSource.NWS,
                skipHistory = true
            )

            val visibleDates = result.filter { it.isVisible }.map { it.date }
            assertEquals(numColumns, visibleDates.size)
            assertEquals(
                "numColumns=$numColumns should still start from today when skipping history",
                today,
                visibleDates.first()
            )
        }
    }

    @Test
    fun `prepareTextDays identifies first rainy day for text display`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        
        val weatherByDate = createWeatherMap(today)
        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            rainSummaryProvider = { _, date, _, _ ->
                when (date) {
                    today.plusDays(1) -> "9am"
                    today.plusDays(2) -> "10am"
                    else -> null
                }
            }
        )

        val tomorrow = result.first { it.date == today.plusDays(1) }
        val dayAfter = result.first { it.date == today.plusDays(2) }

        assertTrue(tomorrow.showRain)
        assertEquals("9am", tomorrow.rainSummary)
        assertFalse(dayAfter.showRain) // Only first rainy day shows text
        assertEquals("10am", dayAfter.rainSummary) // Summary still exists for click logic
    }

    @Test
    fun `prepareGraphDayInputs keeps handler rain metadata outside render day`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val prepared =
            DailyViewLogic.prepareGraphDayInputs(
                todayLabel = "Today",
                now = now,
                centerDate = today,
                today = today,
                weatherByDate = createWeatherMap(today),
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
                numColumns = 1,
                displaySource = WeatherSource.NWS,
                skipYesterday = false,
                skipHistory = true,
                rainSummaryProvider = { _, _, _, _ -> "9am" },
            ).single()

        assertEquals(today, prepared.renderDay.date)
        assertEquals("9am", prepared.rainSummary)
        assertTrue(prepared.hasRainForecast)
    }

    @Test
    fun `prepareGraphDays compositions triple line data for today`() {
        val now = LocalDateTime.of(2030, 6, 15, 20, 0) // 8 PM
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Official API says 80/60
        val weatherByDate = mapOf(
            today to createWeather(todayStr, highTemp = 80f, lowTemp = 60f)
        )
        
        // Hourly samples only reached 74/65
        val hourlyForecasts = (0..23).map { hour ->
            HourlyForecastEntity(
                dateTime = today.atTime(hour, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                condition = "Clear",
                source = WeatherSource.NWS.id,
                temperature = (65 + (hour % 10)).toFloat(), // Max 74
                locationLat = 0.0,
                locationLon = 0.0,
                fetchedAt = 1L
            )
        }

        val days = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipYesterday = true,
            skipHistory = false,
            hourlyForecasts = hourlyForecasts,
            dailyActuals = mapOf(
                today to extreme(today, 74f, 65f, "Sunny")
            )
        )

        val todayData = days.first { it.date == today }
        // Observed uses source-specific actuals; forecast stays API-specific.
        assertEquals(74f, todayData.solidLineHigh!!, 0.1f)
        assertEquals(65f, todayData.solidLineLow!!, 0.1f)
        assertEquals(80f, todayData.dashedLineHigh!!, 0.1f)
        assertEquals(60f, todayData.dashedLineLow!!, 0.1f)
        assertEquals(65f, todayData.bottomStackLow!!, 0.1f)
    }

    @Test
    fun `prepareGraphDays includes snapshot and current temp for today`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherByDate = mapOf(
            today to createWeather(todayStr, highTemp = 80f, lowTemp = 60f)
        )

        // Snapshot from 24h ago
        val snapshots = mapOf(
            today to listOf(
                createWeather(todayStr, highTemp = 82f, lowTemp = 62f).copy(
                    fetchedAt = now.minusHours(25).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                )
            )
        )

        val currentTemps = listOf(
            ObservationEntity(
                stationId = "NWS_BLEND",
                stationName = "Test Station",
                timestamp = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                temperature = 75f,
                condition = "Clear",
                locationLat = 0.0,
                locationLon = 0.0,
                fetchedAt = 1L,
                api = "NWS",
            )
        )

        val days = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = snapshots,
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = emptyMap(),
            currentTemps = currentTemps,
            currentTemp = 75f,
            observedAt = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        )

        val todayData = days.first { it.date == today }
        assertEquals(82f, todayData.snapshotHigh!!, 0.1f)
        assertEquals(62f, todayData.snapshotLow!!, 0.1f)
        // Observed High should include currentTemp (75) even if dailyActuals is empty
        assertEquals(75f, todayData.solidLineHigh!!, 0.1f)
        assertEquals(75f, todayData.solidLineLow!!, 0.1f)
        assertEquals(80f, todayData.dashedLineHigh!!, 0.1f)
        assertEquals(60f, todayData.dashedLineLow!!, 0.1f)
        assertEquals(75f, todayData.bottomStackLow!!, 0.1f)
    }

    @Test
    fun `prepareGraphDays today ignores Generic fallback snapshot when source snapshot is missing`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherByDate = mapOf(
            today to createWeather(todayStr, highTemp = 80f, lowTemp = 60f)
        )

        val snapshots = mapOf(
            today to listOf(
                createWeather(todayStr, highTemp = 62f, lowTemp = 48f).copy(
                    source = WeatherSource.GENERIC_GAP.id,
                    fetchedAt = now.minusHours(25).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                )
            )
        )

        val days = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = snapshots,
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = emptyMap(),
        )

        val todayData = days.first { it.date == today }
        assertEquals(null, todayData.snapshotHigh)
        assertEquals(null, todayData.snapshotLow)
        assertEquals(80f, todayData.dashedLineHigh!!, 0.1f)
        assertEquals(60f, todayData.dashedLineLow!!, 0.1f)
        assertEquals(60f, todayData.bottomStackLow!!, 0.1f)
    }

    @Test
    fun `prepareGraphDays today falls back to forecast when source actuals are missing`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherByDate = mapOf(
            today to createWeather(todayStr, highTemp = 80f, lowTemp = 60f)
        )

        val hourlyForecasts = listOf(
            HourlyForecastEntity(
                dateTime = today.atTime(5, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                condition = "Clear",
                source = WeatherSource.NWS.id,
                temperature = 60f,
                locationLat = 0.0,
                locationLon = 0.0,
                fetchedAt = 1L
            ),
            HourlyForecastEntity(
                dateTime = today.atTime(14, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                condition = "Sunny",
                source = WeatherSource.NWS.id,
                temperature = 74f,
                locationLat = 0.0,
                locationLon = 0.0,
                fetchedAt = 1L
            ),
        )

        val days = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = hourlyForecasts,
            dailyActuals = emptyMap(),
        )

        val todayData = days.first { it.date == today }
        assertEquals(80f, todayData.solidLineHigh!!, 0.1f)
        assertEquals(60f, todayData.solidLineLow!!, 0.1f)
        assertEquals(80f, todayData.dashedLineHigh!!, 0.1f)
        assertEquals(60f, todayData.dashedLineLow!!, 0.1f)
        assertEquals(60f, todayData.bottomStackLow!!, 0.1f)
        assertTrue(todayData.isTodayForecastFallback)
    }

    @Test
    fun `prepareGraphDays today bottom stack low uses lower observed or forecast low`() {
        val now = LocalDateTime.of(2030, 6, 15, 20, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherByDate = mapOf(
            today to createWeather(todayStr, highTemp = 80f, lowTemp = 65f)
        )

        val hourlyForecasts = (0..23).map { hour ->
            HourlyForecastEntity(
                dateTime = today.atTime(hour, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                condition = "Clear",
                source = WeatherSource.NWS.id,
                temperature = if (hour == 5) 67f else 72f,
                locationLat = 0.0,
                locationLon = 0.0,
                fetchedAt = 1L
            )
        }

        val days = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipYesterday = true,
            skipHistory = false,
            hourlyForecasts = hourlyForecasts,
            dailyActuals = mapOf(
                today to extreme(today, 74f, 67f, "Sunny")
            )
        )

        val todayData = days.first { it.date == today }
        assertEquals(74f, todayData.solidLineHigh!!, 0.1f)
        assertEquals(67f, todayData.solidLineLow!!, 0.1f)
        assertEquals(80f, todayData.dashedLineHigh!!, 0.1f)
        assertEquals(65f, todayData.dashedLineLow!!, 0.1f)

        assertEquals(67f, todayData.bottomStackLow!!, 0.1f)
    }

    @Test
    fun `prepareTextDays past day shows source-specific actuals`() {

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            yesterday to createWeather(yesterdayStr, highTemp = 77f, lowTemp = 56f)
        )
        val dailyActuals = mapOf(
            yesterday to extreme(yesterday, 80.9f, 55f, "Sunny")
        )

        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            dailyActuals = dailyActuals,
        )

        val yesterdayData = result.first { it.dateStr == yesterdayStr }
        assertEquals("80.9°", yesterdayData.highLabel)
        assertEquals("55°", yesterdayData.lowLabel)
    }

    @Test
    fun `prepareTextDays today falls back to forecast labels when source actuals are missing`() {

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(todayStr, highTemp = 80.9f, lowTemp = 60.2f)
        )

        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            dailyActuals = emptyMap(),
        )

        val todayData = result.first { it.dateStr == todayStr }
        assertEquals("80.9°", todayData.highLabel)
        assertEquals("60.2°", todayData.lowLabel)
        assertTrue(todayData.isTodayForecastFallback)
    }

    @Test
    fun `prepareGraphDays past day shows source-specific actuals`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            yesterday to createWeather(yesterdayStr, highTemp = 77f, lowTemp = 56f)
        )
        val dailyActuals = mapOf(
            yesterday to extreme(yesterday, 80.9f, 55f, "Sunny")
        )

        val days = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val yesterdayData = days.first { it.date == yesterday }
        assertEquals(80.9f, yesterdayData.solidLineHigh!!, 0.1f)
        assertEquals(55f, yesterdayData.solidLineLow!!, 0.1f)
    }

    @Test
    fun `prepareGraphDays today icon prefers native daily token over hourly condition`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(todayStr).copy(
                condition = "Rain",
                source = WeatherSource.VISUAL_CROSSING.id,
                nativeDailyIconToken = "partly-cloudy-day",
            )
        )
        val hourlyForecasts = listOf(
            HourlyForecastEntity(
                dateTime = epoch("${todayStr}T12:00"),
                locationLat = 37.7749,
                locationLon = -122.4194,
                temperature = 64f,
                condition = "Rain",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                cloudCover = 90,
                fetchedAt = 1L,
            ),
            HourlyForecastEntity(
                dateTime = epoch("${todayStr}T13:00"),
                locationLat = 37.7749,
                locationLon = -122.4194,
                temperature = 66f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                cloudCover = 0,
                fetchedAt = 1L,
            )
        )

        val days = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.VISUAL_CROSSING,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = hourlyForecasts,
        )

        val todayData = days.first { it.date == today }
        assertEquals(R.drawable.ic_weather_partly_cloudy, todayData.iconRes)
    }

    @Test
    fun `prepareTextDays marks generic fallback days`() {

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val tomorrowStr = tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            tomorrow to createWeather(tomorrowStr).copy(source = WeatherSource.GENERIC_GAP.id, isClimateNormal = true)
        )

        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 3,
            displaySource = WeatherSource.NWS
        )

        assertTrue(result.first { it.dateStr == tomorrowStr }.isSourceGapFallback)
    }

    @Test
    fun `prepareGraphDays marks generic fallback days`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val tomorrowStr = tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            tomorrow to createWeather(tomorrowStr).copy(source = WeatherSource.GENERIC_GAP.id, isClimateNormal = true)
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        assertTrue(result.first { it.date == tomorrow }.isSourceGapFallback)
    }

    @Test
    fun `prepareGraphDays returns all slots when middle days are missing`() {
        // dayOffsets for numColumns=5 are [-1, 0, 1, 2, 3]; only yesterday and today+2 have data
        // With current behavior, all 5 slots are returned to maintain grid stability.
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val skipped = today.plusDays(2)

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = mapOf(
                yesterday to createWeather(yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)),
                skipped   to createWeather(skipped.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            ),
            forecastSnapshots = emptyMap(),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        assertEquals("should have 5 slots", 5, result.size)
        assertEquals("yesterday should be columnIndex 0", 0, result[0].columnIndex)
        assertEquals("today+2 should be columnIndex 3", 3, result[3].columnIndex)
    }

    @Test
    fun `buildDayClickIntent returns correct extras with Robolectric`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val date = LocalDate.of(2030, 6, 16) // Tomorrow
        val dateStr = date.toString()

        val intent = DailyClickHandlerFactory.buildDayClickIntent(
            context = context,
            appWidgetId = 42,
            dayIndex = 1,
            date = date,
            iconRes = R.drawable.ic_weather_rain,
            lat = 37.0,
            lon = -122.0,
            displaySource = WeatherSource.NWS,
            now = now,
            precipProbability = 16,
        )

        assertEquals("com.weatherwidget.ACTION_DAY_CLICK", intent.action)
        assertEquals(dateStr, intent.getStringExtra("date"))
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("PRECIPITATION", intent.getStringExtra("com.weatherwidget.EXTRA_TARGET_VIEW"))
        assertEquals(24, intent.getIntExtra("com.weatherwidget.EXTRA_HOURLY_OFFSET", -1))
    }

    @Test
    fun `buildDayClickIntent tomorrow cloudy icon navigates to temperature`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val date = LocalDate.of(2030, 6, 16) // Tomorrow

        val intent = DailyClickHandlerFactory.buildDayClickIntent(
            context = context,
            appWidgetId = 42,
            dayIndex = 1,
            date = date,
            iconRes = R.drawable.ic_weather_mostly_clear,
            lat = 37.0,
            lon = -122.0,
            displaySource = WeatherSource.NWS,
            now = now
        )

        assertEquals("com.weatherwidget.ACTION_DAY_CLICK", intent.action)
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("TEMPERATURE", intent.getStringExtra("com.weatherwidget.EXTRA_TARGET_VIEW"))
        assertEquals(24, intent.getIntExtra("com.weatherwidget.EXTRA_HOURLY_OFFSET", -1))
    }

    @Test
    fun `buildDayClickIntent past day navigates to temperature graph`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val date = LocalDate.of(2030, 6, 14) // Yesterday

        val intent = DailyClickHandlerFactory.buildDayClickIntent(
            context = context,
            appWidgetId = 42,
            dayIndex = 1,
            date = date,
            iconRes = R.drawable.ic_weather_clear,
            lat = 37.0,
            lon = -122.0,
            displaySource = WeatherSource.NWS,
            now = now
        )

        assertEquals("com.weatherwidget.ACTION_DAY_CLICK", intent.action)
        assertTrue(intent.getBooleanExtra("isHistory", false))
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("TEMPERATURE", intent.getStringExtra(com.weatherwidget.widget.WidgetActions.EXTRA_TARGET_VIEW))
        assertEquals(37.0, intent.getDoubleExtra(com.weatherwidget.ui.ForecastHistoryActivity.EXTRA_LAT, 0.0), 0.1)
    }

    // partialPush=true must deliver the paint via partiallyUpdateAppWidget (in-place patch, no
    // launcher re-inflate flash — the flash the user saw on every background refresh cycle);
    // the default (interaction/onUpdate paths) must stay a full updateAppWidget so those paths
    // still (re)establish the launcher's view hierarchy and persisted RemoteViews.
    @Test
    fun `worker repaint partialPush routes partially once the widget is backed this process`() = runBlocking {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherList = listOf(createWeather(todayStr, highTemp = 62f, lowTemp = 51f))
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(77)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        suspend fun render(captured: com.weatherwidget.testutil.CapturedWidgetViews, partial: Boolean) =
            DailyViewHandler.updateWidget(
                context = context,
                appWidgetManager = captured.appWidgetManager,
                appWidgetId = 77,
                weatherData = WeatherData(
                    weatherList = weatherList,
                    forecastSnapshots = emptyMap(),
                    hourlyForecasts = emptyList(),
                ),
                observationData = ObservationData(),
                now = LocalDateTime.now(),
                startupToken = null,
                stateManagerNullable = null,
                repository = null,
                partialPush = partial,
            )

        // Back widget 77 with a full push first (the framework requires one before it honours any
        // partial), then the steady-state partial repaint routes via partiallyUpdateAppWidget.
        render(mockAppWidgetManager(widgetId = 77, widthDp = 140, heightDp = 90), partial = false)
        val captured = mockAppWidgetManager(widgetId = 77, widthDp = 140, heightDp = 90)
        render(captured, partial = true)

        assertTrue(captured.partialViewsSlot.isCaptured)
        assertFalse(captured.viewsSlot.isCaptured)
    }

    @Test
    fun `worker repaint partialPush is promoted to full when unbacked this process`() = runBlocking {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherList = listOf(createWeather(todayStr, highTemp = 62f, lowTemp = 51f))
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(79)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val captured = mockAppWidgetManager(widgetId = 79, widthDp = 140, heightDp = 90)

        // First push of the process for this widget: an unbacked complete-body partial would be
        // silently dropped by the framework, so WidgetPushDispatcher promotes it to a full update.
        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = captured.appWidgetManager,
            appWidgetId = 79,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
            ),
            observationData = ObservationData(),
            now = LocalDateTime.now(),
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
            partialPush = true,
        )

        assertTrue(captured.viewsSlot.isCaptured)
        assertFalse(captured.partialViewsSlot.isCaptured)
    }

    @Test
    fun `default repaint pushes via full updateAppWidget`() = runBlocking {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherList = listOf(createWeather(todayStr, highTemp = 62f, lowTemp = 51f))
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(78)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val captured = mockAppWidgetManager(widgetId = 78, widthDp = 140, heightDp = 90)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = captured.appWidgetManager,
            appWidgetId = 78,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
            ),
            observationData = ObservationData(),
            now = LocalDateTime.now(),
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        assertTrue(captured.viewsSlot.isCaptured)
        assertFalse(captured.partialViewsSlot.isCaptured)
    }

    @Test
    fun `updateWidget text labels use integer format when value is whole`() = runBlocking {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherList =
            listOf(
                createWeather(todayStr, highTemp = 62f, lowTemp = 51f),
                createWeather(tomorrowStr, highTemp = 62f, lowTemp = 51f),
            )
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(42)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 42, widthDp = 140, heightDp = 90)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 42,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
            ),
            observationData = ObservationData(),
            now = LocalDateTime.now(),
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        val highTexts = listOf(R.id.day1_high, R.id.day2_high, R.id.day3_high).mapNotNull { id ->
            applied.findViewById<TextView>(id)?.text?.toString()
        }
        val lowTexts = listOf(R.id.day1_low, R.id.day2_low, R.id.day3_low).mapNotNull { id ->
            applied.findViewById<TextView>(id)?.text?.toString()
        }

        assertTrue(highTexts.contains("62°"))
        assertTrue(lowTexts.contains("51°"))
    }

    @Test
    fun `single row daily text mode exposes only api and settings controls`() = runBlocking {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherList =
            listOf(
                createWeather(todayStr, highTemp = 62f, lowTemp = 51f),
            )
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(142)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 142, widthDp = 200, heightDp = 90)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 142,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
            ),
            observationData = ObservationData(),
            now = LocalDateTime.now(),
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)

        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.text_mode_api_source_container).visibility)
        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.text_mode_api_touch_zone).visibility)
        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.text_mode_settings_icon).visibility)
        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.text_mode_settings_touch_zone).visibility)

        assertEquals(View.GONE, applied.findViewById<View>(R.id.top_right_header_container).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.api_touch_zone).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.settings_icon).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.settings_touch_zone).visibility)

        assertEquals(View.GONE, applied.findViewById<View>(R.id.nav_left).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.nav_left_zone).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.nav_right).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.nav_right_zone).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.current_temp_zone).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.precip_touch_zone).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_day_zones).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_hour_zones).visibility)

        val textContainer = applied.findViewById<View>(R.id.text_container)
        val expectedRightPadding = WidgetSizeCalculator.dpToPx(context, 18)
        assertEquals(0, textContainer.paddingLeft)
        assertEquals(expectedRightPadding, textContainer.paddingRight)
    }

    @Test
    fun `single row widget renders daily text even when stored mode is hourly`() = runBlocking {
        val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherList =
            listOf(
                createWeather(todayStr, highTemp = 62f, lowTemp = 51f),
            )
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(143)
        stateManager.setViewMode(143, ViewMode.TEMPERATURE)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 143, widthDp = 200, heightDp = 90)

        WidgetRenderer.updateWidgetWithData(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 143,
            weatherList = weatherList,
            forecastSnapshots = emptyMap(),
            hourlyForecasts = emptyList(),
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)

        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.text_container).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.graph_view).visibility)
        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.text_mode_api_source_container).visibility)
        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.text_mode_settings_touch_zone).visibility)
    }

    @Test
    fun `wide single row daily text mode can expose eighth day column`() = runBlocking {
        val now = LocalDateTime.of(2030, 6, 15, 7, 0)
        val today = now.toLocalDate()
        val weatherList = (-1..6).map { offset ->
            createWeather(today.plusDays(offset.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE))
        }
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(144)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 144, widthDp = 520, heightDp = 90)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 144,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
            ),
            observationData = ObservationData(),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)

        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.day8_container).visibility)
        assertEquals(
            today.plusDays(6).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
            applied.findViewById<TextView>(R.id.day8_label).text.toString(),
        )
    }

    @Test
    fun `updateWidget text labels show today forecast without source actuals at Noon`() = runBlocking {
        val now = LocalDateTime.of(2026, 3, 2, 12, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = now.toLocalDate().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        
        val weatherList =
            listOf(
                createWeather(todayStr, highTemp = 62.9f, lowTemp = 51.2f).copy(source = WeatherSource.OPEN_METEO.id),
                createWeather(tomorrowStr, highTemp = 62.9f, lowTemp = 51.2f).copy(source = WeatherSource.OPEN_METEO.id),
            )
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(43)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.OPEN_METEO, WeatherSource.NWS, WeatherSource.WEATHER_API))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 43, widthDp = 200, heightDp = 90)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 43,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = listOf(
                    HourlyForecastEntity(epoch(todayStr + "T14:00"), 0.0, 0.0, 62.9f, "Sunny", "OPEN_METEO", 0, 0, null, 1L),
                    HourlyForecastEntity(epoch(todayStr + "T05:00"), 0.0, 0.0, 51.2f, "Clear", "OPEN_METEO", 0, 0, null, 1L)
                ),
            ),
            observationData = ObservationData(),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        
        val highTexts = listOf(R.id.day1_high, R.id.day2_high, R.id.day3_high).mapNotNull { id ->
            applied.findViewById<TextView>(id)?.text?.toString()
        }
        
        assertTrue("Noon highTexts $highTexts should contain today's forecast value", highTexts.contains("62.9°"))
    }

    @Test
    fun `updateWidget text labels show today forecast without source actuals in Evening`() = runBlocking {
        val now = LocalDateTime.of(2026, 3, 2, 20, 0) // 8 PM
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = now.toLocalDate().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        
        val weatherList =
            listOf(
                createWeather(todayStr, highTemp = 62.9f, lowTemp = 51.2f).copy(source = WeatherSource.OPEN_METEO.id),
                createWeather(tomorrowStr, highTemp = 62.9f, lowTemp = 51.2f).copy(source = WeatherSource.OPEN_METEO.id),
            )
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(44)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.OPEN_METEO, WeatherSource.NWS, WeatherSource.WEATHER_API))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 44, widthDp = 200, heightDp = 90)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 44,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = listOf(
                    HourlyForecastEntity(epoch(todayStr + "T14:00"), 0.0, 0.0, 62.9f, "Sunny", "OPEN_METEO", 0, 0, null, 1L),
                    HourlyForecastEntity(epoch(todayStr + "T05:00"), 0.0, 0.0, 51.2f, "Clear", "OPEN_METEO", 0, 0, null, 1L)
                ),
            ),
            observationData = ObservationData(),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        
        val highTexts = listOf(R.id.day1_high, R.id.day2_high, R.id.day3_high, R.id.day4_high).mapNotNull { id ->
            applied.findViewById<TextView>(id)?.text?.toString()
        }
        
        // Forecast temperatures show the tenth (".0" suppressed) like actuals.
        assertTrue("Evening highTexts $highTexts should contain 62.9° for Today's 62.9° forecast", highTexts.contains("62.9°"))
    }

    /**
     * Repository whose only observation is 70°F exactly 24h before [observedAtMs]. The daily header
     * delta is the delta from yesterday (post-swap), so the header badge reads "+1.0" against a
     * 71°F current observation.
     */
    private fun repositoryWithYesterdayObservation(observedAtMs: Long): WeatherRepository {
        val repository = mockk<WeatherRepository>(relaxed = true)
        val observation = ObservationEntity(
            stationId = "TST",
            stationName = "Test Station",
            timestamp = observedAtMs - 24L * 60 * 60 * 1000,
            temperature = 70f,
            condition = "Clear",
            locationLat = 37.7749,
            locationLon = -122.4194,
            fetchedAt = 1L,
            api = "NWS",
        )
        coEvery { repository.getObservationsInRange(any(), any(), any(), any()) } returns listOf(observation)
        return repository
    }

    @Test
    fun `updateWidget daily header shows delta when precip is absent`() = runBlocking {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(47)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 47, widthDp = 200, heightDp = 150)
        val observedAtMs = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val repository = repositoryWithYesterdayObservation(observedAtMs)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 47,
            weatherData = WeatherData(
                weatherList = listOf(createWeather(todayStr, precipProbability = 0, highTemp = 70f, lowTemp = 55f)),
                forecastSnapshots = emptyMap(),
                hourlyForecasts = listOf(
                    HourlyForecastEntity(epoch("${todayStr}T12:00"), 37.7749, -122.4194, 70f, "Clear", WeatherSource.NWS.id, 0, 0, null, 1L),
                    HourlyForecastEntity(epoch("${todayStr}T13:00"), 37.7749, -122.4194, 72f, "Clear", WeatherSource.NWS.id, 0, 0, null, 1L),
                ),
                currentTemps = listOf(
                    ObservationEntity(
                        stationId = "NWS_BLEND",
                        stationName = "Test Station",
                        timestamp = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        temperature = 71f,
                        condition = "Clear",
                        locationLat = 37.7749,
                        locationLon = -122.4194,
                        fetchedAt = 1L,
                        api = "NWS",
                    ),
                ),
            ),
            observationData = ObservationData(
                lastObservedTemp = 71f,
                observedAt = observedAtMs,
            ),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = repository,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        val deltaBadge = applied.findViewById<TextView>(R.id.current_temp_delta)

        assertEquals(View.VISIBLE, deltaBadge.visibility)
        assertEquals("+1.0", deltaBadge.text.toString())
        assertEquals(Color.parseColor("#FF6B35"), deltaBadge.currentTextColor)
    }

    @Test
    fun `updateWidget daily header shows delta when precip is visible`() = runBlocking {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(48)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 48, widthDp = 200, heightDp = 150)
        val observedAtMs = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val repository = repositoryWithYesterdayObservation(observedAtMs)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 48,
            weatherData = WeatherData(
                weatherList = listOf(createWeather(todayStr, precipProbability = 65, highTemp = 70f, lowTemp = 55f)),
                forecastSnapshots = emptyMap(),
                hourlyForecasts = listOf(
                    HourlyForecastEntity(epoch("${todayStr}T12:00"), 37.7749, -122.4194, 70f, "Clear", WeatherSource.NWS.id, 65, 0, null, 1L),
                    HourlyForecastEntity(epoch("${todayStr}T13:00"), 37.7749, -122.4194, 72f, "Clear", WeatherSource.NWS.id, 65, 0, null, 1L),
                ),
                currentTemps = listOf(
                    ObservationEntity(
                        stationId = "NWS_BLEND",
                        stationName = "Test Station",
                        timestamp = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        temperature = 71f,
                        condition = "Clear",
                        locationLat = 37.7749,
                        locationLon = -122.4194,
                        fetchedAt = 1L,
                        api = "NWS",
                    ),
                ),
            ),
            observationData = ObservationData(
                lastObservedTemp = 71f,
                observedAt = observedAtMs,
            ),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = repository,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        val deltaBadge = applied.findViewById<TextView>(R.id.current_temp_delta)
        val precipBadge = applied.findViewById<TextView>(R.id.precip_probability)

        assertEquals(View.VISIBLE, deltaBadge.visibility)
        assertEquals("+1.0", deltaBadge.text.toString())
        assertEquals(View.VISIBLE, precipBadge.visibility)
        assertEquals("65%", precipBadge.text.toString())
    }

    @Test
    fun `computeMissingDataRefreshes requests actuals today when daily actuals missing`() {
        val today = LocalDate.of(2030, 6, 15)

        val decisions = computeMissingDataRefreshes(
            today = today,
            displaySource = WeatherSource.NWS,
            dailyActuals = emptyMap(),
        )

        assertEquals(1, decisions.size)
        assertEquals("actuals_today", decisions[0].refreshType)
        assertTrue(decisions[0].forceRefresh)
        assertEquals("missing_actuals_NWS_today", decisions[0].reason)
    }

    @Test
    fun `computeMissingDataRefreshes requests actuals history when past graph day lacks actuals`() {
        val today = LocalDate.of(2030, 6, 15)
        val yesterday = today.minusDays(1)

        val displayDays = listOf(
            DailyForecastGraphRenderer.DayData(
                date = yesterday,
                label = "Sat",
                solidLineHigh = 68f,
                solidLineLow = 54f,
                dashedLineHigh = 68f,
                dashedLineLow = 54f,
                isPast = true,
                isToday = false,
                iconRes = 0,
            ),
        )

        val decisions = computeMissingDataRefreshes(
            today = today,
            displaySource = WeatherSource.NWS,
            dailyActuals = mapOf(today to extreme(today, 70f, 55f, "Clear")),
            visibleDates = setOf(yesterday),
        )

        val historyDecision = decisions.find { it.refreshType == "actuals_history" }
        assertTrue(historyDecision != null)
        assertTrue(historyDecision!!.forceRefresh)
        assertEquals("missing_actuals_NWS_history", historyDecision.reason)
    }

    @Test
    fun `computeMissingDataRefreshes requests today snapshot when forecast exists but no snapshot`() {
        val today = LocalDate.of(2030, 6, 15)

        val displayDays = listOf(
            DailyForecastGraphRenderer.DayData(
                date = today,
                label = "Sun",
                solidLineHigh = 70f,
                solidLineLow = 55f,
                dashedLineHigh = 70f,
                dashedLineLow = 55f,
                snapshotHigh = null,
                snapshotLow = null,
                isPast = false,
                isToday = true,
                iconRes = 0,
            ),
        )

        val decisions = computeMissingDataRefreshes(
            today = today,
            displaySource = WeatherSource.NWS,
            dailyActuals = mapOf(today to extreme(today, 70f, 55f, "Clear")),
            todayHasForecast = true,
            todayHasSnapshot = false,
        )

        val snapshotDecision = decisions.find { it.refreshType == "today_snapshot" }
        assertTrue(snapshotDecision != null)
        assertFalse(snapshotDecision!!.forceRefresh)
        assertEquals("missing_today_snapshot_NWS", snapshotDecision.reason)
    }

    @Test
    fun `computeMissingDataRefreshes returns empty when all data present`() {
        val today = LocalDate.of(2030, 6, 15)

        val displayDays = listOf(
            DailyForecastGraphRenderer.DayData(
                date = today,
                label = "Sun",
                solidLineHigh = 70f,
                solidLineLow = 55f,
                dashedLineHigh = 70f,
                dashedLineLow = 55f,
                snapshotHigh = 68f,
                snapshotLow = 52f,
                isPast = false,
                isToday = true,
                iconRes = 0,
            ),
        )

        val decisions = computeMissingDataRefreshes(
            today = today,
            displaySource = WeatherSource.NWS,
            dailyActuals = mapOf(today to extreme(today, 70f, 55f, "Clear")),
            todayHasForecast = true,
            todayHasSnapshot = true,
        )

        assertTrue(decisions.isEmpty())
    }

    @Test
    fun `classifyBlockingSourceWarning returns key missing warning for keyed source without data`() {
        val warning = ApiSourceWarningHelper.classifyBlockingSourceWarning(
            displaySource = WeatherSource.OPEN_WEATHER_MAP,
            hasSelectedSourceData = false,
            latestFailureMessages = listOf("source=OPEN_WEATHER_MAP code=ACCESS_ERROR detail=OpenWeatherMap API key missing. Add OPEN_WEATHER_MAP_API_KEY to local.properties or the environment."),
        )

        assertEquals("OWM key missing", warning?.headerText)
        assertEquals("API key missing.", warning?.summaryText)
        assertEquals("OWM key", warning?.sourceLabelText)
        assertEquals("OpenWeatherMap API key missing. Add OPEN_WEATHER_MAP_API_KEY to local.properties or the environment.", warning?.detailText)
        assertEquals("OpenWeatherMap key missing. OpenWeatherMap API key missing. Add OPEN_WEATHER_MAP_API_KEY to local.properties or the environment.", warning?.toastMessage)
    }

    @Test
    fun `classifyBlockingSourceWarning returns 401 warning with details when source unauthorized`() {
        val warning = ApiSourceWarningHelper.classifyBlockingSourceWarning(
            displaySource = WeatherSource.OPEN_WEATHER_MAP,
            hasSelectedSourceData = false,
            latestFailureMessages = listOf(
                "source=OPEN_WEATHER_MAP code=HTTP_401 detail=Please note that using One Call 3.0 requires a separate subscription to the One Call by Call plan. Learn more here https://openweathermap.org/price.",
            ),
        )

        assertEquals("OWM 401 error", warning?.headerText)
        assertEquals("One Call 3.0 subscription required.", warning?.summaryText)
        assertEquals("OWM 401", warning?.sourceLabelText)
        assertEquals(
            "Please note that using One Call 3.0 requires a separate subscription to the One Call by Call plan. Learn more here https://openweathermap.org/price.",
            warning?.detailText,
        )
        assertEquals(
            "OpenWeatherMap 401 error. Please note that using One Call 3.0 requires a separate subscription to the One Call by Call plan. Learn more here https://openweathermap.org/price.",
            warning?.toastMessage,
        )
    }

    @Test
    fun `classifyBlockingSourceWarning ignores failures when source data exists`() {
        val warning = ApiSourceWarningHelper.classifyBlockingSourceWarning(
            displaySource = WeatherSource.OPEN_WEATHER_MAP,
            hasSelectedSourceData = true,
            latestFailureMessages = listOf("source=OPEN_WEATHER_MAP code=HTTP_401 detail=One Call 3.0 subscription required."),
        )

        assertNull(warning)
    }

    @Test
    fun `classifyBlockingSourceWarning ignores transient failure text`() {
        val warning = ApiSourceWarningHelper.classifyBlockingSourceWarning(
            displaySource = WeatherSource.OPEN_WEATHER_MAP,
            hasSelectedSourceData = false,
            latestFailureMessages = listOf("timeout contacting upstream"),
        )

        assertNull(warning)
    }

    @Test
    fun `computeMissingDataRefreshes does not request actuals today when daily actuals present`() {
        val today = LocalDate.of(2030, 6, 15)

        val decisions = computeMissingDataRefreshes(
            today = today,
            displaySource = WeatherSource.NWS,
            dailyActuals = mapOf(today to extreme(today, 71f, 54f, "Clear")),
        )

        assertTrue(decisions.none { it.refreshType == "actuals_today" })
    }

    @Test
    fun `available navigation dates ignore non-selected source forecasts`() {
        val today = LocalDate.of(2030, 6, 15)
        val selectedTomorrow = today.plusDays(1)
        val otherFuture = today.plusDays(6)
        val genericGapFuture = today.plusDays(2)
        val selectedHistory = today.minusDays(1)

        val dates = DailyViewHandler.buildAvailableNavigationDates(
            weatherList = listOf(
                createWeather(today.toString(), source = WeatherSource.NWS.id),
                createWeather(selectedTomorrow.toString(), source = WeatherSource.NWS.id),
                createWeather(otherFuture.toString(), source = WeatherSource.OPEN_METEO.id),
                createWeather(genericGapFuture.toString(), source = WeatherSource.GENERIC_GAP.id),
            ),
            dailyActuals = mapOf(
                selectedHistory to extreme(selectedHistory, 70f, 50f, "Sunny")
            ),
            displaySource = WeatherSource.NWS,
        )

        assertTrue(dates.contains(today))
        assertTrue(dates.contains(selectedTomorrow))
        assertTrue(dates.contains(genericGapFuture))
        assertTrue(dates.contains(selectedHistory))
        assertFalse(dates.contains(otherFuture))
    }

    @Test
    fun `text mode missing data refresh ignores loaded past dates that are not visible`() = runBlocking {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(52)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val db = WeatherDatabase.getDatabase(context)
        db.appLogDao().clearAllLogs()
        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 52, widthDp = 90, heightDp = 90)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 52,
            weatherData = WeatherData(
                weatherList = listOf(
                    createWeather(yesterday.toString(), source = WeatherSource.NWS.id),
                    createWeather(today.toString(), source = WeatherSource.NWS.id),
                ),
                forecastSnapshots = mapOf(
                    today to listOf(createWeather(today.toString(), source = WeatherSource.NWS.id)),
                ),
                hourlyForecasts = emptyList(),
                dailyActualsBySource = mapOf(
                    WeatherSource.NWS.id to mapOf(
                        today to extreme(today, 71f, 54f, "Sunny")
                    )
                )
            ),
            observationData = ObservationData(),
            now = now,
            startupToken = null,
            stateManagerNullable = stateManager,
            repository = null,
        )

        assertTrue("widget should have rendered", viewsSlot.isCaptured)
        val missingActualLogs = db.appLogDao().getLogsByTag("MISSING_ACTUALS_FETCH", limit = 20)
        assertTrue(
            "Expected no missing actuals refresh for a loaded but non-visible past date; logs=${missingActualLogs.map { it.message }}",
            missingActualLogs.none { it.message.contains("actuals_history") },
        )
    }

    @Test
    fun `resolveTodayHeaderForecast prefers next hour over current hour`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

        val forecast = DailyHeaderBinder.resolveTodayHeaderForecast(
            now = now,
            hourlyForecasts = listOf(
                HourlyForecastEntity(epoch("${todayStr}T12:00"), 37.7749, -122.4194, 64f, "Rain", WeatherSource.NWS.id, 0, 90, null, 1L),
                HourlyForecastEntity(epoch("${todayStr}T13:00"), 37.7749, -122.4194, 66f, "Clear", WeatherSource.NWS.id, 0, 0, null, 1L),
            ),
            displaySource = WeatherSource.NWS,
        )

        assertEquals("Clear", forecast?.condition)
        assertEquals(epoch("${todayStr}T13:00"), forecast?.dateTime)
    }

    @Test
    fun `resolveTodayHeaderForecast falls back to current hour when next hour missing`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

        val forecast = DailyHeaderBinder.resolveTodayHeaderForecast(
            now = now,
            hourlyForecasts = listOf(
                HourlyForecastEntity(epoch("${todayStr}T12:00"), 37.7749, -122.4194, 64f, "Partly Cloudy", WeatherSource.NWS.id, 0, 40, null, 1L),
            ),
            displaySource = WeatherSource.NWS,
        )

        assertEquals("Partly Cloudy", forecast?.condition)
        assertEquals(epoch("${todayStr}T12:00"), forecast?.dateTime)
    }

    @Test
    fun `updateWidget daily header icon prefers current hourly forecast for today`() = runBlocking {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherList = listOf(
            createWeather(todayStr, highTemp = 70f, lowTemp = 55f).copy(
                condition = "Rain",
                source = WeatherSource.WEATHER_API.id,
                nativeDailyIconToken = "1003",
            )
        )
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(45)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.WEATHER_API, WeatherSource.NWS, WeatherSource.OPEN_METEO))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 45, widthDp = 300, heightDp = 200)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 45,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = listOf(
                    HourlyForecastEntity(epoch("${todayStr}T12:00"), 37.7749, -122.4194, 64f, "Rain", WeatherSource.WEATHER_API.id, 0, 90, null, 1L),
                    HourlyForecastEntity(epoch("${todayStr}T13:00"), 37.7749, -122.4194, 66f, "Clear", WeatherSource.WEATHER_API.id, 0, 0, null, 1L),
                ),
            ),
            observationData = ObservationData(),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        val imageView = applied.findViewById<ImageView>(R.id.weather_icon)
        val shadowDrawable = shadowOf(imageView.drawable)
        if (shadowDrawable.createdFromResId != -1) {
            assertEquals(R.drawable.ic_weather_clear, shadowDrawable.createdFromResId)
        } else {
            assertNotNull(imageView.drawable)
        }
    }

    @Test
    fun `updateWidget today text icon prefers native daily token`() = runBlocking {
        val now = LocalDateTime.of(2030, 6, 15, 7, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = now.toLocalDate().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherList = listOf(
            createWeather(todayStr, highTemp = 70f, lowTemp = 55f).copy(
                condition = "Rain",
                source = WeatherSource.WEATHER_API.id,
                nativeDailyIconToken = "1003",
            ),
            createWeather(tomorrowStr, highTemp = 71f, lowTemp = 56f).copy(
                condition = "Clear",
                source = WeatherSource.WEATHER_API.id,
                nativeDailyIconToken = "1000",
            ),
        )
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(46)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.WEATHER_API, WeatherSource.NWS, WeatherSource.OPEN_METEO))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 46, widthDp = 200, heightDp = 90)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 46,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = listOf(
                    HourlyForecastEntity(epoch("${todayStr}T12:00"), 37.7749, -122.4194, 64f, "Rain", WeatherSource.WEATHER_API.id, 0, 90, null, 1L),
                    HourlyForecastEntity(epoch("${todayStr}T13:00"), 37.7749, -122.4194, 66f, "Clear", WeatherSource.WEATHER_API.id, 0, 0, null, 1L)
                ),
            ),
            observationData = ObservationData(),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        val todayImageView = applied.findViewById<ImageView>(R.id.day2_icon)

        assertEquals(R.drawable.ic_weather_partly_cloudy, shadowOf(todayImageView.drawable).createdFromResId)
    }

    @Test
    fun `updateWidget graph mode hides hourly tap zones to prevent stale CYCLE_ZOOM`() = runBlocking {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = now.toLocalDate().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(50)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        // 200x200 gives 2+ rows → graph mode
        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 50, widthDp = 200, heightDp = 200)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 50,
            weatherData = WeatherData(
                weatherList = listOf(
                    createWeather(todayStr, highTemp = 70f, lowTemp = 55f),
                    createWeather(tomorrowStr, highTemp = 72f, lowTemp = 56f),
                ),
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
            ),
            observationData = ObservationData(),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)

        // These views carry ACTION_CYCLE_ZOOM handlers from TemperatureViewHandler.
        // DailyViewHandler must explicitly hide them to prevent stale PendingIntents
        // from firing when the user taps the daily graph.
        val bodyTapZone = applied.findViewById<View>(R.id.graph_body_tap_zone)
        val hourZones = applied.findViewById<View>(R.id.graph_hour_zones)

        assertEquals("graph_body_tap_zone must be GONE in daily mode", View.GONE, bodyTapZone.visibility)
        assertEquals("graph_hour_zones must be GONE in daily mode", View.GONE, hourZones.visibility)
    }

    @Test
    fun `DailyViewHandler uses provided lastObservedTemp`() = runBlocking {
        val now = LocalDateTime.of(2026, 3, 23, 12, 0)
        val today = now.toLocalDate()
        val weatherList = listOf(
            com.weatherwidget.testutil.TestData.forecast(targetDate = today.toString(), source = WeatherSource.NWS.id, highTemp = 75f, lowTemp = 55f)
        )
        val hourlyForecasts = listOf(
            com.weatherwidget.testutil.TestData.hourly(dateTime = "2026-03-23T12:00", temperature = 70f, source = WeatherSource.NWS.id)
        )

        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(51)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val (appWidgetManager, viewsSlot) = mockAppWidgetManager(widgetId = 51, widthDp = 200, heightDp = 90)

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 51,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = hourlyForecasts,
            ),
            observationData = ObservationData(
                lastObservedTemp = 72.5f,
                observedAt = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        // Real CurrentTemperatureResolver: estimated=70 + delta(72.5-70)=2.5 → display=72.5
        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        val currentTempText = applied.findViewById<TextView>(R.id.current_temp)?.text?.toString()
        assertEquals("72.5°", currentTempText)
    }

    private fun createWeatherMap(today: LocalDate): Map<LocalDate, ForecastEntity> {
        return (-1..6).associate { offset ->
            val date = today.plusDays(offset.toLong())
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            date to createWeather(dateStr)
        }
    }

    private fun createWeather(
        date: String,
        precipProbability: Int? = 0,
        highTemp: Float? = 70f,
        lowTemp: Float? = 55f,
        source: String = WeatherSource.NWS.id,
    ): ForecastEntity {
        return ForecastEntity(
            targetDate = dateEpoch(date),
            dateOfPrediction = dateEpoch(date),
            locationLat = 37.7749,
            locationLon = -122.4194,
            highTemp = highTemp,
            lowTemp = lowTemp,
            condition = "Clear",
            source = source,
            precipProbability = precipProbability,
            fetchedAt = 1L,
        )
    }
}

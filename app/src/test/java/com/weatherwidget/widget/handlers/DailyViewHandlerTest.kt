package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.RainAnalyzer
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyViewHandlerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(RainAnalyzer)
    }

    @After
    fun teardown() {
        unmockkObject(RainAnalyzer)
    }

    @Test
    fun `prepareTextDays numColumns=2 shows only 2 slots`() {
        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } returns null

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = createWeatherMap(today)

        val result = DailyViewLogic.prepareTextDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 2,
            displaySource = WeatherSource.NWS
        )

        assertEquals(7, result.size)
        assertEquals(2, result.count { it.isVisible })
        assertTrue(result[1].isVisible) // today
        assertTrue(result[2].isVisible) // tomorrow
    }

    @Test
    fun `prepareTextDays skipHistory shifts visible dates`() {
        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } returns null

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = createWeatherMap(today)

        val result = DailyViewLogic.prepareTextDays(
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
    fun `prepareTextDays identifies first rainy day for text display`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        
        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } answers {
            val date = secondArg<LocalDate>()
            if (date == today.plusDays(1)) "9am"
            else if (date == today.plusDays(2)) "10am"
            else null
        }

        val weatherByDate = createWeatherMap(today)
        val result = DailyViewLogic.prepareTextDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 5,
            displaySource = WeatherSource.NWS
        )

        val tomorrow = result.first { it.date == today.plusDays(1) }
        val dayAfter = result.first { it.date == today.plusDays(2) }

        assertTrue(tomorrow.showRain)
        assertEquals("9am", tomorrow.rainSummary)
        assertFalse(dayAfter.showRain) // Only first rainy day shows text
        assertEquals("10am", dayAfter.rainSummary) // Summary still exists for click logic
    }

    @Test
    fun `prepareGraphDays compositions triple line data for today`() {
        val now = LocalDateTime.of(2030, 6, 15, 20, 0) // 8 PM
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        
        // Official API says 80/60
        val weatherByDate = mapOf(
            todayStr to createWeather(todayStr, highTemp = 80f, lowTemp = 60f)
        )
        
        // Hourly samples only reached 74/65
        val hourlyForecasts = (0..23).map { hour ->
            HourlyForecastEntity(
                dateTime = today.atTime(hour, 0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")),
                condition = "Clear",
                source = WeatherSource.NWS.id,
                temperature = (65 + (hour % 10)).toFloat(), // Max 74
                locationLat = 0.0,
                locationLon = 0.0,
                fetchedAt = 1L
            )
        }

        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = true,
            skipHistory = false,
            hourlyForecasts = hourlyForecasts
        )

        val todayData = days.first { it.date == todayStr }
        // Refined logic: Observed uses hourly peak (74) if after 4 PM, 
        // but Forecast line (Blue) uses official API (80)
        assertEquals(74f, todayData.high!!, 0.1f) 
        assertEquals(60f, todayData.low!!, 0.1f)
        assertEquals(80f, todayData.forecastHigh!!, 0.1f)
        assertEquals(60f, todayData.forecastLow!!, 0.1f)
    }

    @Test
    fun `prepareTextDays past day in NWS mode prefers NWS forecast over higher observation`() {
        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } returns null

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            yesterdayStr to createWeather(yesterdayStr, highTemp = 77f, lowTemp = 56f)
        )
        val dailyActuals = mapOf(
            yesterdayStr to com.weatherwidget.widget.ObservationResolver.DailyActual(
                date = yesterdayStr,
                highTemp = 80.9f,
                lowTemp = 55f,
                condition = "Sunny",
            )
        )

        val result = DailyViewLogic.prepareTextDays(
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
        assertEquals("77°", yesterdayData.highLabel)
        assertEquals("56°", yesterdayData.lowLabel)
    }

    @Test
    fun `prepareGraphDays past day in NWS mode prefers NWS forecast over higher observation`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            yesterdayStr to createWeather(yesterdayStr, highTemp = 77f, lowTemp = 56f)
        )
        val dailyActuals = mapOf(
            yesterdayStr to com.weatherwidget.widget.ObservationResolver.DailyActual(
                date = yesterdayStr,
                highTemp = 80.9f,
                lowTemp = 55f,
                condition = "Sunny",
            )
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val yesterdayData = days.first { it.date == yesterdayStr }
        assertEquals(77f, yesterdayData.high!!, 0.1f)
        assertEquals(56f, yesterdayData.low!!, 0.1f)
    }

    @Test
    fun `prepareGraphDays today icon uses daily condition instead of hourly condition`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            todayStr to createWeather(todayStr).copy(condition = "Rain")
        )
        val hourlyForecasts = listOf(
            HourlyForecastEntity(
                dateTime = "${todayStr}T12:00",
                locationLat = 37.7749,
                locationLon = -122.4194,
                temperature = 64f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                cloudCover = 0,
                fetchedAt = 1L,
            )
        )

        val days = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = hourlyForecasts,
        )

        val todayData = days.first { it.date == todayStr }
        assertEquals(R.drawable.ic_weather_rain, todayData.iconRes)
    }

    @Test
    fun `prepareTextDays marks generic fallback days`() {
        every { RainAnalyzer.getRainSummary(any(), any(), any(), any()) } returns null

        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val tomorrowStr = today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today.format(DateTimeFormatter.ISO_LOCAL_DATE) to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            tomorrowStr to createWeather(tomorrowStr).copy(source = WeatherSource.GENERIC_GAP.id, isClimateNormal = true)
        )

        val result = DailyViewLogic.prepareTextDays(
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
        val tomorrowStr = today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today.format(DateTimeFormatter.ISO_LOCAL_DATE) to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            tomorrowStr to createWeather(tomorrowStr).copy(source = WeatherSource.GENERIC_GAP.id, isClimateNormal = true)
        )

        val result = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        assertTrue(result.first { it.date == tomorrowStr }.isSourceGapFallback)
    }

    @Test
    fun `buildDayClickIntent returns correct extras with Robolectric`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val dateStr = "2030-06-16" // Tomorrow
        
        val intent = DailyViewHandler.buildDayClickIntent(
            context = context,
            appWidgetId = 42,
            dayIndex = 1,
            dateStr = dateStr,
            hasRainForecast = true,
            lat = 37.0,
            lon = -122.0,
            displaySource = WeatherSource.NWS,
            now = now
        )

        assertEquals("com.weatherwidget.ACTION_DAY_CLICK", intent.action)
        assertEquals(dateStr, intent.getStringExtra("date"))
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("PRECIPITATION", intent.getStringExtra("com.weatherwidget.EXTRA_TARGET_VIEW"))
        assertEquals(20, intent.getIntExtra("com.weatherwidget.EXTRA_HOURLY_OFFSET", -1))
    }

    @Test
    fun `buildDayClickIntent tomorrow without rain navigates to temperature`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val dateStr = "2030-06-16" // Tomorrow
        
        val intent = DailyViewHandler.buildDayClickIntent(
            context = context,
            appWidgetId = 42,
            dayIndex = 1,
            dateStr = dateStr,
            hasRainForecast = false,
            lat = 37.0,
            lon = -122.0,
            displaySource = WeatherSource.NWS,
            now = now
        )

        assertEquals("com.weatherwidget.ACTION_DAY_CLICK", intent.action)
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("TEMPERATURE", intent.getStringExtra("com.weatherwidget.EXTRA_TARGET_VIEW"))
        assertEquals(20, intent.getIntExtra("com.weatherwidget.EXTRA_HOURLY_OFFSET", -1))
    }

    @Test
    fun `buildDayClickIntent past day navigates to history`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val dateStr = "2030-06-14" // Yesterday
        
        val intent = DailyViewHandler.buildDayClickIntent(
            context = context,
            appWidgetId = 42,
            dayIndex = 1,
            dateStr = dateStr,
            hasRainForecast = false,
            lat = 37.0,
            lon = -122.0,
            displaySource = WeatherSource.NWS,
            now = now
        )

        assertEquals("com.weatherwidget.ACTION_DAY_CLICK", intent.action)
        assertTrue(intent.getBooleanExtra("showHistory", false))
        assertEquals(37.0, intent.getDoubleExtra(com.weatherwidget.ui.ForecastHistoryActivity.EXTRA_LAT, 0.0), 0.1)
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

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 140)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        }
        every { appWidgetManager.getAppWidgetOptions(42) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(42, capture(viewsSlot)) } just runs

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 42,
            weatherList = weatherList,
            forecastSnapshots = emptyMap(),
            hourlyForecasts = emptyList(),
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
    fun `updateWidget text labels keep tenth precision for decimal source values at Noon`() = runBlocking {
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

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            // 3 columns: (width + 15) / 70 ≈ 3 => width ≈ 195
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        }
        every { appWidgetManager.getAppWidgetOptions(43) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(43, capture(viewsSlot)) } just runs

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 43,
            weatherList = weatherList,
            forecastSnapshots = emptyMap(),
            hourlyForecasts = listOf(
                HourlyForecastEntity(todayStr + "T14:00", 0.0, 0.0, 62.9f, "Sunny", "OPEN_METEO", 0, 0, 1L),
                HourlyForecastEntity(todayStr + "T05:00", 0.0, 0.0, 51.2f, "Clear", "OPEN_METEO", 0, 0, 1L)
            ),
            currentTemps = emptyList(),
            dailyActuals = emptyMap(),
            repository = null,
            now = now
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        
        val highTexts = listOf(R.id.day1_high, R.id.day2_high, R.id.day3_high).mapNotNull { id ->
            applied.findViewById<TextView>(id)?.text?.toString()
        }
        
        assertTrue("Noon highTexts $highTexts should contain 62.9°", highTexts.contains("62.9°"))
    }

    @Test
    fun `updateWidget text labels keep tenth precision for decimal source values in Evening`() = runBlocking {
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

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        }
        every { appWidgetManager.getAppWidgetOptions(44) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(44, capture(viewsSlot)) } just runs

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 44,
            weatherList = weatherList,
            forecastSnapshots = emptyMap(),
            hourlyForecasts = listOf(
                HourlyForecastEntity(todayStr + "T14:00", 0.0, 0.0, 62.9f, "Sunny", "OPEN_METEO", 0, 0, 1L),
                HourlyForecastEntity(todayStr + "T05:00", 0.0, 0.0, 51.2f, "Clear", "OPEN_METEO", 0, 0, 1L)
            ),
            currentTemps = emptyList(),
            dailyActuals = emptyMap(),
            repository = null,
            now = now
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        
        val highTexts = listOf(R.id.day1_high, R.id.day2_high, R.id.day3_high, R.id.day4_high).mapNotNull { id ->
            applied.findViewById<TextView>(id)?.text?.toString()
        }
        
        assertTrue("Evening highTexts $highTexts should contain 62.9° (for Today)", highTexts.contains("62.9°"))
    }

    @Test
    fun `updateWidget daily header icon uses daily condition instead of hourly condition`() = runBlocking {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherList = listOf(
            createWeather(todayStr, highTemp = 70f, lowTemp = 55f).copy(condition = "Rain")
        )
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(45)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 140)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        }
        every { appWidgetManager.getAppWidgetOptions(45) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(45, capture(viewsSlot)) } just runs

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 45,
            weatherList = weatherList,
            forecastSnapshots = emptyMap(),
            hourlyForecasts = listOf(
                HourlyForecastEntity("${todayStr}T12:00", 37.7749, -122.4194, 64f, "Clear", WeatherSource.NWS.id, 0, 0, 1L)
            ),
            currentTemps = emptyList(),
            dailyActuals = emptyMap(),
            repository = null,
            now = now
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        val imageView = applied.findViewById<ImageView>(R.id.weather_icon)

        assertEquals(R.drawable.ic_weather_rain, shadowOf(imageView.drawable).createdFromResId)
    }

    @Test
    fun `updateWidget today text icon uses daily condition instead of hourly condition`() = runBlocking {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val tomorrowStr = now.toLocalDate().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherList = listOf(
            createWeather(todayStr, highTemp = 70f, lowTemp = 55f).copy(condition = "Rain"),
            createWeather(tomorrowStr, highTemp = 71f, lowTemp = 56f).copy(condition = "Clear"),
        )
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(46)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        }
        every { appWidgetManager.getAppWidgetOptions(46) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(46, capture(viewsSlot)) } just runs

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = 46,
            weatherList = weatherList,
            forecastSnapshots = emptyMap(),
            hourlyForecasts = listOf(
                HourlyForecastEntity("${todayStr}T12:00", 37.7749, -122.4194, 64f, "Clear", WeatherSource.NWS.id, 0, 0, 1L)
            ),
            currentTemps = emptyList(),
            dailyActuals = emptyMap(),
            repository = null,
            now = now
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        val todayImageView = applied.findViewById<ImageView>(R.id.day2_icon)

        assertEquals(R.drawable.ic_weather_rain, shadowOf(todayImageView.drawable).createdFromResId)
    }

    private fun createWeatherMap(today: LocalDate): Map<String, ForecastEntity> {
        return (-1..5).associate { offset ->
            val date = today.plusDays(offset.toLong())
            val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            dateStr to createWeather(dateStr)
        }
    }

    private fun createWeather(
        date: String,
        precipProbability: Int? = 0,
        highTemp: Float? = 70f,
        lowTemp: Float? = 55f,
    ): ForecastEntity {
        return ForecastEntity(
            targetDate = date,
            forecastDate = date,
            locationLat = 37.7749,
            locationLon = -122.4194,
            locationName = "Test",
            highTemp = highTemp,
            lowTemp = lowTemp,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            precipProbability = precipProbability,
            fetchedAt = 1L,
        )
    }
}

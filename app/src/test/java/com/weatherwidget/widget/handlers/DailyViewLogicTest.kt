package com.weatherwidget.widget.handlers

import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.WidgetConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyViewLogicTest {

    private fun extreme(
        date: LocalDate,
        high: Float,
        low: Float,
        condition: String = "Clear",
        precipAmountMm: Float? = null,
        precipDayMm: Float? = null,
        precipNightMm: Float? = null,
        source: String = WeatherSource.NWS.id
    ) = DailyHistory(
        date = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        source = source,
        locationLat = 0.0,
        locationLon = 0.0,
        highTemp = high,
        lowTemp = low,
        condition = condition,
        updatedAt = System.currentTimeMillis(),
        precipAmountMm = precipAmountMm,
        precipDayMm = precipDayMm,
        precipNightMm = precipNightMm
    )

    @Test
    fun `future day with GENERIC_GAP data is visible`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(7)
        val futureStr = future.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            future to createWeather(futureStr, source = WeatherSource.GENERIC_GAP.id, isClimateNormal = true)
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        val gapDay = result.find { it.date == future }
        assertTrue("GENERIC_GAP future day should appear in output", gapDay != null)
        assertTrue("GENERIC_GAP day should have isSourceGapFallback=true", gapDay!!.isSourceGapFallback)
    }

    @Test
    fun `future day with null highTemp is now visible as empty column`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(7)
        val futureStr = future.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            future to createWeather(futureStr, highTemp = null)
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        val phantomDay = result.find { it.date == future }
        assertTrue("Future day with null high should now be present as empty column", phantomDay != null)
        assertEquals("Should have null high", null, phantomDay!!.solidLineHigh)
    }

    @Test
    fun `NWS days 0-6 plus gap day 7 plus empty day 8 all render`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = mutableMapOf<LocalDate, ForecastEntity>()

        // Days 0-6: complete NWS forecasts
        for (offset in 0L..6L) {
            val date = today.plusDays(offset)
            weatherByDate[date] = createWeather(
                date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            )
        }

        // Day 7: GENERIC_GAP fallback
        val gapDate = today.plusDays(7)
        weatherByDate[gapDate] = createWeather(
            gapDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            source = WeatherSource.GENERIC_GAP.id,
            isClimateNormal = true
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        assertEquals("Should render 9 days (NWS 0-6 + gap 7 + empty 8)", 9, result.size)
        val gapDay = result.find { it.date == gapDate }
        assertTrue("Gap day 7 should be present", gapDay != null)
        assertTrue("Gap day should be marked as gap fallback", gapDay!!.isSourceGapFallback)
        val nwsDays = result.filter { !it.isSourceGapFallback && it.solidLineHigh != null }
        assertEquals("Should have 7 NWS days", 7, nwsDays.size)
    }

    @Test
    fun `future day with missing forecast uses climate normals`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(7)
        
        // No weather data for future
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        )
        
        // Normals available for future
        val climateNormals = mapOf(
            java.time.MonthDay.from(future) to Pair(75f, 60f)
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            climateNormals = climateNormals
        )

        val normalDay = result.find { it.date == future }
        assertTrue("Future day with normals should be present", normalDay != null)
        assertEquals("Should use climate high", 75f, normalDay!!.solidLineHigh)
        assertEquals("Should use climate low", 60f, normalDay.solidLineLow)
        assertTrue("Should be marked as climate overlay", normalDay.isClimateNormal)
    }

    @Test
    fun `past day with no forecast snapshot leaves forecastHigh and forecastLow null`() {
        // Regression guard: when forecastSnapshots is missing a past date, prepareGraphDays
        // must NOT synthesize a forecast bar from climate normals — that's what caused
        // yellow forecast bars on past Wed/Thu to render down at 45°F (May Bay-Area normals).
        val now = LocalDateTime.of(2026, 5, 9, 12, 0)
        val today = now.toLocalDate()
        val pastWed = today.minusDays(3)
        val pastWedStr = pastWed.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            pastWed to createWeather(pastWedStr, highTemp = 72.9f, lowTemp = 56.5f),
        )
        val dailyActuals = mapOf(
            pastWed to extreme(pastWed, 72.9f, 56.5f),
        )
        val climateNormals = mapOf(
            java.time.MonthDay.from(pastWed) to Pair(58f, 48f),  // bait: would be the bad fallback
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
            climateNormals = climateNormals,
        )

        val past = result.find { it.date == pastWed }
        assertNotNull("Past day should be present in result", past)
        assertNull("Past day must NOT synthesize forecastHigh from climate normals", past!!.dashedLineHigh)
        assertNull("Past day must NOT synthesize forecastLow from climate normals", past.dashedLineLow)
        assertFalse("Past day should not be marked climate normal", past.isClimateNormal)
    }

    @Test
    fun `past day with forecast snapshot uses snapshot values not climate normal`() {
        val now = LocalDateTime.of(2026, 5, 9, 12, 0)
        val today = now.toLocalDate()
        val pastWed = today.minusDays(3)
        val pastWedStr = pastWed.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            pastWed to createWeather(pastWedStr, highTemp = 72.9f, lowTemp = 56.5f),
        )
        val dailyActuals = mapOf(
            pastWed to extreme(pastWed, 72.9f, 56.5f),
        )
        val snapshot = createWeather(date = pastWedStr, highTemp = 72f, lowTemp = 53f)
        val forecastSnapshots = mapOf(pastWed to listOf(snapshot))
        val climateNormals = mapOf(
            java.time.MonthDay.from(pastWed) to Pair(58f, 48f),  // bait: must NOT be used
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = forecastSnapshots,
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
            climateNormals = climateNormals,
        )

        val past = result.find { it.date == pastWed }
        assertNotNull(past)
        assertEquals("Past forecastHigh must come from snapshot, not climate normal", 72f, past!!.dashedLineHigh)
        assertEquals("Past forecastLow must come from snapshot, not climate normal", 53f, past.dashedLineLow)
    }

    @Test
    fun `past day prefers frozen overlay from daily_history over snapshot table`() {
        val now = LocalDateTime.of(2026, 5, 9, 12, 0)
        val today = now.toLocalDate()
        val pastWed = today.minusDays(3)
        val pastWedStr = pastWed.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
        )
        val dailyActuals = mapOf(
            pastWed to extreme(pastWed, 72.9f, 56.5f).copy(
                forecastHighTemp = 74f,
                forecastLowTemp = 52f,
            ),
        )
        // Bait: the snapshot table must lose to the frozen daily_history values.
        val snapshot = createWeather(date = pastWedStr, highTemp = 72f, lowTemp = 53f)
        val forecastSnapshots = mapOf(pastWed to listOf(snapshot))

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = forecastSnapshots,
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val past = result.find { it.date == pastWed }!!
        assertEquals("Past forecastHigh must come from the frozen daily_history value", 74f, past.dashedLineHigh)
        assertEquals("Past forecastLow must come from the frozen daily_history value", 52f, past.dashedLineLow)
    }

    @Test
    fun `past day frozen noon cloud drives cloud ratio without any hourly data`() {
        val now = LocalDateTime.of(2026, 5, 9, 12, 0)
        val today = now.toLocalDate()
        val pastWed = today.minusDays(3)
        val dailyActuals = mapOf(
            pastWed to extreme(pastWed, 72.9f, 56.5f).copy(noonCloudPercent = 60),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = mapOf(today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE))),
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(), // hourly aged out — the frozen value must carry the day
            dailyActuals = dailyActuals,
        )

        val past = result.find { it.date == pastWed }!!
        assertEquals(0.6f, past.cloudCoverRatioOverride)
    }

    @Test
    fun `past day skips NWS latest-batch with null lowTemp and uses older usable NWS batch`() {
        val now = LocalDateTime.of(2026, 5, 9, 12, 0)
        val today = now.toLocalDate()
        val pastWed = today.minusDays(3)
        val pastWedStr = pastWed.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            pastWed to createWeather(pastWedStr, highTemp = 72.9f, lowTemp = 56.5f),
        )
        val dailyActuals = mapOf(
            pastWed to extreme(pastWed, 72.9f, 56.5f),
        )
        val nwsLatestNullLow = createWeather(date = pastWedStr, highTemp = 72f, lowTemp = null)
            .copy(fetchedAt = 2000L)
        val nwsOlderUsable = createWeather(date = pastWedStr, highTemp = 72f, lowTemp = 53f)
            .copy(fetchedAt = 1000L)
        val forecastSnapshots = mapOf(pastWed to listOf(nwsLatestNullLow, nwsOlderUsable))

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = forecastSnapshots,
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val past = result.find { it.date == pastWed }!!
        assertEquals("Must use older NWS batch with non-null low, not the latest null-low row",
            72f, past.dashedLineHigh)
        assertEquals(53f, past.dashedLineLow)
    }

    @Test
    fun `past day ignores GENERIC_GAP source rows even when displaySource has only null-low data`() {
        val now = LocalDateTime.of(2026, 5, 9, 12, 0)
        val today = now.toLocalDate()
        val pastWed = today.minusDays(3)
        val pastWedStr = pastWed.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            pastWed to createWeather(pastWedStr, highTemp = 72.9f, lowTemp = 56.5f),
        )
        val dailyActuals = mapOf(
            pastWed to extreme(pastWed, 72.9f, 56.5f),
        )
        val nwsNullLow = createWeather(date = pastWedStr, highTemp = 72f, lowTemp = null)
        val genericClimateNormal = createWeather(
            date = pastWedStr,
            source = WeatherSource.GENERIC_GAP.id,
            highTemp = 58f,
            lowTemp = 48f,
            isClimateNormal = true,
        )
        val forecastSnapshots = mapOf(pastWed to listOf(nwsNullLow, genericClimateNormal))

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = forecastSnapshots,
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val past = result.find { it.date == pastWed }!!
        assertNull("Must NOT use GENERIC_GAP climate-normal row as a forecast bait", past.dashedLineHigh)
        assertNull("Must NOT use GENERIC_GAP climate-normal row as a forecast bait", past.dashedLineLow)
    }

    @Test
    fun `future day with no data still renders as empty column`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(7)
        
        // No weather, no normals
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        // With numColumns = 9, we expect 9 slots if NavigationUtils.getDayOffsets returns them
        // NavigationUtils.getDayOffsets(9, false) starts from -1, so it should go -1 to 7
        val emptyDay = result.find { it.date == future }
        assertTrue("Future day with no data should still be present in result list", emptyDay != null)
        assertEquals("Empty column should have null high", null, emptyDay!!.solidLineHigh)
        assertEquals("Empty column should have null low", null, emptyDay.solidLineLow)
        assertEquals("Label should still be set", "Sat", emptyDay.label)
    }

    @Test
    fun `prepareTextDays with missing forecast uses climate normals`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(4)
        
        // No weather data for future
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        )
        
        // Normals available for future
        val climateNormals = mapOf(
            java.time.MonthDay.from(future) to Pair(80f, 65f)
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
            climateNormals = climateNormals
        )

        // Day index 6 is offset 5, so day index 5 is offset 4 (future)
        val normalDay = result.find { it.date == future }
        assertTrue("Future day with normals should be present in text mode", normalDay != null)
        assertEquals("Should use climate high label", "80°", normalDay!!.highLabel)
        assertEquals("Should use climate low label", "65°", normalDay.lowLabel)
    }

    @Test
    fun `prepareTextDays with no data still shows column`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(5) // offset 5
        
        // No weather, no normals
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE))
        )

        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 7,
            displaySource = WeatherSource.NWS
        )

        val emptyDay = result.find { it.date == future }
        assertTrue("Future day with no data should still be present in text mode result", emptyDay != null)
        assertTrue("Day should be visible even without data to maintain grid", emptyDay!!.isVisible)
        assertEquals("Labels should be null", null, emptyDay.highLabel)
        assertEquals("Labels should be null", null, emptyDay.lowLabel)
    }

    @Test
    fun `prepareTextDays labels today as Today`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            tomorrow to createWeather(tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE)),
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
        )

        assertEquals("Today", result.first { it.date == today }.label)
        assertEquals("Sun", result.first { it.date == tomorrow }.label)
    }

    @Test
    fun `prepareTextDays supports eight visible columns`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = (-1..6).associate { offset ->
            val date = today.plusDays(offset.toLong())
            date to createWeather(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }

        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 8,
            displaySource = WeatherSource.NWS,
        )

        assertEquals(8, result.size)
        assertEquals(8, result.count { it.isVisible })
        assertEquals(today.plusDays(6), result.last().date)
    }

    @Test
    fun `prepareTextDays keeps terminal NWS low only future day without climate fallback`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(5)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                highTemp = null,
                lowTemp = 41f,
                source = WeatherSource.NWS.id,
            ),
        )
        val climateNormals = mapOf(
            java.time.MonthDay.from(future) to Pair(80f, 65f),
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
            climateNormals = climateNormals,
        )

        val partialDay = result.find { it.date == future }
        assertNotNull("Terminal NWS low-only future day should be present", partialDay)
        assertTrue("Terminal NWS low-only future day should count as data", partialDay!!.hasData)
        assertNull("Missing high should remain null", partialDay.highLabel)
        assertEquals("Low label should come from NWS data", "41°", partialDay.lowLabel)
    }

    @Test
    fun `prepareTextDays still uses climate fallback for non terminal future low only day`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val earlierFuture = today.plusDays(4)
        val lastFuture = today.plusDays(5)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            earlierFuture to createWeather(
                date = earlierFuture.format(DateTimeFormatter.ISO_LOCAL_DATE),
                highTemp = null,
                lowTemp = 44f,
                source = WeatherSource.NWS.id,
            ),
            lastFuture to createWeather(lastFuture.format(DateTimeFormatter.ISO_LOCAL_DATE)),
        )
        val climateNormals = mapOf(
            java.time.MonthDay.from(earlierFuture) to Pair(78f, 60f),
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
            climateNormals = climateNormals,
        )

        val fallbackDay = result.find { it.date == earlierFuture }
        assertNotNull("Earlier future day should be present", fallbackDay)
        assertEquals("Non-terminal low-only day should still use climate high", "78°", fallbackDay!!.highLabel)
        assertEquals("Non-terminal low-only day should still use climate low", "60°", fallbackDay.lowLabel)
    }

    @Test
    fun `prepareGraphDays keeps terminal NWS low only future day without climate fallback`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(7)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                highTemp = null,
                lowTemp = 39f,
                source = WeatherSource.NWS.id,
            ),
        )
        val climateNormals = mapOf(
            java.time.MonthDay.from(future) to Pair(77f, 58f),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            climateNormals = climateNormals,
        )

        val partialDay = result.find { it.date == future }
        assertNotNull("Terminal NWS low-only future graph day should be present", partialDay)
        assertNull("Missing high should remain null", partialDay!!.solidLineHigh)
        assertEquals("Low should come from NWS data", 39f, partialDay.solidLineLow)
        assertFalse("Terminal low-only day should not become climate overlay", partialDay.isClimateNormal)
    }

    @Test
    fun `prepareGraphDays rainy future day shows percent label`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 65,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals("65%", futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `prepareGraphDays future NWS rain label uses hourly window max with period fallback`() {
        // NWS no longer gets its native 12h period chance directly (periods run 6am/6pm and clipped
        // 6-8am rain out of "tonight"): the hourly 8am-8pm max wins for day, and night falls back to
        // the period field only because there are no night hourly rows here.
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(2)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 30,
                daytimePrecipProbability = 30,
                nighttimePrecipProbability = 80,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = listOf(
                createHourlyForecast(future.atTime(14, 0), cloudCover = 50).copy(precipProbability = 95),
            ),
        )

        val futureDay = result.first { it.date == future }
        assertEquals("95%", futureDay.rainData.dailyRainLabelText)
        assertEquals("80%", futureDay.rainData.nightRainLabelText)
    }

    @Test
    fun `prepareGraphDays past day night rain label replays stored snapshot over raw NWS period field`() {
        // Regression (2026-07-04): the past day's daily_history row has forecastNightPrecipChance=14
        // (the hourly window-max value archived while the day was still live). The weather row's raw
        // NWS period field (nighttimePrecipProbability=9) must NOT win once the day is history.
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val weatherByDate = mapOf(
            yesterday to createWeather(
                date = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 9,
                daytimePrecipProbability = 0,
                nighttimePrecipProbability = 9,
            ),
        )
        val dailyActuals = mapOf(
            yesterday to extreme(yesterday, 70f, 55f).copy(
                forecastDayPrecipChance = 0,
                forecastNightPrecipChance = 14,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val pastDay = result.first { it.date == yesterday }
        assertEquals("14%", pastDay.rainData.nightRainLabelText)
    }

    @Test
    fun `prepareGraphDays tonight rain label requires greater than 0 percent`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()

        fun renderTonight(probability: Int): DailyForecastGraphRenderer.DayData {
            val result = DailyViewLogic.prepareGraphDays(
                todayLabel = "Today",
                now = now,
                centerDate = today,
                today = today,
                weatherByDate = mapOf(
                    today to createWeather(
                        date = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        condition = "Clear",
                        precipProbability = 0,
                        nighttimePrecipProbability = probability,
                    ),
                ),
                forecastSnapshots = emptyMap(),
                numColumns = 3,
                displaySource = WeatherSource.NWS,
                skipYesterday = false,
                skipHistory = true,
                hourlyForecasts = emptyList(),
            )
            return result.first { it.date == today }
        }

        assertNull(renderTonight(-1).rainData.nightRainLabelText)
        assertEquals("1%", renderTonight(1).rainData.nightRainLabelText)
    }

    @Test
    fun `prepareGraphDays future night rain thresholds follow getMinimumPrecipProbabilityNight`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val day2 = today.plusDays(2)
        val weatherByDate = mapOf(
            tomorrow to createWeather(
                date = tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Clear",
                precipProbability = 0,
                nighttimePrecipProbability = 4,
            ),
            day2 to createWeather(
                date = day2.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Clear",
                precipProbability = 0,
                nighttimePrecipProbability = 10,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        // day 1: threshold=5. 4 < 5 -> null
        assertNull(result.first { it.date == tomorrow }.rainData.nightRainLabelText)
        // day 2: threshold=10. 10 >= 10 -> "10%"
        assertEquals("10%", result.first { it.date == day2 }.rainData.nightRainLabelText)
    }

    @Test
    fun `prepareGraphDays rainy future day with 100 percent and amount shows amount`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 100,
                precipAmountMm = 0.0508f,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals(".002in", futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `prepareGraphDays today with 100 percent rain shows amount`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = mapOf(
            today to createWeather(
                date = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 100,
                precipAmountMm = 10f,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val todayDay = result.first { it.date == today }
        assertNotNull(todayDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `prepareGraphDays today with low rain chance omits rain label`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = mapOf(
            today to createWeather(
                date = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 80,
                precipAmountMm = 10f,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val todayDay = result.first { it.date == today }
        assertNull(todayDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `prepareGraphDays today rain chance label shown when today label is allowed`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = mapOf(
            today to createWeather(
                date = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 80,
                precipAmountMm = 10f,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
            todayNext8HourPrecipProbability = 80,
            allowTodayRainChanceLabel = true,
        )

        val todayDay = result.first { it.date == today }
        assertEquals("80%", todayDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `prepareGraphDays today rain amount still wins when today label is allowed`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = mapOf(
            today to createWeather(
                date = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 100,
                precipAmountMm = 10f,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
            todayNext8HourPrecipProbability = 100,
            allowTodayRainChanceLabel = true,
        )

        val todayDay = result.first { it.date == today }
        assertEquals(".39in", todayDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `rainy future day with 99 percent and amount shows amount`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 99,
                precipAmountMm = 5.0f,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals(".2in", futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `rainy future day with 98 percent and amount shows percentage`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 98,
                precipAmountMm = 5.0f,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals("98%", futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `rainy future day with 99 percent and null amount shows percentage`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 99,
                precipAmountMm = null,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals("99%", futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `rainy future day with 0 percent returns null label`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 0,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals(null, futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `rainy future day with 1 percent returns null label`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 1,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals(null, futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `rainy future day just above threshold shows percent`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 21,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals("21%", futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `future day rain label does not depend on rain icon`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Mostly Clear",
                nativeDailyIconToken = "Mostly Clear",
                precipProbability = 15,
                daytimePrecipProbability = 15,
                nighttimePrecipProbability = 0,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals(R.drawable.ic_weather_mostly_clear, futureDay.iconRes)
        assertEquals("15%", futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `rainy future day below tomorrow threshold returns null label`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 4,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertNull(futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `rainy future day with 99 percent and small amount shows amount`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 99,
                precipAmountMm = 0.0508f,
            ),
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
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals(".002in", futureDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `prepareTextDays today prefers native daily icon token over condition text`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = mapOf(
            today to createWeather(
                date = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Chance Light Rain",
                nativeDailyIconToken = "partly-cloudy-day",
                source = WeatherSource.VISUAL_CROSSING.id,
            ),
        )

        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            hourlyForecasts = emptyList(),
            numColumns = 1,
            displaySource = WeatherSource.VISUAL_CROSSING,
        )

        assertEquals(R.drawable.ic_weather_partly_cloudy, result.first { it.date == today }.iconRes)
    }

    @Test
    fun `prepareGraphDays today prefers native daily icon token over condition text`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val weatherByDate = mapOf(
            today to createWeather(
                date = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                nativeDailyIconToken = "01d",
                source = WeatherSource.OPEN_WEATHER_MAP.id,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.OPEN_WEATHER_MAP,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        assertEquals(R.drawable.ic_weather_clear, result.first { it.date == today }.iconRes)
    }

    @Test
    fun `prepareGraphDays uses noon cloud cover ratio for mixed day bar gradient`() {
        val now = LocalDateTime.of(2030, 6, 15, 9, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Partly Sunny",
            ),
        )
        val hourlyForecasts = listOf(
            createHourlyForecast(future.atTime(11, 0), cloudCover = 20),
            createHourlyForecast(future.atTime(12, 0), cloudCover = 70),
            createHourlyForecast(future.atTime(13, 0), cloudCover = 40),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = hourlyForecasts,
        )

        val futureDay = result.first { it.date == future }
        assertEquals(0.7f, futureDay.cloudCoverRatioOverride)
    }

    @Test
    fun `prepareGraphDays assumes zero cloud cover when noon is absent`() {
        val now = LocalDateTime.of(2030, 6, 15, 9, 0)
        val today = now.toLocalDate()
        val future = today.plusDays(1)
        val weatherByDate = mapOf(
            future to createWeather(
                date = future.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Partly Sunny",
            ),
        )
        val hourlyForecasts = listOf(
            createHourlyForecast(future.atTime(11, 0), cloudCover = 65, source = WeatherSource.NWS.id),
            createHourlyForecast(future.atTime(13, 0), cloudCover = 25, source = WeatherSource.NWS.id),
            createHourlyForecast(future.atTime(12, 0), cloudCover = 90, source = WeatherSource.OPEN_METEO.id),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = hourlyForecasts,
        )

        val futureDay = result.first { it.date == future }
        assertEquals(0f, futureDay.cloudCoverRatioOverride)
    }

    @Test
    fun `prepareGraphDays today uses complete snapshot when latest batch is missing high or low`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Latest weather is incomplete (e.g. NWS evening drop)
        val weatherByDate = mapOf(
            today to createWeather(todayStr, highTemp = null, lowTemp = 55f, source = WeatherSource.NWS.id)
        )

        // Older snapshot is complete
        val completeSnapshot = createWeather(todayStr, highTemp = 80f, lowTemp = 55f, source = WeatherSource.NWS.id)
            .copy(fetchedAt = 100L)
        val incompleteSnapshot = createWeather(todayStr, highTemp = null, lowTemp = 55f, source = WeatherSource.NWS.id)
            .copy(fetchedAt = 200L)

        val forecastSnapshots = mapOf(
            today to listOf(completeSnapshot, incompleteSnapshot)
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = forecastSnapshots,
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = emptyList()
        )

        val todayDay = result.first { it.date == today }
        assertEquals("Today should use complete snapshot high", 80f, todayDay.solidLineHigh)
    }

    @Test
    fun `prepareGraphDays today ignores complete snapshot from a different site`() {
        val now = LocalDateTime.of(2030, 6, 15, 20, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // The widget's own site: freshest batch is high-only (NWS evening drop), so the
        // incomplete-today repair fires.
        val widgetSite = createWeather(todayStr, highTemp = 81f, lowTemp = null, source = WeatherSource.NWS.id)
        val weatherByDate = mapOf(today to widgetSite)

        // The snapshot pool is uncollapsed, so it also holds a batch fetched at a town the device
        // passed through earlier today (~2.7 mi away) — newer, complete, and from the same source.
        val otherTownSnapshot = createWeather(todayStr, highTemp = 84f, lowTemp = 57f, source = WeatherSource.NWS.id)
            .copy(locationLat = 37.735, locationLon = -122.404, fetchedAt = 200L)
        val sameSiteSnapshot = createWeather(todayStr, highTemp = 81f, lowTemp = 57f, source = WeatherSource.NWS.id)
            .copy(fetchedAt = 100L)

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = mapOf(today to listOf(otherTownSnapshot, sameSiteSnapshot)),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = emptyList()
        )

        val todayDay = result.first { it.date == today }
        assertEquals(
            "Today's forecast high must come from the widget's own site, not the newer off-site batch",
            81f,
            todayDay.dashedLineHigh,
        )
        assertEquals("Repair should still supply the missing low from the same site", 57f, todayDay.dashedLineLow)
    }

    @Test
    fun `prepareTextDays today ignores complete snapshot from a different site`() {
        val now = LocalDateTime.of(2030, 6, 15, 20, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val weatherByDate = mapOf(
            today to createWeather(todayStr, highTemp = 81f, lowTemp = null, source = WeatherSource.NWS.id)
        )
        val otherTownSnapshot = createWeather(todayStr, highTemp = 84f, lowTemp = 57f, source = WeatherSource.NWS.id)
            .copy(locationLat = 37.735, locationLon = -122.404, fetchedAt = 200L)
        val sameSiteSnapshot = createWeather(todayStr, highTemp = 81f, lowTemp = 57f, source = WeatherSource.NWS.id)
            .copy(fetchedAt = 100L)

        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = mapOf(today to listOf(otherTownSnapshot, sameSiteSnapshot)),
            hourlyForecasts = emptyList(),
            numColumns = 3,
            displaySource = WeatherSource.NWS
        )

        val todayDay = result.first { it.date == today }
        assertEquals("Today text high must not come from the off-site batch", "81°", todayDay.highLabel)
    }

    @Test
    fun `prepareTextDays today uses complete snapshot when latest batch is missing high or low`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Latest weather is incomplete
        val weatherByDate = mapOf(
            today to createWeather(todayStr, highTemp = null, lowTemp = 55f, source = WeatherSource.NWS.id)
        )

        // Older snapshot is complete
        val completeSnapshot = createWeather(todayStr, highTemp = 80f, lowTemp = 55f, source = WeatherSource.NWS.id)
            .copy(fetchedAt = 100L)

        val forecastSnapshots = mapOf(
            today to listOf(completeSnapshot)
        )

        val result = DailyViewLogic.prepareTextDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = forecastSnapshots,
            hourlyForecasts = emptyList(),
            numColumns = 3,
            displaySource = WeatherSource.NWS
        )

        val todayDay = result.first { it.date == today }
        assertEquals("Today text label should use complete snapshot high", "80°", todayDay.highLabel)
    }

    @Test
    fun `prepareGraphDays today resolves snapshotIconRes from old forecast batch`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val yesterdaySameTime = now.minusHours(24)

        // Snapshot fetched 25 hours ago, predicting rain for today
        val snapshotBatch = createWeather(
            date = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
            condition = "Rain",
            precipProbability = 80
        ).copy(fetchedAt = yesterdaySameTime.minusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())

        // Current batch predicts clear
        val currentWeather = createWeather(
            date = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
            condition = "Clear",
            precipProbability = 0
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = mapOf(today to currentWeather),
            forecastSnapshots = mapOf(today to listOf(snapshotBatch)),
            numColumns = 1,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        val todayDay = result.find { it.isToday }
        assertNotNull("Today column should be present", todayDay)
        assertNotNull("Snapshot icon should be resolved", todayDay!!.snapshotIconRes)
        assertTrue("Snapshot icon should be rain", WeatherIconMapper.isPrecipitation(todayDay.snapshotIconRes!!))
        assertFalse("Current icon should be sunny", todayDay.isRainy)
    }

    @Test
    fun `prepareGraphDays for today can have a nighttime rain label if hourly data clearing threshold is present`() {
        val now = LocalDateTime.of(2026, 5, 25, 10, 0)
        val today = now.toLocalDate()
        val zone = java.time.ZoneId.systemDefault()
        
        // 50% rain tonight (10 PM)
        val tNight = today.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        
        val hourly = listOf(
            HourlyForecastEntity(
                dateTime = tNight,
                locationLat = 0.0,
                locationLon = 0.0,
                temperature = 60f,
                condition = "Rain",
                source = WeatherSource.SILURIAN.id,
                precipProbability = 50,
                fetchedAt = System.currentTimeMillis()
            )
        )

        val weatherByDate = mapOf(
            today to createWeather(
                today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                source = WeatherSource.SILURIAN.id,
                precipProbability = 50
            )
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = WeatherSource.SILURIAN,
            skipYesterday = true,
            skipHistory = true,
            hourlyForecasts = hourly
        )

        val todayData = result.find { it.date == today }
        assertNotNull("Today data should be present", todayData)
        assertEquals("Nighttime rain label should be 50%", "50%", todayData!!.rainData.nightRainLabelText)
    }

    @Test
    fun `prepareGraphDays for today uses stored nighttimePrecipProbability if hourly data is missing`() {
        val now = LocalDateTime.of(2026, 5, 25, 10, 0)
        val today = now.toLocalDate()

        val weatherByDate = mapOf(
            today to createWeather(
                today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                source = WeatherSource.SILURIAN.id,
                nighttimePrecipProbability = 75
            )
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = WeatherSource.SILURIAN,
            skipYesterday = true,
            skipHistory = true,
            hourlyForecasts = emptyList() // No hourly data
        )

        val todayData = result.find { it.date == today }
        assertNotNull("Today data should be present", todayData)
        assertEquals("Nighttime rain label should be 75% from stored value", "75%", todayData!!.rainData.nightRainLabelText)
    }

    @Test
    fun `prepareGraphDays for future day uses daytimePrecipProbability for daytime label instead of general max`() {
        val now = LocalDateTime.of(2026, 5, 25, 10, 0)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val zone = java.time.ZoneId.systemDefault()

        // 10% rain tomorrow day (10 AM)
        val tDay = tomorrow.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        // 50% rain tomorrow night (10 PM)
        val tNight = tomorrow.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()

        val hourly = listOf(
            HourlyForecastEntity(
                dateTime = tDay,
                locationLat = 0.0,
                locationLon = 0.0,
                temperature = 70f,
                condition = "Cloudy",
                source = WeatherSource.SILURIAN.id,
                precipProbability = 10,
                fetchedAt = System.currentTimeMillis()
            ),
            HourlyForecastEntity(
                dateTime = tNight,
                locationLat = 0.0,
                locationLon = 0.0,
                temperature = 60f,
                condition = "Rain",
                source = WeatherSource.SILURIAN.id,
                precipProbability = 50,
                fetchedAt = System.currentTimeMillis()
            )
        )

        val weatherByDate = mapOf(
            tomorrow to createWeather(
                tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE),
                source = WeatherSource.SILURIAN.id,
                precipProbability = 50 // Daily max is 50
            )
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = WeatherSource.SILURIAN,
            skipYesterday = true,
            skipHistory = true,
            hourlyForecasts = hourly
        )

        val tomorrowData = result.find { it.date == tomorrow }
        assertNotNull("Tomorrow data should be present", tomorrowData)
        // Correct fix: should use daytime max (10%) for daytime label
        assertEquals("Daytime rain label should be 10%", "10%", tomorrowData!!.rainData.dailyRainLabelText)
        assertEquals("Nighttime rain label should be 50%", "50%", tomorrowData.rainData.nightRainLabelText)
    }

    private fun createWeather(
        date: String,
        source: String = WeatherSource.NWS.id,
        highTemp: Float? = 70f,
        lowTemp: Float? = 55f,
        isClimateNormal: Boolean = false,
        condition: String = "Clear",
        nativeDailyIconToken: String? = null,
        precipProbability: Int? = 0,
        daytimePrecipProbability: Int? = null,
        nighttimePrecipProbability: Int? = null,
        precipAmountMm: Float? = null,
    ): ForecastEntity {
        return ForecastEntity(
            targetDate = dateEpoch(date),
            dateOfPrediction = dateEpoch(date),
            locationLat = 37.7749,
            locationLon = -122.4194,
            highTemp = highTemp,
            lowTemp = lowTemp,
            condition = condition,
            nativeDailyIconToken = nativeDailyIconToken,
            source = source,
            isClimateNormal = isClimateNormal,
            precipProbability = precipProbability,
            daytimePrecipProbability = daytimePrecipProbability,
            nighttimePrecipProbability = nighttimePrecipProbability,
            precipAmountMm = precipAmountMm,
            fetchedAt = 1L,
        )
    }

    private fun createHourlyForecast(
        dateTime: LocalDateTime,
        cloudCover: Int?,
        source: String = WeatherSource.NWS.id,
    ): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            locationLat = 37.7749,
            locationLon = -122.4194,
            temperature = 60f,
            condition = "Partly Sunny",
            source = source,
            cloudCover = cloudCover,
            fetchedAt = 1L,
        )
    }

    @Test
    fun `rain label suppressed for distant day with 20 percent probability`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val distant = today.plusDays(5)
        val weatherByDate = mapOf(
            distant to createWeather(
                date = distant.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 20,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val distantDay = result.first { it.date == distant }
        assertNull(distantDay.rainData.dailyRainLabelText)
    }

    @Test
     fun `rain label suppressed for near term day below threshold`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val nearTerm = today.plusDays(2)
        val weatherByDate = mapOf(
            nearTerm to createWeather(
                date = nearTerm.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 8,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val nearTermDay = result.first { it.date == nearTerm }
        assertNull(nearTermDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `rain label shown for distant day with 50 percent probability`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val distant = today.plusDays(5)
        val weatherByDate = mapOf(
            distant to createWeather(
                date = distant.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 50,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val distantDay = result.first { it.date == distant }
        assertEquals("50%", distantDay.rainData.dailyRainLabelText)
    }

    @Test
    fun `rain label suppressed for day exactly 4 away below threshold`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val day4 = today.plusDays(4)
        val weatherByDate = mapOf(
            day4 to createWeather(
                date = day4.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 16,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val day4Data = result.first { it.date == day4 }
        assertNull(day4Data.rainData.dailyRainLabelText)
    }

    @Test
    fun `rain label suppressed for day 3 away below threshold`() {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val today = now.toLocalDate()
        val day3 = today.plusDays(3)
        val weatherByDate = mapOf(
            day3 to createWeather(
                date = day3.format(DateTimeFormatter.ISO_LOCAL_DATE),
                condition = "Rain",
                precipProbability = 12,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val day3Data = result.first { it.date == day3 }
        assertNull(day3Data.rainData.dailyRainLabelText)
    }

    // --- Past day observed precipitation tests ---

    @Test
    fun `past day with observed precip shows day rain label`() {
        val now = LocalDateTime.of(2026, 5, 27, 12, 0)
        val today = now.toLocalDate()
        val pastDay = today.minusDays(2)
        val pastDayStr = pastDay.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            pastDay to createWeather(pastDayStr, highTemp = 68f, lowTemp = 52f),
        )
        val dailyActuals = mapOf(
            pastDay to extreme(
                date = pastDay,
                high = 68f,
                low = 52f,
                condition = "Rain",
                precipAmountMm = 10.5f,
                precipDayMm = 6.2f,
                precipNightMm = 4.3f,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val pastDayData = result.first { it.date == pastDay }
        assertNotNull("Past day should have rain label", pastDayData.rainData.dailyRainLabelText)
        // Should show day precip amount (6.2mm converted to inches)
        assertTrue("Rain label should contain amount", pastDayData.rainData.dailyRainLabelText!!.contains("in"))
    }

    @Test
    fun `past day with observed precip shows night rain label`() {
        val now = LocalDateTime.of(2026, 5, 27, 12, 0)
        val today = now.toLocalDate()
        val pastDay = today.minusDays(2)
        val pastDayStr = pastDay.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            pastDay to createWeather(pastDayStr, highTemp = 68f, lowTemp = 52f),
        )
        val dailyActuals = mapOf(
            pastDay to extreme(
                date = pastDay,
                high = 68f,
                low = 52f,
                condition = "Rain",
                precipAmountMm = 10.5f,
                precipDayMm = 6.2f,
                precipNightMm = 4.3f,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val pastDayData = result.first { it.date == pastDay }
        assertNotNull("Past day should have night rain label", pastDayData.rainData.nightRainLabelText)
        // Should show night precip amount (4.3mm converted to inches)
        assertTrue("Night rain label should contain amount", pastDayData.rainData.nightRainLabelText!!.contains("in"))
    }

    @Test
    fun `past day with zero observed precip shows no rain label`() {
        val now = LocalDateTime.of(2026, 5, 27, 12, 0)
        val today = now.toLocalDate()
        val pastDay = today.minusDays(2)
        val pastDayStr = pastDay.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            pastDay to createWeather(pastDayStr, highTemp = 68f, lowTemp = 52f),
        )
        val dailyActuals = mapOf(
            pastDay to extreme(
                date = pastDay,
                high = 68f,
                low = 52f,
                condition = "Clear",
                precipAmountMm = 0f,
                precipDayMm = 0f,
                precipNightMm = 0f,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val pastDayData = result.first { it.date == pastDay }
        assertNull("Past day with zero precip should have no day rain label", pastDayData.rainData.dailyRainLabelText)
        assertNull("Past day with zero precip should have no night rain label", pastDayData.rainData.nightRainLabelText)
    }

    @Test
    fun `past day with null observed precip shows no rain label`() {
        val now = LocalDateTime.of(2026, 5, 27, 12, 0)
        val today = now.toLocalDate()
        val pastDay = today.minusDays(2)
        val pastDayStr = pastDay.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            pastDay to createWeather(pastDayStr, highTemp = 68f, lowTemp = 52f),
        )
        val dailyActuals = mapOf(
            pastDay to extreme(
                date = pastDay,
                high = 68f,
                low = 52f,
                condition = "Clear",
                precipAmountMm = null,
                precipDayMm = null,
                precipNightMm = null,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val pastDayData = result.first { it.date == pastDay }
        assertNull("Past day with null precip should have no day rain label", pastDayData.rainData.dailyRainLabelText)
        assertNull("Past day with null precip should have no night rain label", pastDayData.rainData.nightRainLabelText)
    }

    @Test
    fun `past day uses precipDayMm for day label when available`() {
        val now = LocalDateTime.of(2026, 5, 27, 12, 0)
        val today = now.toLocalDate()
        val pastDay = today.minusDays(2)
        val pastDayStr = pastDay.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val weatherByDate = mapOf(
            today to createWeather(today.format(DateTimeFormatter.ISO_LOCAL_DATE)),
            pastDay to createWeather(pastDayStr, highTemp = 68f, lowTemp = 52f),
        )
        // Has precipDayMm but no precipAmountMm
        val dailyActuals = mapOf(
            pastDay to extreme(
                date = pastDay,
                high = 68f,
                low = 52f,
                condition = "Rain",
                precipAmountMm = null,
                precipDayMm = 5.0f,
                precipNightMm = null,
            ),
        )

        val result = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today.minusDays(2),
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            dailyActuals = dailyActuals,
        )

        val pastDayData = result.first { it.date == pastDay }
        assertNotNull("Past day should have rain label from precipDayMm", pastDayData.rainData.dailyRainLabelText)
    }
}

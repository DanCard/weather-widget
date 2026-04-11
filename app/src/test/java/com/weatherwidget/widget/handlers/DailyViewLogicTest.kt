package com.weatherwidget.widget.handlers

import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@Config(sdk = [34])
@Category(MediumDuration::class)
class DailyViewLogicTest {

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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        val phantomDay = result.find { it.date == future }
        assertTrue("Future day with null high should now be present as empty column", phantomDay != null)
        assertEquals("Should have null high", null, phantomDay!!.high)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        assertEquals("Should render 9 days (NWS 0-6 + gap 7 + empty 8)", 9, result.size)
        val gapDay = result.find { it.date == gapDate }
        assertTrue("Gap day 7 should be present", gapDay != null)
        assertTrue("Gap day should be marked as gap fallback", gapDay!!.isSourceGapFallback)
        val nwsDays = result.filter { !it.isSourceGapFallback && it.high != null }
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
            java.time.MonthDay.from(future) to Pair(75, 60)
        )

        val result = DailyViewLogic.prepareGraphDays(
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = emptyList(),
            climateNormals = climateNormals
        )

        val normalDay = result.find { it.date == future }
        assertTrue("Future day with normals should be present", normalDay != null)
        assertEquals("Should use climate high", 75f, normalDay!!.high)
        assertEquals("Should use climate low", 60f, normalDay.low)
        assertTrue("Should be marked as climate overlay", normalDay.isClimateNormal)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 9,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        // With numColumns = 9, we expect 9 slots if NavigationUtils.getDayOffsets returns them
        // NavigationUtils.getDayOffsets(9, false) starts from -1, so it should go -1 to 7
        val emptyDay = result.find { it.date == future }
        assertTrue("Future day with no data should still be present in result list", emptyDay != null)
        assertEquals("Empty column should have null high", null, emptyDay!!.high)
        assertEquals("Empty column should have null low", null, emptyDay.low)
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
            java.time.MonthDay.from(future) to Pair(80, 65)
        )

        val result = DailyViewLogic.prepareTextDays(
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals("65%", futureDay.dailyRainLabelText)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals(".002in", futureDay.dailyRainLabelText)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val todayDay = result.first { it.date == today }
        assertNotNull(todayDay.dailyRainLabelText)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val todayDay = result.first { it.date == today }
        assertNull(todayDay.dailyRainLabelText)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals(".2in", futureDay.dailyRainLabelText)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals("98%", futureDay.dailyRainLabelText)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals("99%", futureDay.dailyRainLabelText)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals(null, futureDay.dailyRainLabelText)
    }

    @Test
    fun `rainy future day with 1 percent shows one percent`() {
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals("1%", futureDay.dailyRainLabelText)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.NWS,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        val futureDay = result.first { it.date == future }
        assertEquals(".002in", futureDay.dailyRainLabelText)
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
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = weatherByDate,
            forecastSnapshots = emptyMap(),
            numColumns = 3,
            displaySource = WeatherSource.OPEN_WEATHER_MAP,
            isEveningMode = false,
            skipHistory = true,
            hourlyForecasts = emptyList(),
        )

        assertEquals(R.drawable.ic_weather_clear, result.first { it.date == today }.iconRes)
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
        precipAmountMm: Float? = null,
    ): ForecastEntity {
        return ForecastEntity(
            targetDate = dateEpoch(date),
            forecastDate = dateEpoch(date),
            locationLat = 37.7749,
            locationLon = -122.4194,
            locationName = "Test",
            highTemp = highTemp,
            lowTemp = lowTemp,
            condition = condition,
            nativeDailyIconToken = nativeDailyIconToken,
            source = source,
            isClimateNormal = isClimateNormal,
            precipProbability = precipProbability,
            precipAmountMm = precipAmountMm,
            fetchedAt = 1L,
        )
    }
}

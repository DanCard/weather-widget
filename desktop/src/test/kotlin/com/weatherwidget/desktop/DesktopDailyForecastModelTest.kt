package com.weatherwidget.desktop

import com.weatherwidget.data.model.*
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopDailyForecastModelTest {

    private val config = DesktopConfig(
        lat = 37.4220,
        lon = -122.0841,
        label = "Test",
        weatherSource = "NWS",
        dateOffset = -1
    )

    private fun extreme(date: String, high: Float, low: Float, condition: String) = DailyHistory(
        date = LocalDate.parse(date).toEpochDay() * 86_400_000L,
        source = "NWS",
        locationLat = 37.4220,
        locationLon = -122.0841,
        computedHighTemp = high,
        computedLowTemp = low,
        condition = condition,
        updatedAt = System.currentTimeMillis()
    )


    /** Test fixture: builds a [ForecastSnapshot] from the old flat ForecastResult-shaped args. */
    private fun snapshot(
        currentTemp: Float? = null,
        currentCondition: String? = null,
        currentObservedAt: Long? = null,
        appliedDelta: Float? = null,
        deltaFromYesterday: Float? = null,
        daily: List<DailyForecast> = emptyList(),
        hourly: List<HourlyForecast> = emptyList(),
        rawObservations: List<ObservationReading> = emptyList(),
        dailyActuals: Map<String, DailyHistory> = emptyMap(),
        dailySnapshots: Map<String, List<DailyForecastSnapshot>> = emptyMap(),
    ): ForecastSnapshot = ForecastSnapshot(
        raw = RawFetch(
            hourly = hourly,
            daily = daily,
            rawObservations = rawObservations,
            dailyActuals = dailyActuals,
            dailySnapshots = dailySnapshots,
        ),
        resolved = ResolvedView(
            currentTemp = currentTemp,
            currentCondition = currentCondition,
            currentObservedAt = currentObservedAt,
            appliedDelta = appliedDelta,
            deltaFromYesterday = deltaFromYesterday,
        ),
    )

    @Test
    fun `build properly groups and maps daily data`() {
        val now = LocalDateTime.parse("2026-06-03T07:00:00")
        val state = DesktopDailyForecastModel.build(
            config = config,
            forecast = snapshot(
                currentTemp = 72.4f,
                currentCondition = "Sunny",
                daily = listOf(
                    DailyForecast("2026-06-03", 80f, 60f, "Sunny"),
                    DailyForecast("2026-06-04", 82f, 61f, "Partly Cloudy"),
                    DailyForecast("2026-06-05", 83f, 62f, "Rain", precipProbability = 40),
                ),
                dailyActuals = mapOf(
                    "2026-06-01" to extreme("2026-06-01", 77f, 56f, "Fair"),
                    "2026-06-02" to extreme("2026-06-02", 78f, 57f, "Fair"),
                ),
                dailySnapshots = mapOf(
                    "2026-06-01" to listOf(
                        DailyForecastSnapshot("2026-06-01", 75f, 57f, "Cloudy", fetchedAt = 1L),
                        DailyForecastSnapshot("2026-06-01", 51f, 51f, "Cloudy", fetchedAt = 2L),
                    ),
                    "2026-06-02" to listOf(
                        DailyForecastSnapshot("2026-06-02", 76f, 58f, "Cloudy", fetchedAt = 1L),
                    )
                )
            ),
            dimensions = DesktopDailyForecastModel.dimensions(600, 400),
            now = now
        )

        assertEquals(8, state.days.size) // detailed Today mode removes one date from the 9-column base
        assertTrue(state.largeTodayOverlayEnabled)

        val jun1 = state.days.find { it.date == LocalDate.parse("2026-06-01") }!!
        assertEquals(77f, jun1.solidHigh)
        assertEquals(56f, jun1.solidLow)
        assertEquals(75f, jun1.forecastHigh)
        assertEquals(57f, jun1.forecastLow)

        val jun3 = state.days.find { it.date == LocalDate.parse("2026-06-03") }!!
        assertTrue(jun3.isToday)
        assertEquals(80f, jun3.forecastHigh)
        assertEquals(60f, jun3.forecastLow)
        // Today's solid high is max(actual.high, current)
        assertEquals(72.4f, jun3.solidHigh) 
    }

    @Test
    fun `past day prefers frozen overlay and noon cloud from daily_history over snapshots`() {
        val now = LocalDateTime.parse("2026-06-03T07:00:00")
        val state = DesktopDailyForecastModel.build(
            config = config,
            forecast = snapshot(
                currentTemp = 72.4f,
                currentCondition = "Sunny",
                daily = listOf(DailyForecast("2026-06-03", 80f, 60f, "Sunny")),
                dailyActuals = mapOf(
                    "2026-06-01" to extreme("2026-06-01", 77f, 56f, "Fair").copy(
                        forecastHighTemp = 74f,
                        forecastLowTemp = 52f,
                        noonCloudPercent = 60,
                    ),
                ),
                dailySnapshots = mapOf(
                    // Bait: the snapshot table must lose to the frozen daily_history values.
                    "2026-06-01" to listOf(
                        DailyForecastSnapshot("2026-06-01", 75f, 57f, "Cloudy", fetchedAt = 1L),
                    ),
                ),
            ),
            dimensions = DesktopDailyForecastModel.dimensions(600, 400),
            now = now,
        )

        val jun1 = state.days.find { it.date == LocalDate.parse("2026-06-01") }!!
        assertEquals(74f, jun1.forecastHigh)
        assertEquals(52f, jun1.forecastLow)
        assertEquals(0.6f, jun1.cloudCoverRatio!!, 0.001f)
    }

    @Test
    fun `past day falls back to snapshot when frozen columns are null`() {
        val now = LocalDateTime.parse("2026-06-03T07:00:00")
        val state = DesktopDailyForecastModel.build(
            config = config,
            forecast = snapshot(
                currentTemp = 72.4f,
                currentCondition = "Sunny",
                daily = listOf(DailyForecast("2026-06-03", 80f, 60f, "Sunny")),
                dailyActuals = mapOf(
                    "2026-06-01" to extreme("2026-06-01", 77f, 56f, "Fair"),
                ),
                dailySnapshots = mapOf(
                    "2026-06-01" to listOf(
                        DailyForecastSnapshot("2026-06-01", 75f, 57f, "Cloudy", fetchedAt = 1L),
                    ),
                ),
            ),
            dimensions = DesktopDailyForecastModel.dimensions(600, 400),
            now = now,
        )

        val jun1 = state.days.find { it.date == LocalDate.parse("2026-06-01") }!!
        assertEquals(75f, jun1.forecastHigh)
        assertEquals(57f, jun1.forecastLow)
    }

    @Test
    fun `today thermostat tracks high-water mark via ghostHigh`() {
        // Evening case: the day already peaked above the current reading, so the solid mercury
        // sits at the current temp while the ghost preserves the day's high.
        val now = LocalDateTime.parse("2026-06-03T20:00:00")
        val state = DesktopDailyForecastModel.build(
            config = config,
            forecast = snapshot(
                currentTemp = 72.4f,
                currentCondition = "Sunny",
                daily = listOf(DailyForecast("2026-06-03", 97f, 60f, "Sunny")),
                dailyActuals = mapOf(
                    "2026-06-03" to extreme("2026-06-03", 97.7f, 60.3f, "Sunny"),
                ),
            ),
            dimensions = DesktopDailyForecastModel.dimensions(600, 400),
            now = now,
        )

        val today = state.days.find { it.date == LocalDate.parse("2026-06-03") }!!
        assertTrue(today.isToday)
        // Mercury sits at the current temp...
        assertEquals(72.4f, today.solidHigh)
        // ...while the ghost preserves the day's observed peak.
        assertEquals(97.7f, today.ghostHigh)
    }

    @Test
    fun `isIconWidth detection works`() {
        val narrow = DesktopDailyForecastModel.dimensions(120, 100)
        assertTrue(narrow.isIconWidth)
        assertFalse(narrow.useGraph)

        val wide = DesktopDailyForecastModel.dimensions(600, 400)
        assertFalse(wide.isIconWidth)
        assertTrue(wide.useGraph)
    }

    // --- Scroll-wheel zoom (history-biased) ---------------------------------------------------

    private fun actualsRange(startDate: String, days: Int): Map<String, DailyHistory> {
        val start = LocalDate.parse(startDate)
        return (0 until days).associate { i ->
            val d = start.plusDays(i.toLong()).toString()
            d to extreme(d, 70f, 50f, "Fair")
        }
    }

    private fun forecastRange(startDate: String, days: Int): List<DailyForecast> {
        val start = LocalDate.parse(startDate)
        return (0 until days).map { i -> DailyForecast(start.plusDays(i.toLong()).toString(), 80f, 60f, "Sunny") }
    }

    @Test
    fun `zoom-out prepends history days and anchors the right edge`() {
        val now = LocalDateTime.parse("2026-06-10T07:00:00")
        val forecast = snapshot(
            daily = forecastRange("2026-06-10", 8),       // today..+7
            dailyActuals = actualsRange("2026-06-01", 9), // 06-01..06-09
        )
        val cfg = config.copy(dateOffset = 0, dailyExtraHistory = 3)
        val state = DesktopDailyForecastModel.build(cfg, forecast, DesktopDailyForecastModel.dimensions(600, 400), now)

        // Overlay-enabled → 8 base columns + 3 prepended history days.
        assertEquals(3, state.clampedExtraHistory)
        assertEquals(11, state.days.size)
        // Base left edge is yesterday (06-09); three more history days push it to 06-06.
        assertEquals(LocalDate.parse("2026-06-06"), state.days.first().date)
        // Right edge stays anchored at the base rightmost (one fewer column now, so 06-16).
        assertEquals(LocalDate.parse("2026-06-16"), state.days.last().date)
        assertTrue(state.canZoomOut) // 3 < available history (8)
        assertTrue(state.canZoomIn)  // 3 > 0
    }

    @Test
    fun `zoom-out clamps to the earliest available history date`() {
        val now = LocalDateTime.parse("2026-06-10T07:00:00")
        val forecast = snapshot(
            daily = forecastRange("2026-06-10", 8),
            dailyActuals = actualsRange("2026-06-01", 9), // earliest = 06-01
        )
        val cfg = config.copy(dateOffset = 0, dailyExtraHistory = 20)
        val state = DesktopDailyForecastModel.build(cfg, forecast, DesktopDailyForecastModel.dimensions(600, 400), now)

        // Available history left of the base edge (06-09) is 8 days, so extra clamps to 8.
        assertEquals(8, state.clampedExtraHistory)
        assertEquals(LocalDate.parse("2026-06-01"), state.days.first().date)
        assertFalse(state.canZoomOut) // already at the earliest data
        assertTrue(state.canZoomIn)
    }

    @Test
    fun `zoom-out is capped even when more history data exists`() {
        val now = LocalDateTime.parse("2026-06-10T07:00:00")
        val forecast = snapshot(
            daily = forecastRange("2026-06-10", 8),
            dailyActuals = actualsRange("2026-05-20", 21), // 05-20..06-09, 20 days left of base edge
        )
        val cfg = config.copy(dateOffset = 0, dailyExtraHistory = 30)
        val state = DesktopDailyForecastModel.build(cfg, forecast, DesktopDailyForecastModel.dimensions(600, 400), now)

        // Capped at DAILY_MAX_EXTRA_HISTORY (14) despite 20 days of available history.
        assertEquals(14, state.clampedExtraHistory)
        assertEquals(8 + 14, state.days.size)
        assertEquals(LocalDate.parse("2026-05-26"), state.days.first().date) // 06-09 minus 14
        assertFalse(state.canZoomOut)
    }

    @Test
    fun `default view has no extra history but can still zoom out`() {
        val now = LocalDateTime.parse("2026-06-10T07:00:00")
        val forecast = snapshot(
            daily = forecastRange("2026-06-10", 8),
            dailyActuals = actualsRange("2026-06-01", 9),
        )
        val cfg = config.copy(dateOffset = 0, dailyExtraHistory = 0)
        val state = DesktopDailyForecastModel.build(cfg, forecast, DesktopDailyForecastModel.dimensions(600, 400), now)

        assertEquals(0, state.clampedExtraHistory)
        assertEquals(8, state.days.size)
        assertTrue(state.largeTodayOverlayEnabled)
        assertFalse(state.canZoomIn) // nothing to trim
        assertTrue(state.canZoomOut) // history available to reveal
    }

    @Test
    fun `large desktop Today overlay uses dominant raw temperature and Blend age`() {
        val now = LocalDateTime.parse("2026-08-04T08:20:00")
        val forecast = overlayForecast()

        val state = DesktopDailyForecastModel.build(
            config.copy(
                dateOffset = 0,
                useCelsius = false,
                todayOverlayDelta = true,
                todayOverlayDominantTemp = true,
                todayOverlayDominantAge = true,
            ),
            forecast,
            DesktopDailyForecastModel.dimensions(600, 400),
            now,
        )

        assertTrue(state.largeTodayOverlayEnabled)
        assertEquals(8, state.days.size)
        assertEquals("62.6°", state.todayOverlay?.dominantTempText)
        assertEquals("0m", state.todayOverlay?.dominantAgeText)
        assertEquals("fcst", state.todayOverlay?.deltaCaptionText)
        assertEquals("+0.4", state.todayOverlay?.deltaValueText)
    }

    @Test
    fun `large desktop Today overlay is null when all overlay toggles default off`() {
        val now = LocalDateTime.parse("2026-08-04T08:20:00")
        val state = DesktopDailyForecastModel.build(
            config.copy(dateOffset = 0, useCelsius = false),
            overlayForecast(),
            DesktopDailyForecastModel.dimensions(600, 400),
            now,
        )

        assertTrue(state.largeTodayOverlayEnabled)
        assertNull(state.todayOverlay)
    }

    @Test
    fun `large desktop Today overlay shows reading age alone when only age toggle on`() {
        val now = LocalDateTime.parse("2026-08-04T08:20:00")
        val state = DesktopDailyForecastModel.build(
            config.copy(dateOffset = 0, useCelsius = false, todayOverlayDominantAge = true),
            overlayForecast(),
            DesktopDailyForecastModel.dimensions(600, 400),
            now,
        )

        assertNull(state.todayOverlay?.deltaValueText)
        assertNull(state.todayOverlay?.dominantTempText)
        assertEquals("0m", state.todayOverlay?.dominantAgeText)
    }

    private fun overlayForecast(): ForecastSnapshot {
        fun reading(station: String, local: String, temp: Float, distanceKm: Float) =
            ObservationReading(
                stationId = station,
                stationName = station,
                timestamp = LocalDateTime.parse(local).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                temperature = temp,
                condition = "observed",
                locationLat = config.lat,
                locationLon = config.lon,
                distanceKm = distanceKm,
                stationType = "OFFICIAL",
                api = WeatherSource.NWS.id,
                fetchedAt = LocalDateTime.parse(local).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            )
        return snapshot(
            daily = forecastRange("2026-08-04", 8),
            // The overlay delta row shows the FORECAST delta (swapped with the header).
            appliedDelta = 0.4f,
            rawObservations = listOf(
                reading("DOM", "2026-08-03T08:20:00", 60f, 0.2f),
                reading("FAR", "2026-08-03T08:20:00", 70f, 20f),
                reading("DOM", "2026-08-04T08:20:00", 62.6f, 0.2f),
                reading("FAR", "2026-08-04T08:20:00", 70f, 20f),
            ),
        )
    }

    @Test
    fun `zoom reveals history even when skip-yesterday drops it`() {
        // Narrow (5 col) widget after 8am: skip-yesterday normally hides history at offset 0.
        val now = LocalDateTime.parse("2026-06-10T09:00:00")
        val forecast = snapshot(
            daily = forecastRange("2026-06-10", 5),
            dailyActuals = actualsRange("2026-06-01", 9),
        )
        val cfg = config.copy(dateOffset = 0, dailyExtraHistory = 2)
        val state = DesktopDailyForecastModel.build(cfg, forecast, DesktopDailyForecastModel.dimensions(300, 400), now)

        assertTrue(state.skipYesterday)
        assertEquals(2, state.clampedExtraHistory)
        // Base window is today-first (06-10..); the 2 extra history days bring back 06-08 and 06-09.
        assertEquals(LocalDate.parse("2026-06-08"), state.days.first().date)
        assertTrue(state.days.any { it.date == LocalDate.parse("2026-06-10") && it.isToday })
    }

    @Test
    fun `detects available dates correctly for navigation`() {
        val now = LocalDateTime.parse("2026-06-03T07:00:00")
        val forecast = snapshot(
            daily = listOf(DailyForecast("2026-06-03", 80f, 60f, "Sunny")),
            dailyActuals = mapOf("2026-06-01" to extreme("2026-06-01", 77f, 56f, "Fair"))
        )
        val state = DesktopDailyForecastModel.build(config, forecast, DesktopDailyForecastModel.dimensions(600, 400), now)
        
        assertFalse(state.canNavigateLeft) // Already at the earliest date (June 1st)
        assertFalse(state.canNavigateRight) // No data past today
    }
}

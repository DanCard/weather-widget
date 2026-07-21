package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.shared.util.DayClickResolver
import com.weatherwidget.shared.util.WeatherConditionResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyForecastGraphTapZoneTest {

    /**
     * Future-day column with real high/low temps — mirrors Jul 7-style taps where the icon is drawn
     * on the bar geometry above the fixed bottom strip.
     */
    private fun futureGraphDay(
        date: LocalDate,
        high: Float,
        low: Float,
        iconName: String = WeatherConditionResolver.IC_MOSTLY_CLEAR,
    ) = DesktopDailyDay(
        date = date,
        label = "Tue",
        forecast = DailyForecast(date.toString(), high, low, "Mostly Sunny", precipProbability = 0),
        actual = null,
        snapshot = null,
        solidHigh = high,
        solidLow = low,
        forecastHigh = null,
        forecastLow = null,
        ghostHigh = null,
        snapshotHigh = null,
        snapshotLow = null,
        iconCondition = "Mostly Sunny",
        iconName = iconName,
        isToday = false,
        isPast = false,
        cloudCoverRatio = 0.35f,
        dailyRainLabelText = null,
        nightRainLabelText = null,
        dayPrecipProbability = null,
        nightPrecipProbability = null,
        daysFromToday = 1,
        isClimateNormal = false,
    )

    private fun layout(
        iconTops: List<Float?> = listOf(400f),
        bottomStrip: Float = 80f,
        canvasHeight: Float = 600f,
        dayWidth: Float = 100f,
        iconSize: Float = 30f,
    ) = DailyGraphTapLayout(dayWidth, iconSize, iconTops, bottomStrip, canvasHeight)

    @Test
    fun tapAboveBottomStrip_isMainColumn() {
        val strip = dailyGraphBottomStripHeightPx(
            canvasWidth = 900f,
            dayCount = 7,
            scale = 1f,
            density = 3f,
        )
        assertEquals(
            DayClickResolver.DayTapZone.MAIN_COLUMN,
            classifyDailyGraphTapZone(
                tapX = 50f,
                tapY = 400f,
                columnIndex = 0,
                // Icon sits in the bottom strip only — tap at y=400 is bar body, not on the icon.
                layout = layout(iconTops = listOf(600f - strip - 40f), bottomStrip = strip),
            ),
        )
    }

    @Test
    fun tapInBottomStrip_isBottomIcon() {
        val strip = dailyGraphBottomStripHeightPx(
            canvasWidth = 900f,
            dayCount = 7,
            scale = 1f,
            density = 3f,
        )
        assertEquals(
            DayClickResolver.DayTapZone.BOTTOM_ICON,
            classifyDailyGraphTapZone(
                tapX = 50f,
                tapY = 600f - strip + 1f,
                columnIndex = 0,
                layout = layout(bottomStrip = strip),
            ),
        )
    }

    @Test
    fun tapOnRenderedIconAboveBottomStrip_isBottomIcon() {
        // Icon drawn mid-graph (cold low pushes it up) must still route as bottom-icon tap.
        assertEquals(
            DayClickResolver.DayTapZone.BOTTOM_ICON,
            classifyDailyGraphTapZone(
                tapX = 50f,
                tapY = 410f,
                columnIndex = 0,
                layout = layout(iconTops = listOf(400f), bottomStrip = 80f),
            ),
        )
    }

    @Test
    fun tapBesideIconAboveBottomStrip_isMainColumn() {
        assertEquals(
            DayClickResolver.DayTapZone.MAIN_COLUMN,
            classifyDailyGraphTapZone(
                tapX = 10f,
                tapY = 410f,
                columnIndex = 0,
                layout = layout(iconTops = listOf(400f), bottomStrip = 80f),
            ),
        )
    }

    @Test
    fun computeLayout_iconAboveBottomStrip_tapRoutesToCloudCover() {
        // Regression: Jul 7 cloud-icon tap opened temperature because the icon sits on the bar,
        // above the fixed bottom strip — layout math must classify icon-center taps as BOTTOM_ICON.
        val targetDate = LocalDate.of(2026, 7, 7)
        val days = listOf(
            futureGraphDay(targetDate.minusDays(1), high = 94f, low = 68f),
            futureGraphDay(targetDate, high = 79f, low = 48f),
            futureGraphDay(targetDate.plusDays(1), high = 91f, low = 63f),
        )
        val canvasW = 600f
        val canvasH = 400f
        val scale = 1.5f
        val density = 2f
        val targetCol = 1

        val tapLayout = computeDailyGraphTapLayout(days, canvasW, canvasH, scale, density, useCelsius = false)
        val iconTop = tapLayout.iconTops[targetCol]
        assertNotNull(iconTop)
        val stripStart = canvasH - tapLayout.bottomStripHeightPx
        assertTrue(
            "icon top ($iconTop) must be above bottom strip ($stripStart)",
            iconTop!! < stripStart,
        )

        val centerX = tapLayout.dayWidth * targetCol + tapLayout.dayWidth / 2f
        val iconCenterY = iconTop + tapLayout.iconSize / 2f
        val zone = classifyDailyGraphTapZone(centerX, iconCenterY, targetCol, tapLayout)
        assertEquals(DayClickResolver.DayTapZone.BOTTOM_ICON, zone)

        val routed = dayClickConfig(
            DesktopConfig(lat = 37.42, lon = -122.08, label = "test", visibleSources = listOf("NWS")),
            targetDate,
            days,
            zone,
        )
        assertEquals(ViewMode.CLOUD_COVER, routed.viewMode)

        // Bar-body tap on the same column still routes to hourly temperature.
        val mainZone = classifyDailyGraphTapZone(centerX, iconTop - 20f, targetCol, tapLayout)
        assertEquals(DayClickResolver.DayTapZone.MAIN_COLUMN, mainZone)
        assertEquals(
            ViewMode.HOURLY,
            dayClickConfig(
                DesktopConfig(lat = 37.42, lon = -122.08, label = "test", visibleSources = listOf("NWS")),
                targetDate,
                days,
                mainZone,
            ).viewMode,
        )
    }
}
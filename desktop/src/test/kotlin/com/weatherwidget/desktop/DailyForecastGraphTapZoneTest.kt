package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.shared.util.DayClickResolver
import com.weatherwidget.shared.util.WeatherConditionResolver
import com.weatherwidget.shared.graph.DailyBottomStack
import com.weatherwidget.shared.graph.WeightedColumnLayout
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
        barTopHigh = high,
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
        solidIsForecastFallback = false,
    )

    private fun layout(
        iconTops: List<Float?> = listOf(400f),
        bottomStrip: Float = 80f,
        canvasHeight: Float = 600f,
        dayWidth: Float = 100f,
        iconSize: Float = 30f,
    ) = DailyGraphTapLayout(
        columns = WeightedColumnLayout.resolve(dayWidth * iconTops.size.coerceAtLeast(1), iconTops.size.coerceAtLeast(1), null, false),
        iconSize = iconSize,
        iconTops = iconTops,
        bottomStripHeightPx = bottomStrip,
        canvasHeight = canvasHeight,
    )

    /** The tap strip of a 600 px canvas, 7 columns of 900 px, density 3. */
    private fun bottomStrip(): Float =
        desktopBottomStack(
            canvasHeight = 600f,
            dayWidth = 900f / 7,
            iconSize = (30f * 3f).coerceAtMost(900f / 7 * 0.6f),
            scale = 1f,
            density = 3f,
            measureLowLabelHeight = { _, base -> base * 1.4f },
            measureDayLabelHeight = { _, sp -> sp * 1.4f },
        ).tapStripHeightPx

    @Test
    fun tapAboveBottomStrip_isMainColumn() {
        val strip = bottomStrip()
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
    fun tapAboveCanvasMidpoint_isMainColumnUpper() {
        // The chevrons centre on canvasHeight/2, so that line is the one the user can see.
        assertEquals(
            DayClickResolver.DayTapZone.MAIN_COLUMN_UPPER,
            classifyDailyGraphTapZone(
                tapX = 10f,
                tapY = 299f,
                columnIndex = 0,
                layout = layout(iconTops = listOf(480f), bottomStrip = 80f, canvasHeight = 600f),
            ),
        )
        assertEquals(
            DayClickResolver.DayTapZone.MAIN_COLUMN,
            classifyDailyGraphTapZone(
                tapX = 10f,
                tapY = 301f,
                columnIndex = 0,
                layout = layout(iconTops = listOf(480f), bottomStrip = 80f, canvasHeight = 600f),
            ),
        )
    }

    @Test
    fun tapOnIconFloatedAboveMidpoint_staysBottomIcon() {
        // A cold low can push the icon into the upper half. A direct hit on the glyph is an aimed
        // tap, not a body tap, so it keeps its icon-home routing.
        assertEquals(
            DayClickResolver.DayTapZone.BOTTOM_ICON,
            classifyDailyGraphTapZone(
                tapX = 50f,
                tapY = 210f,
                columnIndex = 0,
                layout = layout(iconTops = listOf(200f), bottomStrip = 80f, canvasHeight = 600f),
            ),
        )
    }

    @Test
    fun upperHalfOfRainyColumn_routesToTemperature() {
        val targetDate = LocalDate.of(2026, 7, 7)
        val days = listOf(
            futureGraphDay(targetDate, high = 79f, low = 48f, iconName = WeatherConditionResolver.IC_RAIN)
                .copy(
                    forecast = DailyForecast(
                        targetDate.toString(), 79f, 48f, "Rain", precipProbability = 100,
                    ),
                ),
        )
        val config = DesktopConfig(
            lat = 37.42,
            lon = -122.08,
            label = "test",
            settings = DesktopSettings(visibleSources = listOf("NWS")),
        )
        assertEquals(
            ViewMode.PRECIPITATION,
            dayClickConfig(
                config, targetDate, days, DayClickResolver.DayTapZone.MAIN_COLUMN,
            ).viewMode,
        )
        assertEquals(
            ViewMode.TEMPERATURE,
            dayClickConfig(
                config, targetDate, days, DayClickResolver.DayTapZone.MAIN_COLUMN_UPPER,
            ).viewMode,
        )
    }

    @Test
    fun tapInBottomStrip_isBottomIcon() {
        val strip = bottomStrip()
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
            futureGraphDay(targetDate, high = 79f, low = 50f),
            // The coldest column's icon starts at the strip (the strip is exactly the stack under
            // the coldest bar), so the target is a warmer one whose icon floats above it.
            futureGraphDay(targetDate.plusDays(1), high = 91f, low = 48f),
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

        val centerX = tapLayout.columns.centers[targetCol]
        val iconCenterY = iconTop + tapLayout.iconSize / 2f
        val zone = classifyDailyGraphTapZone(centerX, iconCenterY, targetCol, tapLayout)
        assertEquals(DayClickResolver.DayTapZone.BOTTOM_ICON, zone)

        val routed = dayClickConfig(
            DesktopConfig(
lat = 37.42,
lon = -122.08,
label = "test",
settings = DesktopSettings(visibleSources = listOf("NWS")),
),
            targetDate,
            days,
            zone,
        )
        assertEquals(ViewMode.CLOUD_COVER, routed.viewMode)

        // Bar-body tap on the same column still routes to hourly temperature.
        val mainZone = classifyDailyGraphTapZone(centerX, iconTop - 20f, targetCol, tapLayout)
        assertEquals(DayClickResolver.DayTapZone.MAIN_COLUMN, mainZone)
        assertEquals(
            ViewMode.TEMPERATURE,
            dayClickConfig(
                DesktopConfig(
lat = 37.42,
lon = -122.08,
label = "test",
settings = DesktopSettings(visibleSources = listOf("NWS")),
),
                targetDate,
                days,
                mainZone,
            ).viewMode,
        )
    }

    @Test
    fun weightedTodayColumnControlsDesktopTapLookup() {
        val start = LocalDate.of(2026, 8, 4)
        val days = (0 until 8).map { index ->
            futureGraphDay(start.plusDays(index.toLong()), 80f, 60f).copy(isToday = index == 1)
        }
        val layout = computeDailyGraphTapLayout(
            days = days,
            canvasWidth = 825f,
            canvasHeight = 400f,
            scale = 1f,
            density = 1f,
            useCelsius = false,
            widenToday = true,
        )

        assertEquals(125f, layout.columns.widths[1], 0.001f)
        assertEquals(1, layout.columns.indexAt(224.9f))
        assertEquals(2, layout.columns.indexAt(225f))
    }

    @Test
    fun todayTapRoutesToPrecipitationWhenRainInside6HourWindow() {
        val now = java.time.LocalDateTime.of(2026, 8, 4, 6, 0)
        val today = now.toLocalDate()
        val zone = java.time.ZoneId.systemDefault()
        fun toEpoch(dt: java.time.LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        val todayDay = futureGraphDay(today, high = 75f, low = 55f, iconName = WeatherConditionResolver.IC_RAIN).copy(
            isToday = true,
            daysFromToday = 0,
            forecast = DailyForecast(today.toString(), 75f, 55f, "Rain", precipProbability = 40),
        )

        val hourly = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(3)),
                temperature = 65f,
                condition = "Rain",
                precipProbability = 40,
                source = "NWS",
            ),
        )

        val config = DesktopConfig(
            lat = 37.42,
            lon = -122.08,
            label = "test",
            settings = DesktopSettings(visibleSources = listOf("NWS")),
        )

        val routed = dayClickConfig(
            config = config,
            clickedDate = today,
            days = listOf(todayDay),
            zone = DayClickResolver.DayTapZone.MAIN_COLUMN,
            now = now,
            hourly = hourly,
        )

        assertEquals(ViewMode.PRECIPITATION, routed.viewMode)
    }

    @Test
    fun todayTapRoutesToTemperatureWhenRainOutside6HourWindow() {
        val now = java.time.LocalDateTime.of(2026, 8, 4, 6, 0)
        val today = now.toLocalDate()
        val zone = java.time.ZoneId.systemDefault()
        fun toEpoch(dt: java.time.LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        val todayDay = futureGraphDay(today, high = 75f, low = 55f, iconName = WeatherConditionResolver.IC_RAIN).copy(
            isToday = true,
            daysFromToday = 0,
            forecast = DailyForecast(today.toString(), 75f, 55f, "Rain", precipProbability = 40),
        )

        val hourly = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now),
                temperature = 58f,
                condition = "Clear",
                precipProbability = 0,
                source = "NWS",
            ),
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(6)),
                temperature = 72f,
                condition = "Clear",
                precipProbability = 0,
                source = "NWS",
            ),
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(8)),
                temperature = 68f,
                condition = "Rain",
                precipProbability = 40,
                source = "NWS",
            ),
        )

        val config = DesktopConfig(
            lat = 37.42,
            lon = -122.08,
            label = "test",
            settings = DesktopSettings(visibleSources = listOf("NWS")),
        )

        val routed = dayClickConfig(
            config = config,
            clickedDate = today,
            days = listOf(todayDay),
            zone = DayClickResolver.DayTapZone.MAIN_COLUMN,
            now = now,
            hourly = hourly,
        )

        assertEquals(ViewMode.TEMPERATURE, routed.viewMode)
    }

    @Test
    fun undrawnFutureSnapshot_doesNotStretchAxis() {
        // A future column's prior-day snapshot is never drawn, so a hotter snapshot must not move
        // the bars (Nov 3 2026: a 75° snapshot left the 71° bar well below the canvas top).
        val start = LocalDate.of(2026, 11, 2)
        val days = listOf(
            futureGraphDay(start, high = 71f, low = 57f),
            futureGraphDay(start.plusDays(1), high = 66f, low = 55f),
        )
        val withSnapshot = days.mapIndexed { i, d -> if (i == 0) d.copy(snapshotHigh = 75f, snapshotLow = 58f) else d }

        val plain = computeDailyGraphTapLayout(days, 400f, 600f, 1f, 1f, useCelsius = false)
        val snapped = computeDailyGraphTapLayout(withSnapshot, 400f, 600f, 1f, 1f, useCelsius = false)

        assertEquals(plain.iconTops, snapped.iconTops)
    }

    @Test
    fun bottomStrip_isTheMeasuredStackOnly() {
        // The strip below the bars holds exactly icon + low label + day name and their gaps. The old
        // reserve padded each band (font x1.4 + 4, font x1.5 + 6, + 6) and left ~50 px unused under
        // the coldest bar at the user's window (plans/261029-shared-daily-bottom-stack.md).
        val start = LocalDate.of(2026, 11, 2)
        val days = (0 until 4).map { futureGraphDay(start.plusDays(it.toLong()), high = 70f - it, low = 50f - it) }
        val layout = computeDailyGraphTapLayout(
            days, canvasWidth = 800f, canvasHeight = 600f, scale = 1f, density = 1f, useCelsius = false,
            measureLowLabelHeight = { _, _ -> 20f },
            measureDayLabelHeight = { _, _ -> 15f },
        )
        val margin = 6f
        val gaps = 3f + 0f + 1f
        assertEquals(margin + 15f + gaps + layout.iconSize + 20f, layout.bottomStripHeightPx, 0.001f)
    }

    @Test
    fun tapLayout_usesTheDrawnGraphTop() {
        // The draw pass solves the graph top with DailyTopStack; taps must resolve icons on that same
        // axis, or a lowered top would leave the icon hit boxes where the icons used to be.
        val start = LocalDate.of(2026, 11, 2)
        val days = listOf(
            futureGraphDay(start, high = 80f, low = 70f),
            futureGraphDay(start.plusDays(1), high = 60f, low = 40f),
        )
        val provisional = computeDailyGraphTapLayout(days, 400f, 600f, 1f, 1f, useCelsius = false)
        val solved = computeDailyGraphTapLayout(days, 400f, 600f, 1f, 1f, useCelsius = false, graphTop = 150f)
        // The warm column's icon hangs under its 70° low, which moves down with the graph top.
        assertTrue(solved.iconTops[0]!! > provisional.iconTops[0]!!)
    }

    @Test
    fun coldestBar_endsAtGraphBottom() {
        // No axis padding (shared DailyAxisRange): the coldest bar runs to the graph bottom, so its
        // icon hangs right at the top of the measured strip. Desktop used to pad 4 % (at least 1°)
        // below the coldest temperature, leaving ~17 px empty at the user's window.
        val start = LocalDate.of(2026, 10, 27)
        val days = listOf(
            futureGraphDay(start, high = 66f, low = 48f),
            futureGraphDay(start.plusDays(1), high = 70f, low = 44.5f),
        )
        val layout = computeDailyGraphTapLayout(days, 400f, 600f, scale = 1f, density = 1f, useCelsius = false)
        val graphBottom = layout.canvasHeight - layout.bottomStripHeightPx
        assertEquals(graphBottom + DailyBottomStack.BAR_ICON_GAP_DP, layout.iconTops[1]!!, 0.001f)
    }

    @Test
    fun bottomStrip_reclaimsBlankSpaceAroundTheInk() {
        // Digits sit on the baseline and day names start at their capitals: the blank parts of both
        // line boxes are reclaimed down to a LOW_DAY_INK_GAP_DP visible gap.
        val start = LocalDate.of(2026, 11, 2)
        val days = (0 until 4).map { futureGraphDay(start.plusDays(it.toLong()), high = 70f - it, low = 50f - it) }
        fun strip(blankBelow: Float, blankAbove: Float) = computeDailyGraphTapLayout(
            days, canvasWidth = 800f, canvasHeight = 600f, scale = 1f, density = 1f, useCelsius = false,
            measureLowLabelHeight = { _, _ -> 20f },
            measureDayLabelHeight = { _, _ -> 15f },
            measureLowLabelBlankBelow = { _, _ -> blankBelow },
            measureDayLabelBlankAbove = { _, _ -> blankAbove },
        ).bottomStripHeightPx
        val topUp = DailyBottomStack.LOW_DAY_INK_GAP_DP - DailyBottomStack.LOW_DAY_GAP_DP
        assertEquals(strip(0f, 0f) - (7f + 3f - topUp), strip(7f, 3f), 0.001f)
    }
}

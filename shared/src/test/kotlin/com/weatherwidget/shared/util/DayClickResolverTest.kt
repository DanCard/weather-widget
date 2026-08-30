package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DayClickResolverTest {

    @Test
    fun mainColumn_rainyIconWith16Percent_navigatesToPrecipitation() {
        assertEquals(
            DayClickResolver.DayClickView.PRECIPITATION,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                WeatherConditionResolver.IC_RAIN,
                16,
            ),
        )
    }

    @Test
    fun mainColumn_cloudEligibleIcon_navigatesToTemperature() {
        assertEquals(
            DayClickResolver.DayClickView.TEMPERATURE,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                WeatherConditionResolver.IC_MOSTLY_CLEAR,
                0,
            ),
        )
    }

    @Test
    fun mainColumn_clearIcon_navigatesToTemperature() {
        assertEquals(
            DayClickResolver.DayClickView.TEMPERATURE,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                WeatherConditionResolver.IC_CLEAR,
                0,
            ),
        )
    }

    @Test
    fun bottomIcon_rain_navigatesToPrecipitation() {
        assertEquals(
            DayClickResolver.DayClickView.PRECIPITATION,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.BOTTOM_ICON,
                WeatherConditionResolver.IC_SNOW,
                null,
            ),
        )
    }

    @Test
    fun bottomIcon_cloudy_navigatesToCloudCover() {
        assertEquals(
            DayClickResolver.DayClickView.CLOUD_COVER,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.BOTTOM_ICON,
                WeatherConditionResolver.IC_PARTLY_CLOUDY,
                null,
            ),
        )
    }

    @Test
    fun bottomIcon_mostlyClear_navigatesToCloudCover() {
        assertEquals(
            DayClickResolver.DayClickView.CLOUD_COVER,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.BOTTOM_ICON,
                WeatherConditionResolver.IC_MOSTLY_CLEAR,
                null,
            ),
        )
    }

    @Test
    fun bottomIcon_clear_navigatesToTemperature() {
        assertEquals(
            DayClickResolver.DayClickView.TEMPERATURE,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.BOTTOM_ICON,
                WeatherConditionResolver.IC_CLEAR,
                null,
            ),
        )
    }

    @Test
    fun bottomIcon_chanceRainMixed_navigatesToPrecipitation() {
        assertEquals(
            DayClickResolver.DayClickView.PRECIPITATION,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.BOTTOM_ICON,
                WeatherConditionResolver.IC_PARTLY_CLOUDY_CHANCE_RAIN,
                null,
            ),
        )
    }

    @Test
    fun mainColumn_chanceRainMixedWithHighPrecip_navigatesToPrecipitation() {
        assertEquals(
            DayClickResolver.DayClickView.PRECIPITATION,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                WeatherConditionResolver.IC_PARTLY_CLOUDY_CHANCE_RAIN,
                60,
            ),
        )
    }

    @Test
    fun mainColumn_rainBelow16Percent_navigatesToTemperature() {
        assertEquals(
            DayClickResolver.DayClickView.TEMPERATURE,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                WeatherConditionResolver.IC_RAIN,
                15,
            ),
        )
        assertEquals(
            DayClickResolver.DayClickView.TEMPERATURE,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                WeatherConditionResolver.IC_RAIN,
                null,
            ),
        )
    }

    @Test
    fun offset_isZeroForTodayRegardlessOfTime() {
        val today = LocalDate.of(2024, 6, 15)
        assertEquals(0, DayClickResolver.calculateHourlyOffset(LocalDateTime.of(2024, 6, 15, 0, 0), today))
        assertEquals(0, DayClickResolver.calculateHourlyOffset(LocalDateTime.of(2024, 6, 15, 14, 0), today))
        assertEquals(0, DayClickResolver.calculateHourlyOffset(LocalDateTime.of(2024, 6, 15, 10, 45), today))
    }

    @Test
    fun offset_isPositiveForTomorrow() {
        val now = LocalDateTime.of(2024, 6, 15, 14, 0)
        assertEquals(25, DayClickResolver.calculateHourlyOffset(now, LocalDate.of(2024, 6, 16)))
    }

    @Test
    fun offset_truncatesCurrentTimeToHourBeforeNoonAnchor() {
        val now = LocalDateTime.of(2024, 6, 15, 10, 45)
        assertEquals(28, DayClickResolver.calculateHourlyOffset(now, LocalDate.of(2024, 6, 16)))
    }

    @Test
    fun offset_centersWideViewAroundNoonForFutureDay() {
        val now = LocalDateTime.of(2024, 6, 15, 10, 45)
        val targetDay = LocalDate.of(2024, 6, 16)
        val offset = DayClickResolver.calculateHourlyOffset(now, targetDay)
        val alignedCenter = WeatherTimeUtils.alignToNearestHourHalfUp(now.plusHours(offset.toLong()))
        val window = com.weatherwidget.shared.graph.ZoomStage.WIDE.window()
        val start = alignedCenter.minusHours(window.backHours)
        val end = alignedCenter.plusHours(window.forwardHours)
        assertEquals(targetDay.atTime(3, 0), start)
        assertEquals(targetDay.atTime(21, 0), end)
        assertEquals(targetDay.atTime(12, 0), start.plusHours((window.backHours + window.forwardHours) / 2))
    }

    @Test
    fun offset_isNegativeForPastDays() {
        val now = LocalDateTime.of(2024, 6, 15, 14, 0)
        assertEquals(-23, DayClickResolver.calculateHourlyOffset(now, LocalDate.of(2024, 6, 14)))
        assertEquals(-71, DayClickResolver.calculateHourlyOffset(now, LocalDate.of(2024, 6, 12)))
    }

    @Test
    fun offset_centersWideViewAroundNoonForPastDay() {
        val now = LocalDateTime.of(2024, 6, 15, 14, 0)
        val targetDay = LocalDate.of(2024, 6, 12)
        val offset = DayClickResolver.calculateHourlyOffset(now, targetDay)
        val alignedCenter = WeatherTimeUtils.alignToNearestHourHalfUp(now.plusHours(offset.toLong()))
        val window = com.weatherwidget.shared.graph.ZoomStage.WIDE.window()
        val start = alignedCenter.minusHours(window.backHours)
        val end = alignedCenter.plusHours(window.forwardHours)
        assertEquals(targetDay.atTime(3, 0), start)
        assertEquals(targetDay.atTime(21, 0), end)
        assertEquals(targetDay.atTime(12, 0), start.plusHours((window.backHours + window.forwardHours) / 2))
    }
}
package com.weatherwidget.shared.util

import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DayClickResolverTest {

    @Test
    fun `today precipitation lookahead matches wide graph forward window`() {
        assertEquals(
            ZoomStage.WIDE.window().forwardHours,
            DayClickResolver.TODAY_LOOKAHEAD_HOURS,
        )
    }

    @Test
    fun mainColumnUpper_rainyIconAtFullCertainty_navigatesToTemperature() {
        // The exact input MAIN_COLUMN sends to precipitation. Above the chevrons it must not.
        assertEquals(
            DayClickResolver.DayClickView.TEMPERATURE,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN_UPPER,
                WeatherConditionResolver.IC_RAIN,
                100,
            ),
        )
    }

    @Test
    fun mainColumnUpper_cloudIcon_navigatesToTemperatureNotCloudCover() {
        assertEquals(
            DayClickResolver.DayClickView.TEMPERATURE,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN_UPPER,
                WeatherConditionResolver.IC_CLOUDY,
                0,
            ),
        )
    }

    @Test
    fun mainColumnUpper_noIcon_navigatesToTemperature() {
        assertEquals(
            DayClickResolver.DayClickView.TEMPERATURE,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN_UPPER,
                iconName = null,
                precipProbability = null,
            ),
        )
    }

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

    @Test
    fun `routingPrecipProbability today with rain at now plus 3h routes to precipitation`() {
        val now = LocalDateTime.of(2026, 7, 10, 6, 0)
        val today = now.toLocalDate()
        val zone = java.time.ZoneId.systemDefault()
        fun toEpoch(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        val hourly = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(3)),
                temperature = 65f,
                condition = "Rain",
                precipProbability = 50,
                source = "NWS",
            ),
        )

        val result = DayClickResolver.routingPrecipProbability(
            targetDay = today,
            now = now,
            hourly = hourly,
            displaySourceId = "NWS",
            fallbackSourceId = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
            dailyProbability = 50,
        )

        assertEquals(DayClickResolver.PrecipGateSource.ROLLING_6H, result.gateSource)
        assertEquals(50, result.probability)
        assertEquals(
            DayClickResolver.DayClickView.PRECIPITATION,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                WeatherConditionResolver.IC_RAIN,
                result.probability,
            ),
        )
    }

    @Test
    fun `routingPrecipProbability today with rain at now plus 8h routes to temperature`() {
        // Rain is outside the 6-hour window [now, now+6h). Even though the daily probability is 50%,
        // the 6h lookahead is 0%, which routes the tap to TEMPERATURE (avoiding opening a blank precip graph).
        val now = LocalDateTime.of(2026, 7, 10, 6, 0)
        val today = now.toLocalDate()
        val zone = java.time.ZoneId.systemDefault()
        fun toEpoch(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        val hourly = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now),
                temperature = 65f,
                condition = "Clear",
                precipProbability = 0,
                source = "NWS",
            ),
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(6)),
                temperature = 65f,
                condition = "Clear",
                precipProbability = 0,
                source = "NWS",
            ),
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(8)),
                temperature = 65f,
                condition = "Rain",
                precipProbability = 50,
                source = "NWS",
            ),
        )

        val result = DayClickResolver.routingPrecipProbability(
            targetDay = today,
            now = now,
            hourly = hourly,
            displaySourceId = "NWS",
            fallbackSourceId = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
            dailyProbability = 50,
        )

        assertEquals(DayClickResolver.PrecipGateSource.ROLLING_6H, result.gateSource)
        assertEquals(0, result.probability)
        assertEquals(
            DayClickResolver.DayClickView.TEMPERATURE,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                WeatherConditionResolver.IC_RAIN,
                result.probability,
            ),
        )
    }

    @Test
    fun `routingPrecipProbability today threshold boundary 15 vs 16`() {
        val now = LocalDateTime.of(2026, 7, 10, 6, 0)
        val today = now.toLocalDate()
        val zone = java.time.ZoneId.systemDefault()
        fun toEpoch(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        val hourly15 = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(2)),
                temperature = 65f,
                condition = "Rain",
                precipProbability = 15,
                source = "NWS",
            ),
        )
        val result15 = DayClickResolver.routingPrecipProbability(
            targetDay = today,
            now = now,
            hourly = hourly15,
            displaySourceId = "NWS",
            fallbackSourceId = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
            dailyProbability = 15,
        )
        assertEquals(15, result15.probability)
        assertEquals(
            DayClickResolver.DayClickView.TEMPERATURE,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                WeatherConditionResolver.IC_RAIN,
                result15.probability,
            ),
        )

        val hourly16 = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(2)),
                temperature = 65f,
                condition = "Rain",
                precipProbability = 16,
                source = "NWS",
            ),
        )
        val result16 = DayClickResolver.routingPrecipProbability(
            targetDay = today,
            now = now,
            hourly = hourly16,
            displaySourceId = "NWS",
            fallbackSourceId = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
            dailyProbability = 16,
        )
        assertEquals(16, result16.probability)
        assertEquals(
            DayClickResolver.DayClickView.PRECIPITATION,
            DayClickResolver.resolveView(
                DayClickResolver.DayTapZone.MAIN_COLUMN,
                WeatherConditionResolver.IC_RAIN,
                result16.probability,
            ),
        )
    }

    @Test
    fun `routingPrecipProbability targetDay not today ignores hourly and uses dailyProbability`() {
        val now = LocalDateTime.of(2026, 7, 10, 6, 0)
        val tomorrow = now.toLocalDate().plusDays(1)
        val zone = java.time.ZoneId.systemDefault()
        fun toEpoch(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        // Hourly has 0% in next 6 hours, but tomorrow has 40% daily
        val hourly = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(2)),
                temperature = 65f,
                condition = "Clear",
                precipProbability = 0,
                source = "NWS",
            ),
        )

        val result = DayClickResolver.routingPrecipProbability(
            targetDay = tomorrow,
            now = now,
            hourly = hourly,
            displaySourceId = "NWS",
            fallbackSourceId = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
            dailyProbability = 40,
        )

        assertEquals(DayClickResolver.PrecipGateSource.DAILY, result.gateSource)
        assertEquals(40, result.probability)
        assertEquals("40(daily)", result.auditText())
    }

    @Test
    fun `routingPrecipProbability empty hourly falls back to dailyProbability`() {
        val now = LocalDateTime.of(2026, 7, 10, 6, 0)
        val today = now.toLocalDate()

        val result = DayClickResolver.routingPrecipProbability(
            targetDay = today,
            now = now,
            hourly = emptyList(),
            displaySourceId = "NWS",
            fallbackSourceId = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
            dailyProbability = 45,
        )

        assertEquals(DayClickResolver.PrecipGateSource.DAILY, result.gateSource)
        assertEquals(45, result.probability)
        assertEquals("45(daily)", result.auditText())
    }

    @Test
    fun `routingPrecipProbability falls back via fallbackSourceId then daily`() {
        val now = LocalDateTime.of(2026, 7, 10, 6, 0)
        val today = now.toLocalDate()
        val zone = java.time.ZoneId.systemDefault()
        fun toEpoch(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        // Only fallback source has hourly data
        val hourly = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(2)),
                temperature = 65f,
                condition = "Rain",
                precipProbability = 30,
                source = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
            ),
        )

        val result = DayClickResolver.routingPrecipProbability(
            targetDay = today,
            now = now,
            hourly = hourly,
            displaySourceId = "NWS",
            fallbackSourceId = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
            dailyProbability = 10,
        )

        assertEquals(DayClickResolver.PrecipGateSource.ROLLING_6H, result.gateSource)
        assertEquals(30, result.probability)
        assertEquals("30(rolling6h)", result.auditText())

        // If only an unrelated source is present, falls back to daily
        val unrelatedHourly = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(2)),
                temperature = 65f,
                condition = "Rain",
                precipProbability = 30,
                source = "OTHER_SOURCE",
            ),
        )
        val fallbackDailyResult = DayClickResolver.routingPrecipProbability(
            targetDay = today,
            now = now,
            hourly = unrelatedHourly,
            displaySourceId = "NWS",
            fallbackSourceId = com.weatherwidget.data.model.WeatherSource.GENERIC_GAP.id,
            dailyProbability = 10,
        )
        assertEquals(DayClickResolver.PrecipGateSource.DAILY, fallbackDailyResult.gateSource)
        assertEquals(10, fallbackDailyResult.probability)
    }
}

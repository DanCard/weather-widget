package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DayClickResolver
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.widget.WidgetActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyViewHandlerIntentContractTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun pastDayClick_buildsTemperatureGraphIntentContract() {
        val now = LocalDateTime.of(2030, 6, 15, 9, 0)
        val targetDate = LocalDate.of(2030, 6, 14)

        val intent =
            DailyClickHandlerFactory.buildDayClickIntent(
                context = context,
                appWidgetId = TEST_WIDGET_ID,
                dayIndex = 1,
                date = targetDate,
                iconRes = R.drawable.ic_weather_rain,
                lat = LAT,
                lon = LON,
                displaySource = WeatherSource.NWS,
                now = now,
            )

        assertEquals(WidgetActions.ACTION_DAY_CLICK, intent.action)
        assertEquals(TEST_WIDGET_ID, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertEquals(targetDate.toString(), intent.getStringExtra("date"))
        assertTrue(intent.getBooleanExtra("isHistory", false))
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals(1, intent.getIntExtra("index", -1))
        assertEquals(LAT, intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LAT, 0.0), 0.00001)
        assertEquals(LON, intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LON, 0.0), 0.00001)
        assertEquals(WeatherSource.NWS.displayName, intent.getStringExtra(ForecastHistoryActivity.EXTRA_SOURCE))
        // History days always force TEMPERATURE — the icon is irrelevant since we're looking at past data
        assertEquals("TEMPERATURE", intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
    }

    @Test
    fun futureRainyDayClick_buildsPrecipitationIntentContract() {
        val now = LocalDateTime.of(2030, 6, 15, 9, 0)
        val targetDate = LocalDate.of(2030, 6, 16)

        val intent =
            DailyClickHandlerFactory.buildDayClickIntent(
                context = context,
                appWidgetId = TEST_WIDGET_ID,
                dayIndex = 2,
                date = targetDate,
                iconRes = R.drawable.ic_weather_rain,
                lat = LAT,
                lon = LON,
                displaySource = WeatherSource.NWS,
                now = now,
                precipProbability = 16,
            )

        val expectedOffset =
            DayClickHelper.calculatePrecipitationOffset(
                now = now,
                targetDay = targetDate,
            )

        assertEquals(WidgetActions.ACTION_DAY_CLICK, intent.action)
        assertFalse(intent.getBooleanExtra("isHistory", true))
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("PRECIPITATION", intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
        assertEquals(expectedOffset, intent.getIntExtra(WidgetActions.EXTRA_HOURLY_OFFSET, Int.MIN_VALUE))
        assertEquals(LAT, intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LAT, 0.0), 0.00001)
        assertEquals(LON, intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LON, 0.0), 0.00001)
        assertEquals(WeatherSource.NWS.displayName, intent.getStringExtra(ForecastHistoryActivity.EXTRA_SOURCE))
    }

    @Test
    fun todayRainyIconStillBuildsPrecipitationIntentContract() {
        val now = LocalDateTime.of(2030, 6, 15, 9, 0)
        val today = LocalDate.of(2030, 6, 15)

        val intent =
            DailyClickHandlerFactory.buildDayClickIntent(
                context = context,
                appWidgetId = TEST_WIDGET_ID,
                dayIndex = 2,
                date = today,
                iconRes = R.drawable.ic_weather_rain,
                lat = LAT,
                lon = LON,
                displaySource = WeatherSource.NWS,
                now = now,
                precipProbability = 16,
            )

        assertFalse(intent.getBooleanExtra("isHistory", true))
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("PRECIPITATION", intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
        assertEquals(0, intent.getIntExtra(WidgetActions.EXTRA_HOURLY_OFFSET, Int.MIN_VALUE))
    }

    @Test
    fun todayCloudyBottomTap_buildsCloudCoverIntentContract() {
        val now = LocalDateTime.of(2030, 6, 15, 9, 0)
        val today = LocalDate.of(2030, 6, 15)

        val intent =
            DailyClickHandlerFactory.buildDayClickIntent(
                context = context,
                appWidgetId = TEST_WIDGET_ID,
                dayIndex = 2,
                date = today,
                iconRes = R.drawable.ic_weather_cloudy,
                lat = LAT,
                lon = LON,
                displaySource = WeatherSource.NWS,
                now = now,
                targetModeOverride = com.weatherwidget.widget.ViewMode.CLOUD_COVER,
            )

        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("CLOUD_COVER", intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
        assertEquals(0, intent.getIntExtra(WidgetActions.EXTRA_HOURLY_OFFSET, Int.MIN_VALUE))
    }

    @Test
    fun futureClearBottomTap_buildsTemperatureIntentContract() {
        val now = LocalDateTime.of(2030, 6, 15, 9, 0)
        val targetDate = LocalDate.of(2030, 6, 17)

        val intent =
            DailyClickHandlerFactory.buildDayClickIntent(
                context = context,
                appWidgetId = TEST_WIDGET_ID,
                dayIndex = 3,
                date = targetDate,
                iconRes = R.drawable.ic_weather_clear,
                lat = LAT,
                lon = LON,
                displaySource = WeatherSource.NWS,
                now = now,
                targetModeOverride = com.weatherwidget.widget.ViewMode.TEMPERATURE,
            )

        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("TEMPERATURE", intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
    }

    @Test
    fun todayTap_withRainInside6HourWindow_routesToPrecipitation() {
        val now = LocalDateTime.of(2030, 6, 15, 6, 0)
        val today = LocalDate.of(2030, 6, 15)
        val zone = java.time.ZoneId.systemDefault()
        fun toEpoch(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        val hourly = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(3)),
                temperature = 68f,
                condition = "Rain",
                source = "NWS",
                precipProbability = 40,
            ),
        )

        val routingPrecip = DayClickResolver.routingPrecipProbability(
            targetDay = today,
            now = now,
            hourly = hourly,
            displaySourceId = WeatherSource.NWS.id,
            fallbackSourceId = WeatherSource.GENERIC_GAP.id,
            dailyProbability = 40,
        )

        val intent = DailyClickHandlerFactory.buildDayClickIntent(
            context = context,
            appWidgetId = TEST_WIDGET_ID,
            dayIndex = 2,
            date = today,
            iconRes = R.drawable.ic_weather_rain,
            lat = LAT,
            lon = LON,
            displaySource = WeatherSource.NWS,
            now = now,
            routingPrecip = routingPrecip,
        )

        assertEquals("PRECIPITATION", intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
        assertEquals("40(rolling6h)", intent.getStringExtra(WidgetActions.EXTRA_PRECIP_GATE))
    }

    @Test
    fun todayTap_withRainOutside6HourWindow_routesToTemperature() {
        // Daily probability is 40% (and icon is rain), but the rain is at now+8h (outside the 6h lookahead).
        // Tap must route to TEMPERATURE instead of opening a dry precipitation graph.
        val now = LocalDateTime.of(2030, 6, 15, 6, 0)
        val today = LocalDate.of(2030, 6, 15)
        val zone = java.time.ZoneId.systemDefault()
        fun toEpoch(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

        val hourly = listOf(
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now),
                temperature = 58f,
                condition = "Clear",
                source = "NWS",
                precipProbability = 0,
            ),
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(6)),
                temperature = 72f,
                condition = "Clear",
                source = "NWS",
                precipProbability = 0,
            ),
            com.weatherwidget.data.model.HourlyForecast(
                dateTime = toEpoch(now.plusHours(8)),
                temperature = 68f,
                condition = "Rain",
                source = "NWS",
                precipProbability = 40,
            ),
        )

        val routingPrecip = DayClickResolver.routingPrecipProbability(
            targetDay = today,
            now = now,
            hourly = hourly,
            displaySourceId = WeatherSource.NWS.id,
            fallbackSourceId = WeatherSource.GENERIC_GAP.id,
            dailyProbability = 40,
        )

        val intent = DailyClickHandlerFactory.buildDayClickIntent(
            context = context,
            appWidgetId = TEST_WIDGET_ID,
            dayIndex = 2,
            date = today,
            iconRes = R.drawable.ic_weather_rain,
            lat = LAT,
            lon = LON,
            displaySource = WeatherSource.NWS,
            now = now,
            routingPrecip = routingPrecip,
        )

        assertEquals("TEMPERATURE", intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
        assertEquals("0(rolling6h)", intent.getStringExtra(WidgetActions.EXTRA_PRECIP_GATE))
    }

    companion object {
        private const val TEST_WIDGET_ID = 123
        private const val LAT = 37.7749
        private const val LON = -122.4194
    }
}

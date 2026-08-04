package com.weatherwidget.ui

import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class WeatherObservationsSupportTest {
    @Test
    fun `widget exit refresh requires a valid widget and changed content`() {
        assertTrue(
            WeatherObservationsActivity.WeatherObservationsSupport.shouldRefreshWidgetOnExit(
                appWidgetId = 345,
                widgetContentChanged = true,
            ),
        )
        assertFalse(
            WeatherObservationsActivity.WeatherObservationsSupport.shouldRefreshWidgetOnExit(
                appWidgetId = 345,
                widgetContentChanged = false,
            ),
        )
        assertFalse(
            WeatherObservationsActivity.WeatherObservationsSupport.shouldRefreshWidgetOnExit(
                appWidgetId = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID,
                widgetContentChanged = true,
            ),
        )
    }

    @Test
    fun `matchesObservationSource excludes silurian rows from NWS`() {
        assertFalse(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesObservationSource(
                stationId = "SILURIAN_MAIN",
                source = WeatherSource.NWS,
            ),
        )
        assertFalse(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesObservationSource(
                stationId = "TOMORROW_IO_MAIN",
                source = WeatherSource.NWS,
            ),
        )
        assertTrue(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesObservationSource(
                stationId = "AW020",
                source = WeatherSource.NWS,
            ),
        )
    }

    @Test
    fun `matchesObservationSource excludes NWS_BLEND from NWS`() {
        assertFalse(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesObservationSource(
                stationId = "NWS_BLEND",
                source = WeatherSource.NWS,
            ),
        )
    }

    @Test
    fun `matchesObservationSource excludes NWS history backfill from NWS`() {
        // The NWS->Open-Meteo fallback mints "NWS_MAIN" backfill rows; they are not station obs.
        assertFalse(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesObservationSource(
                stationId = "NWS_MAIN",
                source = WeatherSource.NWS,
            ),
        )
    }

    @Test
    fun `matchesObservationSource matches source prefixes for non-NWS sources`() {
        assertTrue(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesObservationSource(
                stationId = "SILURIAN_2",
                source = WeatherSource.SILURIAN,
            ),
        )
        assertFalse(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesObservationSource(
                stationId = "WEATHER_API_MAIN",
                source = WeatherSource.SILURIAN,
            ),
        )
    }

    @Test
    fun `matchesFetchLog uses current log tags`() {
        val startLog = AppLogEntity(tag = "CURR_FETCH_START", message = "reason=opportunistic_job targets=NWS,SILURIAN,WEATHER_API")
        val doneLog = AppLogEntity(tag = "CURR_FETCH_DONE", message = "reason=opportunistic_job targets=NWS,SILURIAN,WEATHER_API updated=3")
        val errorLog = AppLogEntity(tag = "CURR_FETCH_ERROR", message = "source=SILURIAN error=timeout")
        val enqueuedLog = AppLogEntity(tag = "CURR_FETCH_WORK_ENQUEUED", message = "type=charging_loop reason=charging_loop")
        val sourceResultLog = AppLogEntity(tag = "CURR_FETCH_SOURCE_RESULT", message = "reason=charging_loop source=NWS success=true temp=70.0")
        val insertLog = AppLogEntity(tag = "OBS_CURRENT_INSERT", message = "source=NWS station=KNUQ temp=68.0")
        val hourlyRequestLog = AppLogEntity(tag = "OBS_HOURLY_BACKFILL_REQ", message = "widget=4 source=NWS reason=missing_actuals")

        assertTrue(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesFetchLog(
                log = startLog,
                source = WeatherSource.NWS,
            ),
        )
        assertTrue(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesFetchLog(
                log = doneLog,
                source = WeatherSource.NWS,
            ),
        )
        assertFalse(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesFetchLog(
                log = errorLog,
                source = WeatherSource.NWS,
            ),
        )
        assertTrue(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesFetchLog(
                log = errorLog,
                source = WeatherSource.SILURIAN,
            ),
        )
        assertTrue(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesFetchLog(
                log = enqueuedLog,
                source = WeatherSource.NWS,
            ),
        )
        assertTrue(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesFetchLog(
                log = sourceResultLog,
                source = WeatherSource.NWS,
            ),
        )
        assertFalse(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesFetchLog(
                log = sourceResultLog,
                source = WeatherSource.SILURIAN,
            ),
        )
        assertTrue(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesFetchLog(
                log = insertLog,
                source = WeatherSource.NWS,
            ),
        )
        assertTrue(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesFetchLog(
                log = hourlyRequestLog,
                source = WeatherSource.NWS,
            ),
        )
    }

    @Test
    fun `formatFetchLog strips redundant source prefix for error logs`() {
        val errorLog = AppLogEntity(tag = "CURR_FETCH_ERROR", message = "source=NWS error=network")

        assertEquals(
            "error error=network",
            WeatherObservationsActivity.WeatherObservationsSupport.formatFetchLog(errorLog, WeatherSource.NWS),
        )
    }
}

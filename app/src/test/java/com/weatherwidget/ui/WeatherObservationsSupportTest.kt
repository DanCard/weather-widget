package com.weatherwidget.ui

import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherObservationsSupportTest {
    @Test
    fun `matchesObservationSource excludes silurian rows from NWS`() {
        assertFalse(
            WeatherObservationsActivity.WeatherObservationsSupport.matchesObservationSource(
                stationId = "SILURIAN_MAIN",
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
        val startLog = AppLogEntity(tag = "CURR_FETCH_START", message = "reason=opportunistic_job targets=NWS, SILURIAN, WEATHER_API")
        val doneLog = AppLogEntity(tag = "CURR_FETCH_DONE", message = "reason=opportunistic_job updated=3")
        val errorLog = AppLogEntity(tag = "CURR_FETCH_ERROR", message = "source=SILURIAN error=timeout")

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

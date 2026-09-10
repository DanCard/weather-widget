package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TomorrowIoActualsTest {

    @Test
    fun `only five minute history is an allowed tomorrow actuals product`() {
        assertTrue(TomorrowIoActuals.isAllowedStation(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID))
        assertFalse(TomorrowIoActuals.isAllowedStation(TomorrowIoActuals.REALTIME_STATION_ID))
        assertFalse(TomorrowIoActuals.isAllowedStation(TomorrowIoActuals.RECENT_HISTORY_STATION_ID))
    }

    @Test
    fun `isSyntheticBackfillStation recognises canonical tomorrow actuals ids`() {
        val matcher = com.weatherwidget.shared.observations.ObservationSourceMatcher
        val sourceId = WeatherSource.TOMORROW_IO.id

        assertTrue(matcher.isSyntheticBackfillStation(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, sourceId))
        assertTrue(matcher.isSyntheticBackfillStation(TomorrowIoActuals.MERGED_SERIES_STATION_ID, sourceId))
        assertTrue(matcher.isSyntheticBackfillStation("TOMORROW_IO_MAIN", sourceId))
        assertFalse(matcher.isSyntheticBackfillStation(TomorrowIoActuals.REALTIME_STATION_ID, sourceId))
        assertFalse(matcher.isSyntheticBackfillStation(TomorrowIoActuals.RECENT_HISTORY_STATION_ID, sourceId))
        assertFalse(matcher.isSyntheticBackfillStation("KNUQ", sourceId))
    }
}

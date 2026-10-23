package com.weatherwidget.shared.actuals

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** [TomorrowIoActuals.replacements] and [TomorrowIoActuals.fetchLine]: the one `TMRW_5M_FETCH` line. */
@Category(ShortDuration::class)
class TomorrowIoFiveMinuteLogTest {
    private val id = TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID
    private fun key(t: Long, lat: Double = 37.42) = TomorrowIoActuals.ReadingKey(id, t, "TOMORROW_IO", lat, -122.08)

    @Test
    fun onlyTheSameReadingAtTheSameSiteIsAReplacement() {
        val incoming = listOf(key(1), key(2), key(3))
        val stored = listOf(key(1), key(2, lat = 37.45), key(9))
        assertEquals(1, TomorrowIoActuals.replacements(incoming, stored))
    }

    @Test
    fun theLineKeepsItsFields() {
        assertEquals(
            "windowHours=23 rows=3 earliest=1 latest=3 replacements=1",
            TomorrowIoActuals.fetchLine(23, 3, 1L, 3L, 1),
        )
    }
}

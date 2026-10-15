package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.shared.util.PreferredSourceHome.HomeAction
import com.weatherwidget.util.NavigationUtils
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class PreferredSourceHomeTest {

    private val order = listOf(
        WeatherSource.NWS.id,
        WeatherSource.OPEN_METEO.id,
        WeatherSource.TOMORROW_IO.id,
    )

    @Test
    fun `preferred source is the first visible one`() {
        assertEquals(WeatherSource.NWS.id, PreferredSourceHome.preferredSourceId(order))
    }

    @Test
    fun `no order stored means no preferred source`() {
        assertNull(PreferredSourceHome.preferredSourceId(emptyList()))
    }

    @Test
    fun `button is offered when a non-preferred source is displayed`() {
        assertTrue(PreferredSourceHome.shouldShowHomeButton(WeatherSource.OPEN_METEO.id, order))
        assertTrue(PreferredSourceHome.shouldShowHomeButton(WeatherSource.TOMORROW_IO.id, order))
    }

    @Test
    fun `button is withheld while the preferred source is displayed`() {
        assertFalse(PreferredSourceHome.shouldShowHomeButton(WeatherSource.NWS.id, order))
    }

    @Test
    fun `single visible source can never be off home`() {
        assertFalse(
            PreferredSourceHome.shouldShowHomeButton(
                WeatherSource.NWS.id,
                listOf(WeatherSource.NWS.id),
            ),
        )
    }

    @Test
    fun `a source outside the visible order offers no destination`() {
        // Hidden in Settings while still selected: the header must not promise a trip back to a
        // list this source is not on — the ordering code will re-home it on the next write.
        assertFalse(PreferredSourceHome.shouldShowHomeButton(WeatherSource.SILURIAN.id, order))
        assertFalse(PreferredSourceHome.shouldShowHomeButton(WeatherSource.NWS.id, emptyList()))
        assertFalse(PreferredSourceHome.shouldShowHomeButton(null, order))
    }

    @Test
    fun `non-preferred source resets the source whether or not today is in view`() {
        for (todayInView in listOf(true, false)) {
            assertEquals(
                HomeAction.RESET_SOURCE,
                PreferredSourceHome.homeAction(WeatherSource.OPEN_METEO.id, order, todayInView),
            )
        }
    }

    @Test
    fun `preferred source with today off screen recentres`() {
        assertEquals(
            HomeAction.RECENTER_TODAY,
            PreferredSourceHome.homeAction(WeatherSource.NWS.id, order, todayInView = false),
        )
    }

    @Test
    fun `preferred source with today on screen offers no button`() {
        assertNull(PreferredSourceHome.homeAction(WeatherSource.NWS.id, order, todayInView = true))
    }

    @Test
    fun `no order or unknown source offers no button`() {
        assertNull(PreferredSourceHome.homeAction(WeatherSource.NWS.id, emptyList(), todayInView = false))
        assertNull(PreferredSourceHome.homeAction("not_a_source", order, todayInView = false))
        assertNull(PreferredSourceHome.homeAction(null, order, todayInView = false))
    }

    @Test
    fun `home offset shows yesterday then today at every width`() {
        val today = LocalDate.of(2026, 10, 15)
        for (cols in listOf(3, 4, 5, 6, 8, 9, 10, 12, 16)) {
            val offset = PreferredSourceHome.homeDateOffset(cols)
            val (from, to) = NavigationUtils.getVisibleDateRange(
                today, offset, cols, NavigationUtils.shouldSkipYesterday(cols),
            )
            assertEquals("cols=$cols leftmost", today.minusDays(1), from)
            assertEquals("cols=$cols width", cols.toLong() - 1, java.time.temporal.ChronoUnit.DAYS.between(from, to))
        }
    }

    @Test
    fun `one and two columns go to offset zero - today first`() {
        val today = LocalDate.of(2026, 10, 15)
        for (cols in listOf(1, 2)) {
            assertEquals(0, PreferredSourceHome.homeDateOffset(cols))
            val (from, _) = NavigationUtils.getVisibleDateRange(
                today, 0, cols, NavigationUtils.shouldSkipYesterday(cols),
            )
            assertEquals("cols=$cols", today, from)
        }
    }
}

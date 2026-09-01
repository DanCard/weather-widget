package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
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
}

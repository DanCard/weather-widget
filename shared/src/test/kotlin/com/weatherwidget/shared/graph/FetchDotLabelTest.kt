package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class FetchDotLabelTest {

    @Test
    fun `minutes under an hour format as Nm`() {
        assertEquals("17m", FetchDotLabel.formatAgeLabel(17, spanHours = 4))
        assertEquals("0m", FetchDotLabel.formatAgeLabel(0, spanHours = 4))
    }

    @Test
    fun `whole hours omit the minutes part`() {
        assertEquals("1h", FetchDotLabel.formatAgeLabel(60, spanHours = 4))
        assertEquals("2h", FetchDotLabel.formatAgeLabel(120, spanHours = 4))
    }

    @Test
    fun `hours with remainder show both parts`() {
        assertEquals("1h 5m", FetchDotLabel.formatAgeLabel(65, spanHours = 4))
        assertEquals("3h 1m", FetchDotLabel.formatAgeLabel(181, spanHours = 4))
    }

    @Test
    fun `wide window past the max span suppresses the label`() {
        assertNull(FetchDotLabel.formatAgeLabel(17, spanHours = 13))
        assertEquals("17m", FetchDotLabel.formatAgeLabel(17, spanHours = 12)) // boundary is inclusive
    }

    @Test
    fun `negative age suppresses the label`() {
        assertNull(FetchDotLabel.formatAgeLabel(-1, spanHours = 4))
    }
}

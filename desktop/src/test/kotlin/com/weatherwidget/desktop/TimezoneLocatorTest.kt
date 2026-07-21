package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TimezoneLocatorTest {
    @Test
    fun `parseIso6709Coordinates handles degree minute coordinates`() {
        val coords = TimezoneLocator.parseIso6709Coordinates("+4042-07400")

        assertEquals(40.7, coords?.first ?: 0.0, 0.000001)
        assertEquals(-74.0, coords?.second ?: 0.0, 0.000001)
    }

    @Test
    fun `parseIso6709Coordinates handles degree minute second coordinates`() {
        val coords = TimezoneLocator.parseIso6709Coordinates("+404251-0740023")

        assertEquals(40.714167, coords?.first ?: 0.0, 0.000001)
        assertEquals(-74.006389, coords?.second ?: 0.0, 0.000001)
    }

    @Test
    fun `parseZoneTabLine returns location for tab row`() {
        val location = TimezoneLocator.parseZoneTabLine("US\t+404251-0740023\tAmerica/New_York\tEastern")

        assertEquals("America/New_York", location?.zoneId)
        assertEquals(40.714167, location?.lat ?: 0.0, 0.000001)
        assertEquals(-74.006389, location?.lon ?: 0.0, 0.000001)
    }

    @Test
    fun `parseZoneTabLine ignores comments`() {
        assertNull(TimezoneLocator.parseZoneTabLine("# comment"))
    }
}

package com.weatherwidget.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.weatherwidget.test.category.ShortDuration
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The Settings location card names what the user picked. It used to replace the picked label with
 * the reverse-geocoded city, so a searched "860 Avery Drive, Mountain View, CA 94043" read
 * "Mountain View, California" (2026-09-30).
 */
@Category(ShortDuration::class)
class SettingsLocationLineTest {
    @Test
    fun `a picked name is shown as picked, with coordinates`() {
        assertEquals(
            "860 Avery Drive, Mountain View, CA 94043 (37.4166, -122.0889)",
            settingsLocationLine(
                "860 Avery Drive, Mountain View, CA 94043", 37.4166014, -122.0888722,
                friendlyName = "Mountain View, California",
            ),
        )
    }

    @Test
    fun `only a coordinate-shaped label asks the resolver`() {
        assertFalse(needsFriendlyName("860 Avery Drive, Mountain View, CA 94043"))
        assertTrue(needsFriendlyName("37.4166, -122.0889"))
        assertFalse(needsFriendlyName(""))
    }

    @Test
    fun `a coordinate label uses the resolver name when there is one`() {
        assertEquals(
            "Mountain View, California (37.4166, -122.0889)",
            settingsLocationLine("37.4166, -122.0889", 37.4166, -122.0889, "Mountain View, California"),
        )
        assertEquals(
            "37.4166, -122.0889",
            settingsLocationLine("37.4166, -122.0889", 37.4166, -122.0889, null),
        )
    }

    @Test
    fun `no label means no location`() {
        assertEquals("No location set", settingsLocationLine("", 0.0, 0.0, null))
    }
}

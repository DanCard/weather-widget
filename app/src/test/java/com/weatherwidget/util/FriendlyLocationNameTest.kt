package com.weatherwidget.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class FriendlyLocationNameTest {

    @Test
    fun `coordinate-shaped labels are not friendly names`() {
        assertTrue(FriendlyLocationName.isCoordinateLabel("37.42, -122.08"))
        assertTrue(FriendlyLocationName.isCoordinateLabel("37.4220,-122.0841"))
        // Default-locale formatting can produce decimal commas.
        assertTrue(FriendlyLocationName.isCoordinateLabel("37,42, -122,08"))
    }

    @Test
    fun `real place names are friendly`() {
        assertFalse(FriendlyLocationName.isCoordinateLabel("Mountain View, California"))
        assertFalse(FriendlyLocationName.isCoordinateLabel("東京都"))
    }

    @Test
    fun `nameFromPois returns same-site label`() {
        val pois = "Boulder, Colorado|40.01499|-105.27055;Mountain View, California|37.4220|-122.0841"
        assertEquals(
            "Mountain View, California",
            FriendlyLocationName.nameFromPois(pois, 37.4221, -122.0840),
        )
    }

    @Test
    fun `nameFromPois ignores coordinate-shaped labels`() {
        // Fetch paths without a name record "lat, lon" as the POI label; that is not a name.
        val pois = "37.42, -122.08|37.4220|-122.0841"
        assertNull(FriendlyLocationName.nameFromPois(pois, 37.4220, -122.0841))
    }

    @Test
    fun `nameFromPois ignores far-away sites`() {
        val pois = "Boulder, Colorado|40.01499|-105.27055"
        assertNull(FriendlyLocationName.nameFromPois(pois, 37.4220, -122.0841))
    }

    @Test
    fun `nameFromPois handles null blank and malformed input`() {
        assertNull(FriendlyLocationName.nameFromPois(null, 37.4220, -122.0841))
        assertNull(FriendlyLocationName.nameFromPois("", 37.4220, -122.0841))
        assertNull(FriendlyLocationName.nameFromPois("garbage-without-pipes", 37.4220, -122.0841))
        assertNull(FriendlyLocationName.nameFromPois("name|not-a-lat|not-a-lon", 37.4220, -122.0841))
    }
}

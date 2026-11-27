package com.weatherwidget.shared.util

import com.weatherwidget.data.model.RecentLocation
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class PreviousLocationToggleTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private val home = RecentLocation(37.3861, -122.0839, "860 Avery Dr, Mountain View, CA 94043", timestamp = now - 1 * day)
    private val sf = RecentLocation(37.7749, -122.4194, "San Francisco, CA", timestamp = now)
    private val atHome = CurrentLocationSetting(home.lat, home.lon, followDevice = false)
    private val inSf = CurrentLocationSetting(sf.lat, sf.lon, followDevice = false)

    @Test
    fun `no recents hides the button`() {
        assertNull(PreviousLocationToggle.target(emptyList(), atHome, null, now))
    }

    @Test
    fun `only entry is the current location hides the button`() {
        assertNull(PreviousLocationToggle.target(listOf(home), atHome, null, now))
    }

    @Test
    fun `fresh previous location is the target`() {
        val target = PreviousLocationToggle.target(listOf(sf, home), inSf, null, now)
        println("target=$target")
        assertEquals(home, target)
    }

    @Test
    fun `target skips the current location even when it is not first`() {
        // Current = home, but SF was picked last: the target is SF, not home.
        assertEquals(sf, PreviousLocationToggle.target(listOf(sf, home), atHome, null, now))
    }

    @Test
    fun `stale target with toggle never used is hidden`() {
        val staleHome = home.copy(timestamp = now - 15 * day)
        assertNull(PreviousLocationToggle.target(listOf(sf, staleHome), inSf, null, now))
    }

    @Test
    fun `stale target stays visible while the toggle was used within two weeks`() {
        val staleHome = home.copy(timestamp = now - 15 * day)
        assertEquals(staleHome, PreviousLocationToggle.target(listOf(sf, staleHome), inSf, now - 13 * day, now))
    }

    @Test
    fun `stale target and stale toggle use is hidden`() {
        val staleHome = home.copy(timestamp = now - 15 * day)
        assertNull(PreviousLocationToggle.target(listOf(sf, staleHome), inSf, now - 15 * day, now))
    }

    @Test
    fun `exactly fourteen days is still shown`() {
        val edgeHome = home.copy(timestamp = now - PreviousLocationToggle.VISIBILITY_WINDOW_MS)
        assertEquals(edgeHome, PreviousLocationToggle.target(listOf(sf, edgeHome), inSf, null, now))
    }

    @Test
    fun `six weeks at home then a picker move to SF still offers home`() {
        // Home picked 6 weeks ago and never toggled. Without the leaving stamp its timestamp would be
        // 42 days old and the button would be hidden the first day in SF.
        val oldHome = home.copy(timestamp = now - 42 * day)
        val recents = RecentLocationsHelper.addRecent(listOf(oldHome), sf, leaving = atHome, nowMs = now)
        println("recents=$recents")
        assertEquals(now, recents[1].timestamp)
        val target = PreviousLocationToggle.target(recents, inSf, null, now)
        assertEquals(oldHome.copy(timestamp = now), target)
    }

    @Test
    fun `recentsAfterSwitch puts target first and stamps the place left`() {
        val oldSf = sf.copy(timestamp = now - 3 * day)
        val other = RecentLocation(40.7128, -74.0060, "New York, NY", timestamp = now - 2 * day)
        val recents = listOf(home, other, oldSf)
        val result = PreviousLocationToggle.recentsAfterSwitch(recents, atHome, oldSf, now)
        println("result=$result")
        assertEquals(listOf("San Francisco, CA", home.label, "New York, NY"), result.map { it.label })
        assertEquals(now, result[0].timestamp)
        assertEquals(now, result[1].timestamp) // home, the place left
        assertEquals(now - 2 * day, result[2].timestamp) // untouched
    }

    @Test
    fun `follow-device entry is the target from a pinned place`() {
        val device = RecentLocation(37.39, -122.08, "Mountain View, California", timestamp = now - 1 * day, followDevice = true)
        val target = PreviousLocationToggle.target(listOf(sf, device), inSf, null, now)
        assertTrue(target!!.followDevice)
    }

    @Test
    fun `follow-device entry is current while following the device`() {
        val device = RecentLocation(37.77, -122.42, "San Francisco, California", timestamp = now, followDevice = true)
        val followingInSf = CurrentLocationSetting(sf.lat, sf.lon, followDevice = true)
        // The fixed SF entry is at the current coordinates too, so home is the target.
        assertEquals(home, PreviousLocationToggle.target(listOf(device, sf, home), followingInSf, null, now))
    }

    @Test
    fun `desktop never offers a follow-device entry`() {
        val device = RecentLocation(37.39, -122.08, "Mountain View, California", timestamp = now, followDevice = true)
        assertEquals(
            home,
            PreviousLocationToggle.target(listOf(sf, device, home), inSf, null, now, supportsFollowDevice = false),
        )
    }

    @Test
    fun `only one follow-device entry is kept wherever the fix was`() {
        val homeFix = RecentLocation(37.39, -122.08, "Mountain View, California", timestamp = now - day, followDevice = true)
        val sfFix = RecentLocation(37.77, -122.42, "San Francisco, California", timestamp = now, followDevice = true)
        val result = RecentLocationsHelper.addRecent(listOf(homeFix, home), sfFix)
        assertEquals(listOf(sfFix, home), result)
    }

    @Test
    fun `follow-device and pinned entries at one place do not replace each other`() {
        val homeFix = RecentLocation(home.lat, home.lon, home.label, timestamp = now, followDevice = true)
        val result = RecentLocationsHelper.addRecent(listOf(home), homeFix)
        assertEquals(listOf(homeFix, home), result)
    }

    @Test
    fun `picker dropdown leaves out follow-device entries`() {
        val homeFix = RecentLocation(37.39, -122.08, "Mountain View, California", followDevice = true)
        assertEquals(listOf(home), RecentLocationsHelper.filterMatching(listOf(homeFix, home), ""))
        assertEquals(listOf(home), RecentLocationsHelper.filterMatching(listOf(homeFix, home), "mountain"))
    }

    @Test
    fun `old stored entries decode as pinned places`() {
        val raw = """[{"lat":37.77,"lon":-122.42,"label":"San Francisco, CA","timestamp":1}]"""
        val decoded = RecentLocationsHelper.decodeFromJson(raw)
        assertEquals(false, decoded.single().followDevice)
        val roundTrip = RecentLocationsHelper.decodeFromJson(
            RecentLocationsHelper.encodeToJson(listOf(sf.copy(followDevice = true))),
        )
        assertTrue(roundTrip.single().followDevice)
    }

    @Test
    fun `shortLabel drops a leading street segment only`() {
        assertEquals("Mountain View, CA 94043", PreviousLocationToggle.shortLabel("860 Avery Dr, Mountain View, CA 94043"))
        assertEquals("San Francisco, CA", PreviousLocationToggle.shortLabel("San Francisco, CA"))
        assertEquals("Lviv", PreviousLocationToggle.shortLabel("Lviv"))
        // Nominatim display names (desktop picker): bare house number, then the street.
        assertEquals(
            "Mountain View, Santa Clara County, California",
            PreviousLocationToggle.shortLabel("860, Avery Drive, Mountain View, Santa Clara County, California, 94043, United States"),
        )
        assertEquals("Warsaw, Masovian Voivodeship, Poland", PreviousLocationToggle.shortLabel("Warsaw, Masovian Voivodeship, Poland"))
        assertEquals("Scottsdale, AZ 85255", PreviousLocationToggle.shortLabel("7900 E Princess Dr, Scottsdale, AZ 85255"))
    }
}

package com.weatherwidget.shared.util

import com.weatherwidget.data.model.RecentLocation
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class RecentLocationsHelperTest {

    private val kyiv = RecentLocation(lat = 50.4501, lon = 30.5234, label = "Kyiv, Ukraine")
    private val lviv = RecentLocation(lat = 49.8420, lon = 24.0316, label = "Lviv, Ukraine")
    private val odesa = RecentLocation(lat = 46.4825, lon = 30.7233, label = "Odesa, Ukraine")

    @Test
    fun `addRecent prepends new location to empty list`() {
        val result = RecentLocationsHelper.addRecent(emptyList(), kyiv)
        assertEquals(listOf(kyiv), result)
    }

    @Test
    fun `addRecent moves existing location to front`() {
        val initial = listOf(kyiv, lviv)
        val updatedLviv = lviv.copy(timestamp = 9999L)
        val result = RecentLocationsHelper.addRecent(initial, updatedLviv)

        assertEquals(2, result.size)
        assertEquals(updatedLviv, result[0])
        assertEquals(kyiv, result[1])
    }

    @Test
    fun `addRecent deduplicates by proximity`() {
        val initial = listOf(kyiv)
        // Coords within 0.005 deg (~550m)
        val nearbyKyiv = RecentLocation(lat = 50.4505, lon = 30.5238, label = "Kyiv City Center")
        val result = RecentLocationsHelper.addRecent(initial, nearbyKyiv)

        assertEquals(1, result.size)
        assertEquals(nearbyKyiv, result[0])
    }

    @Test
    fun `addRecent caps at maxEntries`() {
        var list = emptyList<RecentLocation>()
        for (i in 1..10) {
            list = RecentLocationsHelper.addRecent(
                list,
                RecentLocation(lat = i.toDouble(), lon = i.toDouble(), label = "City $i"),
                maxEntries = 5,
            )
        }
        assertEquals(5, list.size)
        assertEquals("City 10", list[0].label)
        assertEquals("City 6", list[4].label)
    }

    @Test
    fun `filterMatching returns all on blank query`() {
        val list = listOf(kyiv, lviv, odesa)
        assertEquals(list, RecentLocationsHelper.filterMatching(list, ""))
        assertEquals(list, RecentLocationsHelper.filterMatching(list, "   "))
    }

    @Test
    fun `filterMatching filters case-insensitively`() {
        val list = listOf(kyiv, lviv, odesa)
        val matches = RecentLocationsHelper.filterMatching(list, "lvi")
        assertEquals(1, matches.size)
        assertEquals(lviv, matches[0])

        val ukraineMatches = RecentLocationsHelper.filterMatching(list, "ukraine")
        assertEquals(3, ukraineMatches.size)
    }

    @Test
    fun `encode and decode JSON roundtrip`() {
        val list = listOf(kyiv, lviv)
        val json = RecentLocationsHelper.encodeToJson(list)
        assertTrue(json.contains("Kyiv"))
        val decoded = RecentLocationsHelper.decodeFromJson(json)
        assertEquals(list, decoded)
    }

    @Test
    fun `decodeFromJson handles null and invalid JSON gracefully`() {
        assertTrue(RecentLocationsHelper.decodeFromJson(null).isEmpty())
        assertTrue(RecentLocationsHelper.decodeFromJson("").isEmpty())
        assertTrue(RecentLocationsHelper.decodeFromJson("{ invalid }").isEmpty())
    }

    @Test
    fun `toResolvedLocation maps fields correctly`() {
        val resolved = kyiv.toResolvedLocation()
        assertEquals(kyiv.lat, resolved.lat, 0.0001)
        assertEquals(kyiv.lon, resolved.lon, 0.0001)
        assertEquals(kyiv.label, resolved.label)
        assertEquals("recent", resolved.source)
    }
}

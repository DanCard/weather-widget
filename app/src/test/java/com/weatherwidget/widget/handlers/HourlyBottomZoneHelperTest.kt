package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HourlyBottomZoneHelperTest {

    // ── findNearestIcon: searches outward from center for nearest non-null icon ──

    @Test
    fun `exact hit returns icon at center`() {
        val icons = listOf<Int?>(null, 42, null)
        assertEquals(42, HourlyBottomZoneHelper.findNearestIcon(icons, 1))
    }

    @Test
    fun `search right finds nearby icon`() {
        // 13 entries → radius = 13/13 = 1; icon at distance 1
        val icons = MutableList<Int?>(13) { null }.also { it[1] = 42 }
        assertEquals(42, HourlyBottomZoneHelper.findNearestIcon(icons, 0))
    }

    @Test
    fun `search left finds nearby icon`() {
        // 13 entries → radius = 1; icon at distance 1
        val icons = MutableList<Int?>(13) { null }.also { it[11] = 42 }
        assertEquals(42, HourlyBottomZoneHelper.findNearestIcon(icons, 12))
    }

    @Test
    fun `prefers left when equidistant`() {
        // Left is checked before right at each radius
        val icons = listOf<Int?>(10, null, 20)
        assertEquals(10, HourlyBottomZoneHelper.findNearestIcon(icons, 1))
    }

    @Test
    fun `prefers closer match over farther`() {
        val icons = listOf<Int?>(null, 10, null, null, 20)
        assertEquals(10, HourlyBottomZoneHelper.findNearestIcon(icons, 2))
    }

    @Test
    fun `all null within radius returns null`() {
        // 26 entries, icon only at index 25, center at 0 → radius = 26/13 = 2
        val icons = MutableList<Int?>(26) { null }.also { it[25] = 42 }
        assertNull(HourlyBottomZoneHelper.findNearestIcon(icons, 0))
    }

    @Test
    fun `single non-null element returns it`() {
        assertEquals(42, HourlyBottomZoneHelper.findNearestIcon(listOf(42), 0))
    }

    @Test
    fun `single null element returns null`() {
        assertNull(HourlyBottomZoneHelper.findNearestIcon(listOf(null), 0))
    }

    @Test
    fun `simulates WIDE zoom sparse data - finds icon within radius`() {
        // 153 entries, icons every 7th (like top-of-hour in a dense observation list)
        // Radius = 153 / 13 = 11, so icons at distance ≤ 3 should be found
        val icons = MutableList<Int?>(153) { null }
        for (i in icons.indices step 7) icons[i] = 100 + i
        // Center at index 10 (null), nearest icon at index 7 (distance 3)
        assertEquals(107, HourlyBottomZoneHelper.findNearestIcon(icons, 10))
    }

    @Test
    fun `simulates NARROW zoom sparse data - finds icon within radius`() {
        // 49 entries, icons every 2nd
        // Radius = 49 / 13 = 3
        val icons = MutableList<Int?>(49) { null }
        for (i in icons.indices step 2) icons[i] = 200 + i
        // Center at index 5 (null), nearest icon at index 4 (distance 1)
        assertEquals(204, HourlyBottomZoneHelper.findNearestIcon(icons, 5))
    }
}

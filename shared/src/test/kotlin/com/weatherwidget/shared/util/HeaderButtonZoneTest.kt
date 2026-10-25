package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/** One air ladder for every centred header button, both platforms (`plans/261025-shared-header-button-air.md`). */
@Category(ShortDuration::class)
class HeaderButtonZoneTest {

    @Test
    fun `air grows with the header's width and never goes below the 6dp floor`() {
        assertEquals(6f, HeaderButtonZone.airDp(340f), 0f)
        assertEquals(12f, HeaderButtonZone.airDp(360f), 0f)
        assertEquals(12f, HeaderButtonZone.airDp(373f), 0f) // Pixel 7 Pro: was 4dp on the hourly header
        assertEquals(20f, HeaderButtonZone.airDp(420f), 0f)
        assertEquals(32f, HeaderButtonZone.airDp(450f), 0f)
        assertEquals(6f, HeaderButtonZone.MIN_AIR_DP, 0f)
    }

    @Test
    fun `a zone is the icon plus its air`() {
        assertEquals(32f, HeaderButtonZone.zoneDp(20f, 373f), 0f)
        assertEquals(26f, HeaderButtonZone.minZoneDp(20f), 0f)
    }
}

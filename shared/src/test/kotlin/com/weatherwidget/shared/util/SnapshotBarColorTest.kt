package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the today-column 24h-prior snapshot bar's base-color rule so Android and desktop can't
 * silently diverge. Only rain overrides the platform's bright snapshot yellow; a cloudy snapshot
 * condition must NOT grey the base — grey base + grey split bottom rendered the whole bar as a
 * solid-grey slab, hiding both the forecast range and the cloud split.
 */
@Category(ShortDuration::class)
class SnapshotBarColorTest {

    @Test
    fun rainySnapshotKeepsBlueBase() {
        assertEquals(WeatherColors.FORECAST_RAINY, WeatherColors.snapshotBarOverrideArgb(isRainy = true))
    }

    @Test
    fun nonRainySnapshotUsesPlatformYellow() {
        // null = "no override": each platform paints its bright snapshot yellow. This is the
        // regression case — cloudy conditions used to return the slate-grey forecast color.
        assertNull(WeatherColors.snapshotBarOverrideArgb(isRainy = false))
    }
}

package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Guards the English-default source descriptions, mostly so a new [WeatherSource] can't ship
 * without one (it would render as an empty subtext on the desktop).
 */
@Category(ShortDuration::class)
class WeatherSourceDescriptionsTest {

    @Test
    fun everyConfigurableSourceHasANonBlankDescription() {
        for (source in WeatherSourceOrdering.ALL_CONFIGURABLE) {
            val description = WeatherSourceDescriptions.describe(source)
            assertTrue(
                "${source.id} needs a non-blank description for the Settings row",
                description.isNotBlank(),
            )
        }
    }

    @Test
    fun everyEnumValueResolvesWithoutFallingThroughToEmpty() {
        // Exhaustive — guards against a new enum entry rendering as "" if the when-expression
        // isn't updated. GENERIC_GAP is included here even though it's never user-visible.
        for (source in WeatherSource.values()) {
            assertTrue(
                "${source.id} description must not be empty",
                WeatherSourceDescriptions.describe(source).isNotEmpty(),
            )
        }
    }
}

package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Guards the English-default source descriptions that now live on [WeatherSource.description],
 * mostly so a new [WeatherSource] can't ship without one (it would render as an empty subtext on
 * the desktop).
 */
@Category(ShortDuration::class)
class WeatherSourceDescriptionTest {

    @Test
    fun everyConfigurableSourceHasANonBlankDescription() {
        for (source in WeatherSourceOrdering.ALL_CONFIGURABLE) {
            assertTrue(
                "${source.id} needs a non-blank description for the Settings row",
                source.description.isNotBlank(),
            )
        }
    }

    @Test
    fun everyEnumValueResolvesWithoutFallingThroughToEmpty() {
        // Exhaustive — guards against a new enum entry shipping with an empty description.
        // GENERIC_GAP is included here even though it's never user-visible.
        for (source in WeatherSource.entries) {
            assertTrue(
                "${source.id} description must not be empty",
                source.description.isNotEmpty(),
            )
        }
    }
}

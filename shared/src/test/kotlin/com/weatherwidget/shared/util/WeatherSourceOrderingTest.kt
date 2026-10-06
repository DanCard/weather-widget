package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the API-source list logic shared by Android `SettingsActivity` and desktop
 * `SettingsWindow`. Both platforms used to duplicate this; now they both call into
 * [WeatherSourceOrdering] and these tests guard the contract.
 */
@Category(ShortDuration::class)
class WeatherSourceOrderingTest {

    @Test
    fun allConfigurableExcludesGenericGapAndIncludesEveryUserSelectableSource() {
        assertTrue(
            "GENERIC_GAP is synthetic and must never appear in Settings",
            WeatherSource.GENERIC_GAP !in WeatherSourceOrdering.ALL_CONFIGURABLE,
        )
        // Every source the user can toggle must be present.
        listOf(
            WeatherSource.NWS,
            WeatherSource.OPEN_WEATHER_MAP,
            WeatherSource.OPEN_METEO,
            WeatherSource.SILURIAN,
            WeatherSource.TOMORROW_IO,
            WeatherSource.WEATHER_API,
            WeatherSource.GOOGLE_WEATHER,
        ).forEach {
            assertTrue("$it must be configurable", it in WeatherSourceOrdering.ALL_CONFIGURABLE)
        }
        assertTrue(WeatherSource.VISUAL_CROSSING !in WeatherSourceOrdering.ALL_CONFIGURABLE)
        assertTrue(WeatherSource.OPEN_WEATHER_MAP in WeatherSourceOrdering.ALL_CONFIGURABLE)
    }

    @Test
    fun orderedPutsVisibleFirstInStoredOrderThenHiddenInCanonicalOrder() {
        val visible = listOf(WeatherSource.OPEN_METEO.id, WeatherSource.NWS.id)

        val result = WeatherSourceOrdering.ordered(visible)

        assertEquals(
            "visible first, in stored order; then hidden in ALL_CONFIGURABLE order",
            listOf(
                WeatherSource.OPEN_METEO,
                WeatherSource.NWS,
                WeatherSource.GOOGLE_WEATHER,
                WeatherSource.TOMORROW_IO,
                WeatherSource.SILURIAN,
                WeatherSource.WEATHER_API,
                WeatherSource.OPEN_WEATHER_MAP,
            ),
            result,
        )
    }

    @Test
    fun orderedDropsUnknownIds() {
        val result = WeatherSourceOrdering.ordered(listOf("NWS", "MADE_UP_SOURCE", "OPEN_METEO"))

        assertEquals(
            "unknown ids silently dropped, matching both platforms' existing behavior",
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.GOOGLE_WEATHER,
                WeatherSource.TOMORROW_IO,
                WeatherSource.SILURIAN,
                WeatherSource.WEATHER_API,
                WeatherSource.OPEN_WEATHER_MAP,
            ),
            result,
        )
    }

    @Test
    fun orderedWithEmptyVisibleListRepairsDefaultsThenAppendsHiddenSources() {
        assertEquals(
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
                WeatherSource.GOOGLE_WEATHER,
                WeatherSource.TOMORROW_IO,
                WeatherSource.WEATHER_API,
                WeatherSource.OPEN_WEATHER_MAP,
            ),
            WeatherSourceOrdering.ordered(emptyList()),
        )
    }

    @Test
    fun toggleAddingAppendsWhenAbsent() {
        val result = WeatherSourceOrdering.toggle(
            listOf("NWS"),
            WeatherSource.OPEN_METEO,
            makeVisible = true,
        )
        assertEquals(listOf("NWS", "OPEN_METEO"), result)
    }

    @Test
    fun toggleAddingIsIdempotentWhenAlreadyPresent() {
        val result = WeatherSourceOrdering.toggle(
            listOf("NWS", "OPEN_METEO"),
            WeatherSource.NWS,
            makeVisible = true,
        )
        assertEquals("no duplicate added", listOf("NWS", "OPEN_METEO"), result)
    }

    @Test
    fun toggleRemovingTheLastSourceReturnsNull() {
        val result = WeatherSourceOrdering.toggle(
            listOf("NWS"),
            WeatherSource.NWS,
            makeVisible = false,
        )
        assertNull("must keep at least one source — null signals the platform should show feedback", result)
    }

    @Test
    fun toggleRemovingNonLastSourceRemovesIt() {
        val result = WeatherSourceOrdering.toggle(
            listOf("NWS", "OPEN_METEO"),
            WeatherSource.NWS,
            makeVisible = false,
        )
        assertEquals(listOf("OPEN_METEO"), result)
    }

    @Test
    fun moveUpAtTopReturnsInputUnchanged() {
        val input = listOf("NWS", "OPEN_METEO", "SILURIAN")
        val result = WeatherSourceOrdering.moveUp(input, WeatherSource.NWS)
        assertEquals("no-op at top edge", input, result)
    }

    @Test
    fun moveUpSwapsWithPredecessor() {
        val result = WeatherSourceOrdering.moveUp(
            listOf("NWS", "OPEN_METEO", "SILURIAN"),
            WeatherSource.OPEN_METEO,
        )
        assertEquals(listOf("OPEN_METEO", "NWS", "SILURIAN"), result)
    }

    @Test
    fun moveUpOnMissingSourceIsNoOp() {
        val input = listOf("NWS", "OPEN_METEO")
        val result = WeatherSourceOrdering.moveUp(input, WeatherSource.SILURIAN)
        assertEquals(input, result)
    }

    @Test
    fun moveDownAtBottomReturnsInputUnchanged() {
        val input = listOf("NWS", "OPEN_METEO", "SILURIAN")
        val result = WeatherSourceOrdering.moveDown(input, WeatherSource.SILURIAN)
        assertEquals("no-op at bottom edge", input, result)
    }

    @Test
    fun moveDownSwapsWithSuccessor() {
        val result = WeatherSourceOrdering.moveDown(
            listOf("NWS", "OPEN_METEO", "SILURIAN"),
            WeatherSource.OPEN_METEO,
        )
        assertEquals(listOf("NWS", "SILURIAN", "OPEN_METEO"), result)
    }

    @Test
    fun moveDownOnMissingSourceIsNoOp() {
        val input = listOf("NWS", "OPEN_METEO")
        val result = WeatherSourceOrdering.moveDown(input, WeatherSource.SILURIAN)
        assertEquals(input, result)
    }

    @Test
    fun defaultVisibleIdsMatchesCanonicalFreshInstallOrder() {
        assertEquals(
            "default visible ids must be in canonical order NWS, OPEN_METEO, SILURIAN",
            listOf("NWS", "OPEN_METEO", "SILURIAN"),
            WeatherSourceOrdering.DEFAULT_VISIBLE_IDS,
        )
    }

    @Test
    fun sanitizeVisibleDropsDeprecatedUnknownAndDuplicateSources() {
        val result = WeatherSourceOrdering.sanitizeVisibleIds(
            listOf(
                "VISUAL_CROSSING",
                "NWS",
                "MADE_UP_SOURCE",
                "NWS",
                "OPEN_WEATHER_MAP",
                "OPEN_METEO",
            ),
        )

        assertEquals(listOf("NWS", "OPEN_WEATHER_MAP", "OPEN_METEO"), result)
    }

    @Test
    fun sanitizeVisibleFallsBackToCanonicalDefaultsWhenNothingSurvives() {
        assertEquals(
            WeatherSourceOrdering.DEFAULT_VISIBLE_IDS,
            WeatherSourceOrdering.sanitizeVisibleIds(listOf("VISUAL_CROSSING", "MADE_UP_SOURCE")),
        )
    }

    @Test
    fun operationsReturnNewListsLeavingInputUntouched() {
        val original = listOf("NWS", "OPEN_METEO")
        WeatherSourceOrdering.moveUp(original, WeatherSource.OPEN_METEO)
        WeatherSourceOrdering.moveDown(original, WeatherSource.NWS)
        WeatherSourceOrdering.toggle(original, WeatherSource.SILURIAN, makeVisible = true)

        assertEquals("input list not mutated", listOf("NWS", "OPEN_METEO"), original)
    }

    @Test
    fun `enabling Google makes it primary`() {
        val result = WeatherSourceOrdering.toggle(listOf("NWS", "OPEN_METEO"), WeatherSource.GOOGLE_WEATHER, makeVisible = true)
        assertEquals(listOf("GOOGLE_WEATHER", "NWS", "OPEN_METEO"), result)
    }

    @Test
    fun `enabling OWM appends it last, and it can then be moved up`() {
        val enabled = WeatherSourceOrdering.toggle(listOf("NWS", "OPEN_METEO"), WeatherSource.OPEN_WEATHER_MAP, makeVisible = true)!!
        assertEquals(listOf("NWS", "OPEN_METEO", "OPEN_WEATHER_MAP"), enabled)
        assertEquals(
            listOf("NWS", "OPEN_WEATHER_MAP", "OPEN_METEO"),
            WeatherSourceOrdering.moveUp(enabled, WeatherSource.OPEN_WEATHER_MAP),
        )
    }

    @Test
    fun `Google placement is a starting position, not a pin`() {
        val ids = listOf("GOOGLE_WEATHER", "NWS")
        assertEquals(listOf("NWS", "GOOGLE_WEATHER"), WeatherSourceOrdering.moveDown(ids, WeatherSource.GOOGLE_WEATHER))
        // Re-enabling an already-enabled source leaves the user's order alone.
        assertEquals(listOf("NWS", "GOOGLE_WEATHER"), WeatherSourceOrdering.withEnabled(listOf("NWS", "GOOGLE_WEATHER"), WeatherSource.GOOGLE_WEATHER))
    }

    @Test
    fun `a newly enabled primary-on-enable source takes over the display`() {
        assertEquals(
            "GOOGLE_WEATHER",
            WeatherSourceOrdering.selectionAfterChange(listOf("NWS", "OPEN_METEO"), listOf("GOOGLE_WEATHER", "NWS", "OPEN_METEO"), "OPEN_METEO"),
        )
    }

    @Test
    fun `any other change keeps the display's own choice, or falls back to the new primary`() {
        assertEquals("OPEN_METEO", WeatherSourceOrdering.selectionAfterChange(listOf("NWS", "OPEN_METEO"), listOf("NWS", "OPEN_METEO", "OPEN_WEATHER_MAP"), "OPEN_METEO"))
        // Reordering Google back to the front is not "enabling" it.
        assertEquals("NWS", WeatherSourceOrdering.selectionAfterChange(listOf("NWS", "GOOGLE_WEATHER"), listOf("GOOGLE_WEATHER", "NWS"), "NWS"))
        assertEquals("NWS", WeatherSourceOrdering.selectionAfterChange(listOf("NWS", "SILURIAN"), listOf("NWS"), "SILURIAN"))
    }

    @Test
    fun `newlyPrimary names only a primary-on-enable source just enabled at the front`() {
        assertEquals(WeatherSource.GOOGLE_WEATHER, WeatherSourceOrdering.newlyPrimary(listOf("NWS"), listOf("GOOGLE_WEATHER", "NWS")))
        // Reordering an enabled Google to the front is not "becoming primary on enable".
        assertNull(WeatherSourceOrdering.newlyPrimary(listOf("NWS", "GOOGLE_WEATHER"), listOf("GOOGLE_WEATHER", "NWS")))
        // Other sources are appended, never primary-on-enable.
        assertNull(WeatherSourceOrdering.newlyPrimary(listOf("NWS"), listOf("NWS", "OPEN_WEATHER_MAP")))
        assertNull(WeatherSourceOrdering.newlyPrimary(listOf("NWS", "SILURIAN"), listOf("SILURIAN")))
    }
}

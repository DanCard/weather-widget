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
            WeatherSource.OPEN_METEO,
            WeatherSource.SILURIAN,
            WeatherSource.TOMORROW_IO,
            WeatherSource.WEATHER_API,
            WeatherSource.VISUAL_CROSSING,
        ).forEach {
            assertTrue("$it must be configurable", it in WeatherSourceOrdering.ALL_CONFIGURABLE)
        }
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
                WeatherSource.TOMORROW_IO,
                WeatherSource.SILURIAN,
                WeatherSource.WEATHER_API,
                WeatherSource.VISUAL_CROSSING,
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
                WeatherSource.TOMORROW_IO,
                WeatherSource.SILURIAN,
                WeatherSource.WEATHER_API,
                WeatherSource.VISUAL_CROSSING,
            ),
            result,
        )
    }

    @Test
    fun orderedWithEmptyVisibleListReturnsCanonicalOrder() {
        assertEquals(
            WeatherSourceOrdering.ALL_CONFIGURABLE,
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
    fun operationsReturnNewListsLeavingInputUntouched() {
        val original = listOf("NWS", "OPEN_METEO")
        WeatherSourceOrdering.moveUp(original, WeatherSource.OPEN_METEO)
        WeatherSourceOrdering.moveDown(original, WeatherSource.NWS)
        WeatherSourceOrdering.toggle(original, WeatherSource.SILURIAN, makeVisible = true)

        assertEquals("input list not mutated", listOf("NWS", "OPEN_METEO"), original)
    }
}

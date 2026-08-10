package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * `DesktopConfig` is one object with two independent writers: the Settings window and the popup
 * (window geometry, zoom, pan, view mode, day clicks). Both persist the whole object, so whoever
 * saved last used to clobber the other — which is how a changed Hourly Zoom span reverted on save.
 *
 * `withSettingsFrom` is the ownership split that makes the Settings window able to rebase an
 * in-progress draft onto a newer baseline instead of being reset by it. These tests pin that split
 * from both directions: settings edits survive a popup save, and popup state survives a settings save.
 */
@Category(ShortDuration::class)
class SettingsDraftRebaseTest {

    private fun config(
        narrowZoomSpanHours: Int = 5,
        zoomFactor: Float = DesktopGraphUtils.DEFAULT_ZOOM_FACTOR,
        windowX: Float? = null,
        viewMode: ViewMode = ViewMode.DAILY,
        personalStationDiscount: Int = 95,
    ) = DesktopConfig(
        lat = 37.42,
        lon = -122.08,
        label = "Test",
        narrowZoomSpanHours = narrowZoomSpanHours,
        zoomFactor = zoomFactor,
        windowX = windowX,
        viewMode = viewMode,
        personalStationDiscount = personalStationDiscount,
    )

    @Test
    fun `an unsaved settings edit survives a popup config save`() {
        // This is the reported bug, start to finish.
        val baseline = config(narrowZoomSpanHours = 5)

        // 1. User drags the Hourly Zoom slider to 7. Draft only — not yet persisted.
        val draft = baseline.copy(narrowZoomSpanHours = 7)

        // 2. Within the 5s auto-save window the popup persists something of its own: the window was
        //    nudged, so Main writes windowX off the PERSISTED config, which still says 5h.
        val newBaseline = baseline.copy(windowX = 120f)

        // 3. The Settings window rebases instead of resetting.
        val rebased = newBaseline.withSettingsFrom(draft)

        assertEquals("the user's 7h edit must survive", 7, rebased.narrowZoomSpanHours)
        assertEquals("the popup's window move must survive too", 120f, rebased.windowX)
    }

    @Test
    fun `saving settings does not clobber popup state that moved underneath it`() {
        // The mirror image: the draft carries a stale copy of every popup field, so saving the draft
        // verbatim would rewind the window position and zoom the user just changed.
        val baseline = config(narrowZoomSpanHours = 5, zoomFactor = 0.3f, windowX = 10f)
        val draft = baseline.copy(narrowZoomSpanHours = 8)

        val newBaseline = baseline.copy(zoomFactor = 0.75f, windowX = 900f, viewMode = ViewMode.HOURLY)
        val saved = newBaseline.withSettingsFrom(draft)

        assertEquals(8, saved.narrowZoomSpanHours)
        assertEquals("zoom must stay where the popup put it", 0.75f, saved.zoomFactor)
        assertEquals("window position must stay where the popup put it", 900f, saved.windowX)
        assertEquals("view mode must stay where the popup put it", ViewMode.HOURLY, saved.viewMode)
    }

    @Test
    fun `dirty is computed on settings fields only`() {
        // Comparing whole configs latched dirty forever the moment the popup moved its window — and a
        // save in that state wrote the stale geometry back.
        val baseline = config(windowX = 10f)
        val untouchedDraft = baseline

        val movedWindow = baseline.copy(windowX = 999f)
        assertNotEquals("whole-config comparison would call this dirty", movedWindow, untouchedDraft)
        assertEquals(
            "settings-only comparison must call it clean",
            movedWindow,
            movedWindow.withSettingsFrom(untouchedDraft),
        )

        val editedDraft = baseline.copy(personalStationDiscount = 40)
        assertNotEquals(
            "a real settings edit must still read dirty",
            movedWindow,
            movedWindow.withSettingsFrom(editedDraft),
        )
    }

    @Test
    fun `every settings-owned field carries across a rebase`() {
        // Guards the merge list: a field added to the Settings UI but forgotten in withSettingsFrom
        // would silently revert exactly the way narrowZoomSpanHours did.
        val baseline = config()
        val draft = baseline.copy(
            weatherSource = "OPEN_METEO",
            visibleSources = listOf("OPEN_METEO"),
            apiKeys = mapOf("SILURIAN" to "abc"),
            narrowZoomSpanHours = 8,
            personalStationDiscount = 10,
            useCelsius = !baseline.useCelsius,
            todayOverlayDelta = true,
            todayOverlayDominantTemp = true,
            todayOverlayDominantAge = true,
        )
        val rebased = baseline.copy(windowX = 5f).withSettingsFrom(draft)

        DesktopConfig.SETTINGS_OWNED_FIELDS.forEach { field ->
            assertTrue(
                "$field is listed as settings-owned but did not survive the rebase",
                rebased.settingsDiffFrom(draft).none { it.startsWith("$field:") },
            )
        }
        assertTrue(
            "rebased draft must be settings-identical to the draft",
            rebased.settingsDiffFrom(draft).isEmpty(),
        )
    }

    @Test
    fun `settings diff names the field and both values`() {
        // The logging this bug needed: nothing ever printed what a save contained.
        val before = config(narrowZoomSpanHours = 5)
        val after = before.copy(narrowZoomSpanHours = 7)
        assertEquals(listOf("narrowZoomSpanHours: 5 -> 7"), after.settingsDiffFrom(before))
        assertTrue("identical configs must diff empty", before.settingsDiffFrom(before).isEmpty())
    }

    @Test
    fun `popup-only changes do not appear in the settings diff`() {
        val before = config(windowX = 1f, zoomFactor = 0.2f)
        val after = before.copy(windowX = 2f, zoomFactor = 0.9f, hourlyOffset = 4, dateOffset = 2)
        assertTrue(
            "window/zoom/pan churn must not read as a settings change: ${after.settingsDiffFrom(before)}",
            after.settingsDiffFrom(before).isEmpty(),
        )
    }
}

package com.weatherwidget.shared.settings

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SettingsSectionTest {

    @Test
    fun `titles are non-blank and unique`() {
        val titles = SettingsSection.entries.map { it.title }
        assertTrue(titles.all { it.isNotBlank() })
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `every section is shown on at least one platform`() {
        SettingsSection.entries.forEach { assertTrue("${it.name} has no platform", it.platforms.isNotEmpty()) }
    }

    /** forPlatform preserves declaration order, which is the on-screen order both screens follow. */
    @Test
    fun `forPlatform is declaration order filtered by membership`() {
        val android = SettingsSection.forPlatform(Platform.ANDROID)
        val desktop = SettingsSection.forPlatform(Platform.DESKTOP)
        assertEquals(SettingsSection.entries.filter { Platform.ANDROID in it.platforms }, android)
        assertEquals(SettingsSection.entries.filter { Platform.DESKTOP in it.platforms }, desktop)
        // Language is the only Android-only section; nothing is desktop-only.
        assertEquals(android - desktop.toSet(), listOf(SettingsSection.LANGUAGE))
        assertTrue((desktop - android.toSet()).isEmpty())
    }
}

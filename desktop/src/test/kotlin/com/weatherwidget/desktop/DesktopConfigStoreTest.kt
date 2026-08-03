package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Category(ShortDuration::class)
class DesktopConfigStoreTest {

    @Test
    fun `load removes deprecated sources and repairs selected source`() {
        val configPath = Files.createTempDirectory("desktop-config-source-policy").resolve("config.json")
        configPath.writeText(
            """
            {
              "lat": 37.42,
              "lon": -122.08,
              "label": "Test",
              "weatherSource": "VISUAL_CROSSING",
              "visibleSources": ["VISUAL_CROSSING", "OPEN_WEATHER_MAP", "OPEN_METEO"]
            }
            """.trimIndent(),
        )

        val loaded = DesktopConfigStore(configPath).load()

        requireNotNull(loaded)
        assertEquals(listOf("OPEN_METEO"), loaded.visibleSources)
        assertEquals("OPEN_METEO", loaded.weatherSource)
        val normalizedJson = configPath.readText()
        assertEquals(false, normalizedJson.contains("VISUAL_CROSSING"))
        assertEquals(false, normalizedJson.contains("OPEN_WEATHER_MAP"))
    }

    @Test
    fun `obsSelectedTab defaults to TAB_OBSERVATIONS and persists tab selection`() {
        val defaultConfig = DesktopConfig(lat = 37.42, lon = -122.08, label = "Test")
        assertEquals(TAB_OBSERVATIONS, defaultConfig.obsSelectedTab)

        val configPath = Files.createTempDirectory("desktop-config-obs-tab").resolve("config.json")
        val store = DesktopConfigStore(configPath)

        val updatedConfig = defaultConfig.copy(obsSelectedTab = TAB_FETCH_LOGS)
        store.save(updatedConfig)

        val reloaded = store.load()
        requireNotNull(reloaded)
        assertEquals(TAB_FETCH_LOGS, reloaded.obsSelectedTab)
    }
}

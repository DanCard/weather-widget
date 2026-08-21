package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Category(ShortDuration::class)
class DesktopConfigStoreTest {

    @Test
    fun `missing unit is resolved once and persisted explicitly`() {
        val configPath = Files.createTempDirectory("desktop-config-unit-migration").resolve("config.json")
        configPath.writeText(
            """
            {
              "lat": 37.42,
              "lon": -122.08,
              "label": "Test",
              "settings": { "weatherSource": "OPEN_METEO" }
            }
            """.trimIndent(),
        )

        val loaded = DesktopConfigStore(configPath, missingUnitDefault = { false }).load()

        requireNotNull(loaded)
        assertEquals(false, loaded.settings.useCelsius)
        val persisted = Json.parseToJsonElement(configPath.readText()).jsonObject
        assertEquals(
            false,
            persisted.getValue("settings").jsonObject
                .getValue("useCelsius").jsonPrimitive.boolean,
        )
    }

    @Test
    fun `explicit unit wins over the process default`() {
        val configPath = Files.createTempDirectory("desktop-config-explicit-unit").resolve("config.json")
        configPath.writeText(
            """
            {
              "lat": 37.42,
              "lon": -122.08,
              "label": "Test",
              "settings": { "weatherSource": "OPEN_METEO", "useCelsius": true }
            }
            """.trimIndent(),
        )

        val loaded = DesktopConfigStore(configPath, missingUnitDefault = { false }).load()

        assertEquals(true, requireNotNull(loaded).settings.useCelsius)
    }

    @Test
    fun `desktop unit default ignores regionless C locale and uses LANG region`() {
        assertEquals(
            false,
            desktopDefaultUseCelsius(
                environment = mapOf("LC_ALL" to "C.UTF-8", "LANG" to "en_US.UTF-8"),
                fallbackLocale = java.util.Locale.FRANCE,
            ),
        )
    }

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
        assertEquals(listOf("OPEN_METEO"), loaded.settings.visibleSources)
        assertEquals("OPEN_METEO", loaded.settings.weatherSource)
        val normalizedJson = configPath.readText()
        assertEquals(false, normalizedJson.contains("VISUAL_CROSSING"))
        assertEquals(false, normalizedJson.contains("OPEN_WEATHER_MAP"))
    }

    @Test
    fun `obsSelectedTab defaults to TAB_OBSERVATIONS and persists tab selection`() {
        val defaultConfig = DesktopConfig(
lat = 37.42,
lon = -122.08,
label = "Test",
)
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

package com.weatherwidget.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class DesktopConfig(
    val lat: Double,
    val lon: Double,
    val label: String,
    val weatherSource: String = "NWS",
    val viewMode: ViewMode = ViewMode.DAILY,
    val windowX: Float? = null,
    val windowY: Float? = null,
    val windowWidth: Float? = null,
    val windowHeight: Float? = null,
    val apiKeys: Map<String, String> = emptyMap(),
    val visibleSources: List<String> = listOf("NWS", "OPEN_METEO", "SILURIAN"),
    val dateOffset: Int = 0,
    // Daily-view scroll-wheel zoom: extra history days prepended on the left (today + future stay
    // anchored on the right). 0 = default view (~1 history day). encodeDefaults=false omits the 0.
    val dailyExtraHistory: Int = 0,
    val hourlyOffset: Int = 0,
    // Continuous zoom: 0 = most zoomed-in (~±2h), 1 = most zoomed-out (6 days back / 1 day forward).
    // Legacy "zoomLevel" string configs are ignored on read (ignoreUnknownKeys) and reset to default.
    val zoomFactor: Float = DesktopGraphUtils.DEFAULT_ZOOM_FACTOR,
    val obsWindowX: Float? = null,
    val obsWindowY: Float? = null,
    val obsWindowWidth: Float? = null,
    val obsWindowHeight: Float? = null,
    val historyWindowX: Float? = null,
    val historyWindowY: Float? = null,
    val historyWindowWidth: Float? = null,
    val historyWindowHeight: Float? = null,
)

class DesktopConfigStore(
    private val configPath: Path = defaultConfigPath(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) {
    fun load(): DesktopConfig? {
        if (!configPath.exists()) return null
        return runCatching {
            json.decodeFromString<DesktopConfig>(configPath.readText())
        }.getOrNull()
    }

    fun save(config: DesktopConfig) {
        configPath.parent?.createDirectories()
        configPath.writeText(json.encodeToString(DesktopConfig.serializer(), config))
    }

    companion object {
        fun defaultConfigPath(): Path {
            val configHome = System.getenv("XDG_CONFIG_HOME")
                ?.takeIf { it.isNotBlank() }
                ?: "${System.getProperty("user.home")}/.config"
            return Path.of(configHome, "weather-widget", "config.json")
        }
    }
}

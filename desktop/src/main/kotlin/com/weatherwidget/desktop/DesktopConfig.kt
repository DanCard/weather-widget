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
    val source: String,
    val weatherSource: String = "NWS",
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

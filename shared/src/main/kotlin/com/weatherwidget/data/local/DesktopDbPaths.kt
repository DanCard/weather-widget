package com.weatherwidget.data.local

import java.nio.file.Path

object DesktopDbPaths {
    fun defaultDbPath(): Path {
        val dataHome = System.getenv("XDG_DATA_HOME")
            ?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.home")}/.local/share"
        return Path.of(dataHome, "weather-widget", "weather.db")
    }
}

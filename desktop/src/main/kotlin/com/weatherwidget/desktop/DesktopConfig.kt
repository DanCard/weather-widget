package com.weatherwidget.desktop

import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.util.WeatherSourceOrdering
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
    // Span (4..8h) of the tight NARROW zoom stage, matching Android's Settings → "Hourly Zoom".
    // Desktop's wheel/drag zoom stays continuous down to ~±2h; this governs the stage a *click*
    // snaps to and the nav-arrow step there, so click-cycling agrees with the widget.
    val narrowZoomSpanHours: Int = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS,
    val obsWindowX: Float? = null,
    val obsWindowY: Float? = null,
    val obsWindowWidth: Float? = null,
    val obsWindowHeight: Float? = null,
    val obsSelectedTab: Int = TAB_OBSERVATIONS,
    val historyWindowX: Float? = null,
    val historyWindowY: Float? = null,
    val historyWindowWidth: Float? = null,
    val historyWindowHeight: Float? = null,
    // Settings window bounds, mirroring the popup/observations/history windows. Popup-owned, NOT
    // settings-owned: they are written by dragging the window, so withSettingsFrom must leave them
    // on the newer baseline or moving the Settings window while editing would rewind itself.
    val settingsWindowX: Float? = null,
    val settingsWindowY: Float? = null,
    val settingsWindowWidth: Float? = null,
    val settingsWindowHeight: Float? = null,
    // App-wide discount (0..100%) applied to personal weather stations in the actual-temperature
    // IDW blend. 0 = no discount (counts the same as official); 100 = personal stations ignored.
    val personalStationDiscount: Int = 95,
    // Locale-derived until the user touches the toggle. encodeDefaults=false means a value
    // equal to the locale default stays unwritten in config.json and keeps following the
    // locale; an explicit differing choice is persisted and wins.
    val useCelsius: Boolean =
        com.weatherwidget.shared.util.UnitDefaults.defaultUseCelsius(java.util.Locale.getDefault()),
    // Daily-view large-Today-column overlay texts. All opt-in (default off).
    val todayOverlayDelta: Boolean = false,
    val todayOverlayDominantTemp: Boolean = false,
    val todayOverlayDominantAge: Boolean = false,
) {
    // 0% discount -> weight 1.0 (no discount); 100% discount -> weight 0.0 (PWS ignored).
    fun personalStationWeight(): Double = 1.0 - personalStationDiscount.coerceIn(0, 100) / 100.0

    /**
     * Returns this config with the Settings-owned fields taken from [draft].
     *
     * `DesktopConfig` is one object serving two independent writers: the Settings window (these
     * fields) and the popup (window geometry, zoomFactor, hourlyOffset, dateOffset, viewMode,
     * obs/history window bounds — plus lat/lon/label, which the location picker saves directly).
     * Both write the whole object, so whoever saves last used to clobber the other's fields.
     *
     * Splitting ownership here lets the Settings window rebase an in-progress draft onto a newer
     * persisted config instead of being reset by it: popup fields come from the newer baseline,
     * settings fields from the draft. See [SETTINGS_OWNED_FIELDS] for the log-facing names.
     */
    fun withSettingsFrom(draft: DesktopConfig): DesktopConfig = copy(
        weatherSource = draft.weatherSource,
        visibleSources = draft.visibleSources,
        apiKeys = draft.apiKeys,
        narrowZoomSpanHours = draft.narrowZoomSpanHours,
        personalStationDiscount = draft.personalStationDiscount,
        useCelsius = draft.useCelsius,
        todayOverlayDelta = draft.todayOverlayDelta,
        todayOverlayDominantTemp = draft.todayOverlayDominantTemp,
        todayOverlayDominantAge = draft.todayOverlayDominantAge,
    )

    /**
     * `field: old -> new` for every Settings-owned field that differs from [other]. Empty when the
     * two agree. Exists so the logs name actual values — the reverting-setting bug was invisible
     * precisely because nothing ever logged what a save contained.
     */
    fun settingsDiffFrom(other: DesktopConfig): List<String> = buildList {
        fun add(name: String, a: Any?, b: Any?) {
            if (a != b) add("$name: $b -> $a")
        }
        add("weatherSource", weatherSource, other.weatherSource)
        add("visibleSources", visibleSources, other.visibleSources)
        add("apiKeys", apiKeys.keys.sorted(), other.apiKeys.keys.sorted())
        add("narrowZoomSpanHours", narrowZoomSpanHours, other.narrowZoomSpanHours)
        add("personalStationDiscount", personalStationDiscount, other.personalStationDiscount)
        add("useCelsius", useCelsius, other.useCelsius)
        add("todayOverlayDelta", todayOverlayDelta, other.todayOverlayDelta)
        add("todayOverlayDominantTemp", todayOverlayDominantTemp, other.todayOverlayDominantTemp)
        add("todayOverlayDominantAge", todayOverlayDominantAge, other.todayOverlayDominantAge)
    }

    companion object {
        /** Documentation-only list of the fields [withSettingsFrom] carries. */
        val SETTINGS_OWNED_FIELDS = listOf(
            "weatherSource", "visibleSources", "apiKeys", "narrowZoomSpanHours",
            "personalStationDiscount", "useCelsius",
            "todayOverlayDelta", "todayOverlayDominantTemp", "todayOverlayDominantAge",
        )
    }
}

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
        }.map { decoded ->
            val normalizedVisible = WeatherSourceOrdering.sanitizeVisibleIds(decoded.visibleSources)
            val normalizedSource = decoded.weatherSource.takeIf { it in normalizedVisible }
                ?: normalizedVisible.first()
            val normalized = decoded.copy(
                weatherSource = normalizedSource,
                visibleSources = normalizedVisible,
            )
            if (normalized != decoded) save(normalized)
            normalized
        }.getOrNull()
    }

    fun save(config: DesktopConfig) {
        val normalizedVisible = WeatherSourceOrdering.sanitizeVisibleIds(config.visibleSources)
        val normalized = config.copy(
            weatherSource = config.weatherSource.takeIf { it in normalizedVisible }
                ?: normalizedVisible.first(),
            visibleSources = normalizedVisible,
        )
        configPath.parent?.createDirectories()
        configPath.writeText(json.encodeToString(DesktopConfig.serializer(), normalized))
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

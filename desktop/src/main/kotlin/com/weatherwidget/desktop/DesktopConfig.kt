package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.shared.util.WeatherSourceOrdering
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The Settings-window-owned fields, nested so the settings/non-settings ownership boundary is
 * structural (one field) instead of a hand-maintained name list. Written by the Settings window;
 * every other writer must preserve [DesktopConfig.settings] verbatim.
 */
@Serializable
data class DesktopSettings(
    val weatherSource: String = "NWS",
    val visibleSources: List<String> = WeatherSourceOrdering.DEFAULT_VISIBLE_IDS,
    val apiKeys: Map<String, String> = emptyMap(),
    // Span (4..8h) of the tight NARROW zoom stage, matching Android's Settings → "Hourly Zoom".
    val narrowZoomSpanHours: Int = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS,
    // Whether the click-to-cycle includes the multi-day TWO_DAY stage (48h: 42 back, 6 forward).
    // Off by default, matching Android. Gates the *cycle* only — the mouse wheel is continuous and
    // still reaches multi-day spans regardless, which is why this cannot be a render-time gate.
    val multiDayZoomEnabled: Boolean = false,
    // App-wide discount (0..100%) applied to personal weather stations in the actual-temperature
    // IDW blend. 0 = no discount; 100 = personal stations ignored.
    val personalStationDiscount: Int = 95,
    // Locale-derived for a legacy/first-run config, then materialized by DesktopConfigStore on
    // every save so the daemon and UI cannot derive different defaults from their environments.
    val useCelsius: Boolean = desktopDefaultUseCelsius(),
    // Daily-view large-Today-column overlay texts. All opt-in (default off).
    val todayOverlayDelta: Boolean = false,
    val todayOverlayDominantTemp: Boolean = false,
    val todayOverlayDominantAge: Boolean = false,
    // Per-source override for the feed that supplies a forecast-only source's actuals, keyed by
    // WeatherSource.id -> provider WeatherSource.id. Mirrors Android's `actuals_provider_<SOURCE>`
    // SharedPreferences keys. An ABSENT entry means "use the default"
    // (ActualsProviderResolver.DEFAULT_PROVIDER) and is deliberately not written as an explicit
    // value: storing the default would silently pin the user if the default ever moves.
    val actualsProviders: Map<String, String> = emptyMap(),
) {
    // 0% discount -> weight 1.0 (no discount); 100% discount -> weight 0.0 (PWS ignored).
    fun personalStationWeight(): Double = 1.0 - personalStationDiscount.coerceIn(0, 100) / 100.0

    /**
     * `field: old -> new` for every field that differs from [other]. Empty when the two agree.
     * Exists so the logs name actual values — the reverting-setting bug was invisible precisely
     * because nothing ever logged what a save contained.
     */
    fun diffFrom(other: DesktopSettings): List<String> = buildList {
        fun add(name: String, a: Any?, b: Any?) {
            if (a != b) add("$name: $b -> $a")
        }
        add("weatherSource", weatherSource, other.weatherSource)
        add("visibleSources", visibleSources, other.visibleSources)
        add("apiKeys", apiKeys.keys.sorted(), other.apiKeys.keys.sorted())
        add("narrowZoomSpanHours", narrowZoomSpanHours, other.narrowZoomSpanHours)
        add("multiDayZoomEnabled", multiDayZoomEnabled, other.multiDayZoomEnabled)
        add("personalStationDiscount", personalStationDiscount, other.personalStationDiscount)
        add("useCelsius", useCelsius, other.useCelsius)
        add("actualsProviders", actualsProviders, other.actualsProviders)
        add("todayOverlayDelta", todayOverlayDelta, other.todayOverlayDelta)
        add("todayOverlayDominantTemp", todayOverlayDominantTemp, other.todayOverlayDominantTemp)
        add("todayOverlayDominantAge", todayOverlayDominantAge, other.todayOverlayDominantAge)
    }
}

/**
 * Stable desktop unit default across the daemon and its deliberately minimal-environment UI child.
 * A region-bearing measurement/locale environment wins; `C`/`C.UTF-8` has no region and is skipped
 * rather than being interpreted as an instruction to use Celsius. The JVM locale is the fallback.
 */
internal fun desktopDefaultUseCelsius(
    environment: Map<String, String> = System.getenv(),
    fallbackLocale: Locale = Locale.getDefault(),
): Boolean {
    val country = listOf("LC_MEASUREMENT", "LC_ALL", "LANG")
        .asSequence()
        .mapNotNull { environment[it] }
        .mapNotNull(::localeCountry)
        .firstOrNull()
        ?: fallbackLocale.country.takeIf { it.isNotBlank() }
    return com.weatherwidget.shared.util.UnitDefaults.defaultUseCelsius(country)
}

private fun localeCountry(raw: String): String? {
    val base = raw.substringBefore('.').substringBefore('@')
    if (base.equals("C", ignoreCase = true) || base.equals("POSIX", ignoreCase = true)) return null
    return Locale.forLanguageTag(base.replace('_', '-')).country.takeIf { it.isNotBlank() }
}

@Serializable
data class DesktopConfig(
    val lat: Double,
    val lon: Double,
    val label: String,
    // Settings-window-owned fields, nested so a non-settings writer can preserve them as a unit.
    val settings: DesktopSettings = DesktopSettings(),
    val viewMode: ViewMode = ViewMode.DAILY,
    val windowX: Float? = null,
    val windowY: Float? = null,
    val windowWidth: Float? = null,
    val windowHeight: Float? = null,
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
) {
    fun personalStationWeight(): Double = settings.personalStationWeight()

    /**
     * Returns this config with the Settings-owned fields taken from [draft].
     *
     * `DesktopConfig` is one object serving two independent writers: the Settings window
     * ([settings]) and the popup/observations/history/location-picker windows (everything else).
     * Both write the whole object, so whoever saves last used to clobber the other's fields.
     * Nesting the settings fields under [settings] makes this a one-line structural split rather
     * than a hand-maintained name list.
     */
    fun withSettingsFrom(draft: DesktopConfig): DesktopConfig = copy(settings = draft.settings)

    /** `field: old -> new` for every settings-owned field that differs from [other]. */
    fun settingsDiffFrom(other: DesktopConfig): List<String> = settings.diffFrom(other.settings)
}

/**
 * Merges a save from a non-settings writer onto the latest persisted config.
 *
 * `DesktopConfig` is written whole by several windows, and the popup / observations / history
 * windows compute their updates from their own `config` snapshot — which can lag the persisted
 * config — while the location picker builds a config with *default* settings fields. Saving any of
 * those verbatim clobbers settings-owned fields (the reported "Hourly Zoom reverted to 6h" bug).
 *
 * This keeps every non-settings field from [draft] (popup geometry/zoom/pan/view mode, obs/history
 * window bounds, lat/lon/label) while taking every settings field from [persisted].
 * [allowWeatherSourceChange] re-admits the one settings field other writers legitimately change:
 * the popup header toggles the active source, and the location picker chooses a per-region default
 * (NWS for the US, Open-Meteo elsewhere).
 * [allowActualsProvidersChange] allows the observations window to change per-source actuals choices.
 */
internal fun mergeNonSettingsSave(
    persisted: DesktopConfig,
    draft: DesktopConfig,
    allowWeatherSourceChange: Boolean,
    allowActualsProvidersChange: Boolean = false,
): DesktopConfig {
    var settings = persisted.settings
    if (allowWeatherSourceChange) {
        settings = settings.copy(weatherSource = draft.settings.weatherSource)
    }
    if (allowActualsProvidersChange) {
        settings = settings.copy(actualsProviders = draft.settings.actualsProviders)
    }
    return draft.copy(settings = settings)
}

/** Pure result of applying one source-tagged whole-config save to the latest state. */
internal data class DesktopConfigSaveResolution(
    val config: DesktopConfig,
    val settingsChanges: List<String>,
    val mergedAwaySettings: List<String>,
    val zoomFactorBeforeResnap: Float?,
)

/**
 * Resolves the source-specific save policy without performing disk, daemon, or logging effects.
 * Keeping this here makes [DesktopConfig] the single owner of whole-object writer reconciliation.
 */
internal fun resolveDesktopConfigSave(
    persisted: DesktopConfig?,
    draft: DesktopConfig,
    source: String,
): DesktopConfigSaveResolution {
    var effective = if (persisted != null && source !in SETTINGS_SAVE_SOURCES) {
        mergeNonSettingsSave(
            persisted = persisted,
            draft = draft,
            allowWeatherSourceChange = source == "popup" || source == "location-picker",
            allowActualsProvidersChange = source == "observations-window" || source == "observations",
        )
    } else {
        draft
    }
    val beforeResnap = effective
    if (persisted != null) effective = resnapNarrowZoomAfterSpanChange(persisted, effective)

    return DesktopConfigSaveResolution(
        config = effective,
        settingsChanges = persisted?.let(effective::settingsDiffFrom).orEmpty(),
        mergedAwaySettings = draft.settingsDiffFrom(effective),
        zoomFactorBeforeResnap = beforeResnap.zoomFactor.takeIf { it != effective.zoomFactor },
    )
}

/** Persists a pending Settings draft without rewinding newer popup-owned fields. */
internal fun flushSettingsDraft(
    persistedConfig: DesktopConfig?,
    draft: DesktopConfig?,
    onSave: (DesktopConfig) -> Unit,
) {
    if (draft == null) return
    if (persistedConfig == null) {
        onSave(draft)
        return
    }
    val merged = persistedConfig.withSettingsFrom(draft)
    if (merged != persistedConfig) onSave(merged)
}

/** Re-snaps an active NARROW zoom when its configured span changes. */
internal fun resnapNarrowZoomAfterSpanChange(prev: DesktopConfig, next: DesktopConfig): DesktopConfig {
    if (prev.settings.narrowZoomSpanHours == next.settings.narrowZoomSpanHours) return next
    val currentStage = ZoomStage.nearestByTotalSpan(
        DesktopGraphUtils.totalSpanHoursFor(prev.zoomFactor),
        prev.settings.narrowZoomSpanHours,
    )
    if (currentStage != ZoomStage.NARROW) return next
    val newFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, next.settings.narrowZoomSpanHours)
    return if (newFactor == next.zoomFactor) next else next.copy(zoomFactor = newFactor)
}

private val SETTINGS_SAVE_SOURCES = setOf("settings", "settings-close")

/**
 * Heals a persisted config whose `zoomFactor` was left at an old NARROW-stage factor when
 * `narrowZoomSpanHours` changed (the pre-resnap bug). The graph renders its window purely from
 * `zoomFactor`, so `narrowZoomSpanHours = 6` with a factor that renders 4 h showed a 4 h view.
 *
 * Precise by design: it only fires when the stored factor is *exactly* the NARROW factor for the
 * span it renders (4..8 h) — i.e. the user clicked into NARROW and the setting was changed out from
 * under it. Continuous wheel positions (arbitrary floats near the narrow band) are left untouched,
 * so a user who deliberately zoomed to, say, 7 h is not yanked to the configured span on restart.
 * The 4..8 span factors are exactly the values `zoomFactorForStage(NARROW, s)` produces for each
 * setting, so exact `Float` equality against a freshly computed factor is stable across the JSON
 * round-trip (shortest-round-trip float encoding).
 */
internal fun repairStaleNarrowZoomFactor(config: DesktopConfig): DesktopConfig {
    val configured = config.settings.narrowZoomSpanHours
    val renderedTotal = DesktopGraphUtils.totalSpanHoursFor(config.zoomFactor)
    if (renderedTotal !in HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS) {
        return config
    }
    if (renderedTotal == configured) return config
    // Only a factor that IS the NARROW factor for its rendered span is a stale click-to-NARROW
    // value; anything else is a continuous zoom position we must not disturb.
    if (config.zoomFactor != DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, renderedTotal)) {
        return config
    }
    return config.copy(
        zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, configured),
    )
}

class DesktopConfigStore(
    private val configPath: Path = defaultConfigPath(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
    private val missingUnitDefault: () -> Boolean = { desktopDefaultUseCelsius() },
) {
    fun load(): DesktopConfig? {
        if (!configPath.exists()) return null
        return runCatching {
            val text = configPath.readText()
            val migrated = migrateFlatSettingsToNested(text)
            val migratedRoot = json.parseToJsonElement(migrated).jsonObject
            val unitWasExplicit = migratedRoot["settings"]?.jsonObject
                ?.containsKey("useCelsius") == true
            val decodedRaw = json.decodeFromString<DesktopConfig>(migrated)
            val decoded = if (unitWasExplicit) {
                decodedRaw
            } else {
                decodedRaw.copy(settings = decodedRaw.settings.copy(useCelsius = missingUnitDefault()))
            }
            var normalizedVisible = WeatherSourceOrdering.sanitizeVisibleIds(decoded.settings.visibleSources)
            if (WeatherSource.OPEN_WEATHER_MAP.id in normalizedVisible && normalizedVisible.last() != WeatherSource.OPEN_WEATHER_MAP.id) {
                normalizedVisible = normalizedVisible.filter { it != WeatherSource.OPEN_WEATHER_MAP.id } + WeatherSource.OPEN_WEATHER_MAP.id
            }
            val normalizedSource = decoded.settings.weatherSource.takeIf { it in normalizedVisible }
                ?: normalizedVisible.first()
            var normalized = decoded.copy(
                settings = decoded.settings.copy(
                    weatherSource = normalizedSource,
                    visibleSources = normalizedVisible,
                ),
            )
            // Heal configs written before the save-time re-snap existed: a stale NARROW factor
            // makes the view render the wrong number of hours for the configured span.
            normalized = repairStaleNarrowZoomFactor(normalized)
            // Re-save when the values changed OR the format was migrated flat → nested, so the
            // file converges to the nested schema instead of re-migrating on every launch.
            if (normalized != decoded || migrated != text || !unitWasExplicit) save(normalized)
            normalized
        }.getOrNull()
    }

    fun save(config: DesktopConfig) {
        var normalizedVisible = WeatherSourceOrdering.sanitizeVisibleIds(config.settings.visibleSources)
        if (WeatherSource.OPEN_WEATHER_MAP.id in normalizedVisible && normalizedVisible.last() != WeatherSource.OPEN_WEATHER_MAP.id) {
            normalizedVisible = normalizedVisible.filter { it != WeatherSource.OPEN_WEATHER_MAP.id } + WeatherSource.OPEN_WEATHER_MAP.id
        }
        val normalized = config.copy(
            settings = config.settings.copy(
                weatherSource = config.settings.weatherSource.takeIf { it in normalizedVisible }
                    ?: normalizedVisible.first(),
                visibleSources = normalizedVisible,
            ),
        )
        configPath.parent?.createDirectories()
        // `Json.encodeDefaults=false` is useful for the rest of this human-edited file, but unit
        // choice is process-shared state, not a safe default: the daemon and UI can have different
        // locales. Always materialize this one field so both processes decode the same boolean.
        val encoded = json.encodeToJsonElement(DesktopConfig.serializer(), normalized).jsonObject
        val encodedSettings = encoded["settings"]?.jsonObject
        val explicitSettings = buildJsonObject {
            encodedSettings?.forEach { (key, value) -> put(key, value) }
            put("useCelsius", normalized.settings.useCelsius)
        }
        val explicitConfig = buildJsonObject {
            encoded.forEach { (key, value) -> if (key != "settings") put(key, value) }
            put("settings", explicitSettings)
        }
        configPath.writeText(json.encodeToString(JsonElement.serializer(), explicitConfig))
    }

    /**
     * One-time migration: configs written before Phase 6 stored the settings fields FLAT alongside
     * the other fields. Nest them under `settings` so [DesktopConfig] can decode them. Returns the
     * original text unchanged when it is already nested or has no flat settings keys.
     */
    private fun migrateFlatSettingsToNested(text: String): String {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return text
        if (root.containsKey("settings")) return text
        val settingsKeys = setOf(
            "weatherSource", "visibleSources", "apiKeys", "narrowZoomSpanHours",
            "multiDayZoomEnabled", "personalStationDiscount", "useCelsius",
            "todayOverlayDelta", "todayOverlayDominantTemp", "todayOverlayDominantAge",
        )
        val flatKeys = settingsKeys.filter { root.containsKey(it) }
        if (flatKeys.isEmpty()) return text
        val settings = buildJsonObject {
            flatKeys.forEach { key -> root[key]?.let { put(key, it) } }
        }
        val migrated = buildJsonObject {
            root.forEach { (key, value) -> if (key !in settingsKeys) put(key, value) }
            put("settings", settings)
        }
        return json.encodeToString(JsonElement.serializer(), migrated)
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

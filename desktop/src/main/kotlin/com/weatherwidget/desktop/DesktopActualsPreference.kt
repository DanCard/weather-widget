package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver

/**
 * Desktop's binding for [ActualsProviderResolver]'s preference seam — the counterpart of Android's
 * `WeatherWidgetApp.onCreate` install, which reads `WidgetStateManager.getActualsProvider`.
 *
 * Nine call sites across the blend ask "which feed supplies this source's actuals?". Without this
 * the desktop answer was always [ActualsProviderResolver.DEFAULT_PROVIDER], so a per-source choice
 * simply did not exist on this platform.
 *
 * **Why a volatile snapshot rather than reading the config file.** The lookup is called from inside
 * the blend, which runs per render; `DesktopConfigStore.load()` is a filesystem read and a JSON
 * parse. Nine of those per paint is exactly the kind of idle cost this app has already had to hunt
 * down once. Both processes push their settings in whenever config changes instead.
 *
 * Storing the DEFAULT provider is deliberately not the same as storing nothing: an absent entry
 * follows the default if the default ever moves, a stored one pins the user to today's answer. The
 * chooser writes an absent entry for the default, matching Android.
 */
object DesktopActualsPreference {

    @Volatile
    private var settings: DesktopSettings? = null

    /** Wire the shared resolver to this holder. Idempotent; safe to call from either process. */
    fun install() {
        ActualsProviderResolver.installPreferenceSource { source -> lookup(source) }
    }

    /** Publish the latest settings. Call on load and after every save. */
    fun update(latest: DesktopSettings?) {
        settings = latest
    }

    /**
     * The stored provider for [source], or null to mean "use the default".
     *
     * An unknown id (hand-edited config, or a provider removed from a later build) resolves to null
     * rather than throwing — and `canProvide` is re-checked here because a source can stop being a
     * valid actuals provider between the write and the read, which is precisely what happened when
     * Synoptic was disabled.
     */
    fun lookup(source: WeatherSource): WeatherSource? =
        settings?.actualsProviders?.get(source.id)
            ?.let { stored -> WeatherSource.entries.firstOrNull { it.id == stored } }
            ?.takeIf { ActualsProviderResolver.canProvide(it) }

    /** The settings map after choosing [provider] for [source]; null [provider] restores the default. */
    fun withChoice(
        current: DesktopSettings,
        source: WeatherSource,
        provider: WeatherSource?,
    ): DesktopSettings {
        val updated = current.actualsProviders.toMutableMap()
        if (provider == null) updated.remove(source.id) else updated[source.id] = provider.id
        return current.copy(actualsProviders = updated)
    }
}

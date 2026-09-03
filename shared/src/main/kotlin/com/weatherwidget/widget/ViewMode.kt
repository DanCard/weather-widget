package com.weatherwidget.widget

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Widget view mode, shared by the Android widget and the desktop app.
 *
 * [TEMPERATURE] is the hourly temperature graph. Desktop historically serialized this as `"HOURLY"`
 * in config.json; that legacy value is migrated to `"TEMPERATURE"` on load (see
 * `DesktopConfigStore.migrateLegacyHourlyViewMode`), so the canonical member set has no `HOURLY`.
 * Member order matches Android's persisted ordinals — do not reorder.
 */
@Serializable
enum class ViewMode {
    @SerialName("DAILY") DAILY,
    @SerialName("TEMPERATURE") TEMPERATURE,
    @SerialName("PRECIPITATION") PRECIPITATION,
    @SerialName("CLOUD_COVER") CLOUD_COVER;

    /** True for any graph view (everything except [DAILY]). */
    val isGraphMode: Boolean get() = this != DAILY

    /** Desktop's historical name for [isGraphMode]; kept for call-site compatibility. */
    val isHourly: Boolean get() = this != DAILY

    companion object {
        /** Android persisted-name parsing; falls back to [default] for unknown/blank names. */
        fun parseOrDefault(name: String?, default: ViewMode): ViewMode =
            if (name.isNullOrBlank()) default else entries.find { it.name == name } ?: default
    }
}

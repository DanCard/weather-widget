package com.weatherwidget.shared.settings

/** The two Settings screens. */
enum class Platform { ANDROID, DESKTOP }

/**
 * The catalogue of Settings sections: **declaration order is the on-screen order**, on both
 * platforms.
 *
 * The screens cannot share UI — Android is XML/Views, the desktop is Compose, and `:shared` is pure
 * JVM — and they do not try. What they *can* share is the definition of what a Settings screen
 * contains, in what order, under what title. Those three things drifted independently twice
 * (Notifications/Personal Stations swapped; API Keys under Sources on one side and after Feedback on
 * the other; "Icon Gallery" vs "Icon gallery"; a desktop-only Diagnostics card nobody had decided
 * on), each time fixed by hand against a screenshot. This enum is the same remedy
 * [com.weatherwidget.shared.util.WeatherSourceOrdering.ALL_CONFIGURABLE] applies to the sources list.
 *
 * Each platform's Settings test asserts its rendered section titles equal [forPlatform] in order,
 * so adding a section to one screen without adding it here — and therefore without deciding
 * whether the other screen gets it — fails a test rather than waiting to be noticed.
 *
 * [title] is the English text. Android's `values/strings.xml` must carry it verbatim (its own
 * test checks under the default locale); other locales translate. Descriptions are deliberately
 * NOT here — several are platform-specific by nature ("Widget Location: … • Follows device" only
 * means something on Android).
 */
enum class SettingsSection(
    val title: String,
    /** Which platforms show it. A one-sided section is a declaration here, never an accident. */
    val platforms: Set<Platform> = setOf(Platform.ANDROID, Platform.DESKTOP),
) {
    DEFAULT_LOCATION("Default Location"),
    HOURLY_ZOOM("Hourly Zoom"),
    NOTIFICATIONS("Notifications"),
    UNITS("Units"),
    TODAY_COLUMN("Daily View — Today Column"),
    PERSONAL_STATIONS("Personal Weather Stations"),
    WEATHER_SOURCES("Weather Data Sources"),
    ICON_GALLERY("Icon gallery"),
    /** The desktop ships no translations (user-facing text cannot live in `:shared`), so no picker. */
    LANGUAGE("Language", platforms = setOf(Platform.ANDROID)),
    FEEDBACK("Feedback & Bug Reports"),
    API_KEYS("API Keys"),
    SUPPORT("Support Development"),
    DATA_USAGE("Data Usage"),
    ;

    companion object {
        /** The sections [platform] shows, in on-screen order. */
        fun forPlatform(platform: Platform): List<SettingsSection> = entries.filter { platform in it.platforms }
    }
}

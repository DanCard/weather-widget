# Shared settings-section catalog: one definition of order, titles and platform membership

*2026-09-12* — follow-up to `260912-desktop-settings-parity-round-two.md`

## Why

The two Settings screens cannot share UI (`:shared` is pure JVM; Android is XML/Views, desktop is
Compose), and they should not try. What drifted — and what the second parity pass had to fix by
hand — was *definition*: section order (API Keys under Sources on desktop, after Feedback on
Android), titles ("Icon Gallery" vs "Icon gallery", "Location" vs "Default Location", "Feedback"
vs "Feedback & Bug Reports") and membership (a desktop-only Diagnostics card nobody had decided
on). All three are data, and data belongs in `:shared`, the way `WeatherSourceOrdering.ALL_CONFIGURABLE`
already owns the sources list that both screens render ("Mirrors `SettingsActivity.allSources` and
`SettingsWindow.ApiSourcesList.allSources`").

Today the desktop pins its order in `SettingsWindowSectionsTest.ANDROID_SECTION_ORDER` — a local
copy of Android's XML. Drift fails on one side only.

## Design

```kotlin
// shared/.../shared/settings/SettingsSection.kt
enum class SettingsSection(
    /** English title; Android's values/strings.xml must equal this (tested), other locales translate it. */
    val title: String,
    /** Which platforms show it. A one-sided section is a declaration here, not an accident. */
    val platforms: Set<Platform> = setOf(Platform.ANDROID, Platform.DESKTOP),
) {
    HOURLY_ZOOM("Hourly Zoom"),
    NOTIFICATIONS("Notifications"),
    UNITS("Units"),
    TODAY_COLUMN("Daily View — Today Column"),
    PERSONAL_STATIONS("Personal Weather Stations"),
    WEATHER_SOURCES("Weather Data Sources"),
    ICON_GALLERY("Icon gallery"),
    DEFAULT_LOCATION("Default Location"),
    LANGUAGE("Language", platforms = setOf(Platform.ANDROID)),   // desktop has no translations
    FEEDBACK("Feedback & Bug Reports"),
    API_KEYS("API Keys"),
    SUPPORT("Support Development");

    companion object {
        fun forPlatform(p: Platform): List<SettingsSection> = entries.filter { p in it.platforms }
    }
}
enum class Platform { ANDROID, DESKTOP }
```

Enum declaration order **is** the canonical on-screen order — no separate list to keep in step.

### Consumers

| Platform | Use |
|---|---|
| Desktop `SettingsWindow` | `SettingsCard(title = SettingsSection.X.title)` for every card; cards emitted by iterating `forPlatform(DESKTOP)` is tempting but over-engineered — each card has bespoke content, so keep explicit cards and let the **test** enforce order. |
| Desktop `SettingsWindowSectionsTest` | replace `ANDROID_SECTION_ORDER` with `SettingsSection.forPlatform(DESKTOP).map { it.title }`. |
| Android `SettingsActivity` / layout | titles stay in `strings.xml` (localisation). New Robolectric test walks `activity_settings.xml`'s section-header `TextView`s top-to-bottom and asserts their `text` equals `forPlatform(ANDROID).map { it.title }` in order, under the default locale. Header views need a stable marker — a `tag="settings_section"` attribute on each title `TextView` (one XML edit per section). |
| `:shared` unit test | English titles are non-blank and unique; `forPlatform` never returns an empty list. |

### What this does not do

- No shared composables / views. The screens keep rendering their own way.
- No per-section content model. Content (sliders, lists, buttons) stays platform code; the
  `WeatherSourceOrdering`/`ActualsProviderResolver`/`ApiKeySignupUrls` logic they call is already
  shared and is the pattern for anything new.
- Descriptions are *not* moved: several are platform-specific by nature ("Widget Location: … •
  Follows device" only means something on Android). Titles and order are the drift that mattered.

## Files

| File | Change |
|---|---|
| `shared/.../shared/settings/SettingsSection.kt` | new enum + `Platform` |
| `shared/src/test/.../SettingsSectionTest.kt` | new |
| `desktop/.../SettingsWindow.kt` | titles from the enum |
| `desktop/src/test/.../SettingsWindowSectionsTest.kt` | order from the enum |
| `app/src/main/res/layout/activity_settings.xml` | `android:tag="settings_section"` on the 12 title views |
| `app/src/test/.../SettingsSectionOrderRoboTest.kt` | new; Robolectric, default locale |

## Effort / risk

~1 hour. Purely additive on Android (a tag attribute and a test); title-string refactor on desktop
with the existing 41 desktop Settings tests as the net.

## Verification (done 2026-09-12)

- `SettingsSectionTest` 3/3, `SettingsWindowSectionsTest` 5/5, `SettingsSectionOrderRoboTest` 1/1.
- Proved the guard bites: swapping `API_KEYS`/`SUPPORT` in the enum failed the desktop order test
  (`"Support Development" should precede "API Keys"`); restored.
- Desktop rebuilt and restarted on the enum-titled build.

# Enable OpenWeatherMap everywhere; gate sources on the *effective* key, not a static flag

*2026-09-12*

## Request

Enable OpenWeatherMap on all attached Android devices and the desktop, and address the two
inconsistencies found while checking its status:

1. `WeatherSource.OPEN_WEATHER_MAP.requiresUserEnteredKey = true` although both platforms bake a
   build-time key for it — Settings refuses to enable it until the user re-types a key they already
   have.
2. `AGENTS.md` still calls OpenWeatherMap "hidden/deprecated"; it has been configurable and on the
   free 2.5 endpoints since 2026-08-23.

## Root cause of (1)

`requiresUserEnteredKey` is a **static enum property** answering a question that is only knowable
at runtime: *is there a key available from anywhere for this source in this build?* The doc on the
flag says it is false "only for SILURIAN, which has a build-time key fallback" — but OWM,
WeatherAPI and Tomorrow.io all gained build-time fallbacks since (`app/build.gradle.kts:135-139`,
`desktop/build.gradle.kts` `generateDesktopApiKeys`), and the flag was never revisited. Flipping it
to `false` for OWM would be wrong in the other direction: a `-PpublicBuild` desktop or a build
without `local.properties` bakes nothing, and the source would then enable and fail with
`OPEN_WEATHER_MAP_API_KEY is missing`.

## Fix

Replace the static gate with the effective key on each platform, and delete the flag.

| Where | Before | After |
|---|---|---|
| `WeatherSource` | `requiresUserEnteredKey` | removed; `requiresApiKey` is the only static fact |
| Android `SettingsActivity` toggle | `source.requiresUserEnteredKey && getApiKey(source).isBlank` | `source.requiresApiKey && !BuiltInApiKeys.hasEffectiveKey(source, widgetStateManager)` |
| Android (new) `BuiltInApiKeys` | — | maps each keyed source to its `BuildConfig.*_API_KEY`; `effectiveKey(source, user)` = user key else baked key. The per-source `?: BuildConfig.X` fallbacks in `AppModule` already encode this; the new object is the one place Settings can ask the same question. |
| Desktop `SettingsWindow` toggle (checkbox + name click) | `requiresUserEnteredKey && apiKeys[id].isBlank` | `requiresApiKey && apiKeys[id].isBlank && DesktopApiKeys.DEFAULTS[id].isBlank` |
| `ApiKeySignupUrlsTest` | asserts the flag per source | asserts `requiresApiKey` per source (the fact that remains) |
| `ApiKeySignupUrls` doc | mentions the flag | mentions `requiresApiKey` |
| `AGENTS.md` | "Visual Crossing and OpenWeatherMap … hidden/deprecated" | OWM listed as configurable, not default-visible, free 2.5 endpoints; Visual Crossing alone deprecated |

Behavioural consequence: on this machine's builds (keys baked) all four keyed sources enable without
typing a key; on a public build the gate still asks for one — and now also for Silurian, which the
old flag exempted unconditionally.

## Enabling on the attached devices

Four Android devices (`2A191FDH300PPW` Pixel 7 Pro, `RFCT71FR9NT` Fold, `emulator-5554`,
`emulator-5556`) all have `visible_sources_order = NWS,OPEN_METEO,SILURIAN,TOMORROW_IO` in
`shared_prefs/widget_state_prefs.xml`. Desktop `config.json` has the same four.

- Android: `./gradlew installDebug` (all connected), then per device: force-stop, append
  `,OPEN_WEATHER_MAP` to the pref via `run-as`, `am start` the settings activity so the process is
  not left in the stopped state (see memory `force_stop_leaves_stopped_state_needs_am_start`).
  The existing bottom-of-list migration keeps OWM last.
- Desktop: `.quit` the running app, add `OPEN_WEATHER_MAP` to `settings.visibleSources` in
  `config.json`, restart with `scripts/buildStart-desktop.sh` (which also picks up the gate fix).

## Verification (done 2026-09-12)

- `ApiKeySignupUrlsTest` 4/4; `:desktop:compileKotlin`, `:app:compileDebugKotlin` clean.
- `installDebug` → 4 devices; each `widget_state_prefs.xml` now
  `NWS,OPEN_METEO,SILURIAN,TOMORROW_IO,OPEN_WEATHER_MAP`; emulator-5554 Settings shows
  OpenWeatherMap checked and last. Refresh Data on emulator-5554 →
  `CURR_FETCH_SOURCE_RESULT source=OPEN_WEATHER_MAP success=true temp=60.58` with no user key entered
  (baked key path), `NET_FETCH_COMPLETE sources=…,OPEN_WEATHER_MAP`.
- Desktop: `REFRESH source=OPEN_WEATHER_MAP hourly=118 daily=6` at 08:02:45; `hourly_forecasts`
  OWM rows 127 → 245 (previous newest 2026-08-23).
- Also fixed on the way: `AppModule` and `WeatherApiCredentialProvider` now resolve keys through
  `BuiltInApiKeys`, so the fetch path and the Settings gate share one definition.

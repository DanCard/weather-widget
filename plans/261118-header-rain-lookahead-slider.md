# Unified `DisplaySettings` + Header Rain Lookahead Slider (1–8 Hours)

## Context & Goal

1. The rain probability percentage shown in the widget header (daily view and all hourly views) is currently hardcoded to a 6-hour lookahead via `PrecipProbabilityCalculator.VISIBLE_LOOKAHEAD_HOURS = 6L`, called from `HeaderContent.precipitation`.
2. Across Android (`WeatherDisplayPreferences` + `WidgetStateManager` + `SettingsActivity`) and Desktop (`DesktopSettings` + `SettingsWindow`), display settings (~10 fields including 4 integer sliders) currently duplicate defaults, min/max bounds, clamping, diffing, and slider widget boilerplate.

This change:
- Unifies shared display settings and slider bounds into a single `:shared` model (`DisplaySettings` + `IntSliderSpec`), preserving 100% compatibility with existing Android `SharedPreferences` keys and Desktop `config.json` flat JSON fields.
- Adds a **Header** settings section (directly above **Experimental**) on both Android and Desktop with a **1–8 hour** rain lookahead slider (default **6 hours**) applying to both daily and hourly headers.

## Key Design Decisions

1. **Shared `DisplaySettings` + `IntSliderSpec` (`:shared`):**
   - `IntSliderSpec(min, max, default)` owns clamping plus both Android `SeekBar` math (`seekBarMax`, `toSeekBarProgress`, `fromSeekBarProgress`) and Desktop Compose `Slider` math (`composeRange`, `composeSteps`).
   - `DisplaySettings` holds all cross-platform display settings (`narrowZoomSpanHours`, `multiDayZoomEnabled`, `personalStationDiscount`, `hindcastMaxPriorAgeHours`, `headerRainLookaheadHours`, `useCelsius`, `todayOverlayDelta`, `todayOverlayDominantTemp`, `showDailyZoomOutButton`, `showFuturePriorForecast`), their canonical defaults, `sanitized()`, `personalStationWeight()`, and `diffFrom(other)`.
   - Zero storage migration: Android keeps its existing `SharedPreferences` keys (with `WeatherDisplayPreferences.snapshot(): DisplaySettings`), and Desktop keeps its flat `DesktopSettings` JSON fields (delegating defaults, `personalStationWeight()`, and display-field `diffFrom()` to `DisplaySettings`).
2. **Scope — Daily + Hourly headers:** Both the daily header (lookahead from `now`) and hourly headers (lookahead from `centerTime`) use `headerRainLookaheadHours`.
3. **Today-tap routing stays at 6h:** `DayClickResolver.TODAY_LOOKAHEAD_HOURS` stays fixed at `PrecipProbabilityCalculator.VISIBLE_LOOKAHEAD_HOURS` (6h), matching `ZoomStage.WIDE`'s forward span.
4. **Settings placement:** Add `SettingsSection.HEADER("Header")` directly before `SettingsSection.EXPERIMENTAL("Experimental")` in `shared/.../SettingsSection.kt`. Both `SettingsSectionOrderRoboTest` (Android) and `SettingsWindowSectionsTest` (Desktop) enforce identical order and titles.
5. **No DB migration or API change:** Hourly forecasts are already fetched and queried well past 8 hours on both platforms. On Desktop, `DesktopWeatherRepository` queries `allSourcesHourly` using `DisplaySettings.HEADER_RAIN_LOOKAHEAD.max` (8h → +9h reserve window) so moving the slider updates the header immediately without needing a snapshot reload.

## Proposed Changes

### 1. `:shared`
- **`shared/src/main/kotlin/com/weatherwidget/shared/settings/DisplaySettings.kt`** (new):
  - `IntSliderSpec` (min, max, default, `clamp`, `seekBarMax`, `toSeekBarProgress`, `fromSeekBarProgress`, `composeRange`, `composeSteps`).
  - `@Serializable data class DisplaySettings` with canonical `IntSliderSpec` constants (`NARROW_ZOOM_SPAN`, `PERSONAL_STATION_DISCOUNT`, `HINDCAST_MAX_PRIOR_AGE`, `HEADER_RAIN_LOOKAHEAD`), `sanitized()`, `personalStationWeight()`, and `diffFrom(other)`.
- **`HeaderContent.kt`**:
  - Add `rainLookaheadHours: Int = DisplaySettings.HEADER_RAIN_LOOKAHEAD.default` to `HeaderContent.build(...)` and `HeaderContent.precipLabels(...)`, clamping via `DisplaySettings.HEADER_RAIN_LOOKAHEAD.clamp` and calling `PrecipProbabilityCalculator.maxPrecipProbabilityWithin`.
  - Parameterize `HeaderContent.precipReserveWindowMs(..., lookaheadHours: Long = PrecipProbabilityCalculator.VISIBLE_LOOKAHEAD_HOURS)` so the end boundary is `lookaheadHours + 1L`.
- **`SettingsSection.kt`**:
  - Add `HEADER("Header")` directly above `EXPERIMENTAL("Experimental")`.

### 2. `:app` (Android)
- **`WeatherDisplayPreferences.kt` & `WidgetStateManager.kt`**:
  - Use `DisplaySettings` specs for defaults and clamping across all display preferences.
  - Add `KEY_HEADER_RAIN_LOOKAHEAD_HOURS = "header_rain_lookahead_hours"` (`headerRainLookaheadHours()` / `setHeaderRainLookaheadHours(hours: Int)`), `snapshot(): DisplaySettings`, and expose `getHeaderRainLookaheadHours()` / `setHeaderRainLookaheadHours(hours: Int)` on `WidgetStateManager`.
- **Android header builders**:
  - Pass `stateManager.getHeaderRainLookaheadHours()` into `HeaderContent.build(...)` in:
    - `DailyHeaderResolver.kt`
    - `TemperatureHeaderStateBuilder.kt` (via `TemperatureViewHandler` / `TemperatureStateBuilder`)
    - `PrecipViewHandler.kt`
    - `CloudCoverViewHandler.kt`
- **Settings UI**:
  - `strings.xml`: add `header_settings_title` (`"Header"`), `header_rain_lookahead_description`, `header_rain_lookahead_value_singular` (`"1 hour"`), `header_rain_lookahead_value` (`"%1$d hours"`).
  - `activity_settings.xml`: add the **Header** section (`android:tag="settings_section"`) directly above **Experimental**, with description, bold value label, and `SeekBar`.
  - `SettingsActivity.kt`: add a reusable `bindSlider(...)` helper backed by `IntSliderSpec` used by `setupHourlyZoomSpan`, `setupPersonalStationDiscount`, `setupHeaderRainLookahead`, and `setupHindcastGuard`.

### 3. `:desktop`
- **`DesktopConfig.kt` & `BugReportMailto.kt`**:
  - Default `DesktopSettings` fields from `DisplaySettings`, add `headerRainLookaheadHours: Int = DisplaySettings.HEADER_RAIN_LOOKAHEAD.default`, add `toDisplaySettings(): DisplaySettings`, delegate `personalStationWeight()` and display-field `diffFrom()` to `DisplaySettings`, and include `headerRainLookaheadHours` in `BugReportMailto.kt`.
- **`DesktopWidgetHeader.kt`**:
  - Pass `config.settings.headerRainLookaheadHours` into `desktopHeaderContent(...)` -> `HeaderContent.build(...)` and include it in the `remember(...)` keys.
- **`DesktopWeatherRepository.kt`**:
  - Pass `DisplaySettings.HEADER_RAIN_LOOKAHEAD.max.toLong()` to `HeaderContent.precipReserveWindowMs(...)`.
- **`SettingsWindow.kt`**:
  - Add a reusable `@Composable IntSliderSetting(spec: IntSliderSpec, ...)` helper used by `HourlyZoomSpan`, `PersonalStationDiscount`, `HeaderRainLookaheadSlider`, and `HindcastGuardSlider`.
  - Add `SettingsCard(title = SettingsSection.HEADER.title)` directly above `SettingsSection.EXPERIMENTAL`.

### 4. Living Docs
- Update `arch/ARCHITECTURE.md` to document `DisplaySettings` / `IntSliderSpec` and the configurable 1–8h header rain lookahead (default 6h, shared via `HeaderContent`).

## Verification Plan

1. **Unit & Integration Tests**:
   - `:shared`: `DisplaySettingsTest` (specs, clamping, SeekBar/Compose math, `personalStationWeight`, `diffFrom`, `sanitized`), `HeaderContentTest` (1h / 6h / 8h lookahead in both `DAILY` and hourly views, `precipLabels`, `precipReserveWindowMs(lookaheadHours = 8)`).
   - `:app`: `WeatherDisplayPreferences` round-trip/clamping & `snapshot()` test, `SettingsSectionOrderRoboTest` (verifying `"Header"` sits right above `"Experimental"`), and `HardcodedUserFacingStringTest` (allowlist `"Header"` if needed).
   - `:desktop`: `DesktopSettings` default/diff/JSON round-trip, `SettingsWindowSectionsTest`, and a `DesktopWidgetHeader` / `desktopHeaderContent` test verifying changing `headerRainLookaheadHours` changes the displayed rain %.
2. **Full Cross-Platform Gate & Runtime Check**:
   - Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.
   - Verify the new **Header** card above **Experimental** in Settings on Android emulator / desktop app and confirm the slider updates the header rain % cleanly.

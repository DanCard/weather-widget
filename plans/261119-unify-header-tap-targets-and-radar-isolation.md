# Plan: Unify Header Tap Targets Across Android & Desktop and Isolate Desktop Radar Cache in Tests

**Date:** 2026-11-19
**Status:** Completed

## Evidence & Root Cause

While wiring the active rain-now header icon to open the hourly precipitation view (commit `550909e03`), four concrete inconsistencies surfaced between Android and Desktop:

1. **Header rain `%` tap when already on `PRECIPITATION`:**
   - **Android** (`WidgetPresentationStateStore.togglePrecipitationMode`): `ACTION_TOGGLE_PRECIP` toggles `PRECIPITATION` → `DAILY` (and `DAILY`/`TEMPERATURE`/`CLOUD_COVER` → `PRECIPITATION`).
   - **Desktop** (`DesktopWidgetHeader.kt`): clicking `precipText` unconditionally sets `ViewMode.PRECIPITATION` (a no-op when already in `PRECIPITATION`), diverging from both Android and the new active rain-now icon click.
2. **Non-rain-now weather icon tap in hourly views (`PRECIPITATION`, `CLOUD_COVER`):**
   - **Android**: `DailyViewHandler` → `TEMPERATURE`, `TemperatureViewBinder` → `DAILY`, `PrecipViewHandler` → `CLOUD_COVER`, `CloudCoverViewHandler` → `TEMPERATURE` (leftover wiring from before the header graph-selector button existed).
   - **Desktop** (`DesktopWidgetHeader.kt`): `DAILY` → `TEMPERATURE`, and *any* hourly view (`TEMPERATURE`, `PRECIPITATION`, `CLOUD_COVER`) → `DAILY`.
3. **Header yesterday-delta (`+1.2` / `from yest`) tap:**
   - **Android** (`widget_weather.xml`, `HeaderTapTargetHelper.kt`): `current_temp`, `current_temp_delta`, and `current_temp_delta_label` all sit inside `current_temp_zone` and toggle `DAILY ↔ TEMPERATURE`.
   - **Desktop** (`DesktopWidgetHeader.kt`): `deltaText` and `DELTA_CAPTION` sit outside `current_temp_toggle` with no `.clickable` modifier, so clicking them falls through to the outer header's `switchSourceOnPress` and cycles the weather source instead.
4. **Desktop test isolation from live `~/.cache/weather-widget/radar-now.txt`:**
   - `DesktopRadarNow.store` reads `$XDG_CACHE_HOME/weather-widget/radar-now.txt` (defaulting to `~/.cache/weather-widget/radar-now.txt`).
   - Because `desktop/build.gradle.kts` does not isolate `XDG_CACHE_HOME` for `Test` tasks, any desktop Compose test using Mountain View coordinates (`stubConfig`) reads live radar samples written by the local desktop daemon. During active rain, `testNonRainWeatherIconClickOpensTemperatureGraph` picked up the live rain-now radar pixel and saw active rain.

## Proposed Fix

### 1. Single shared `HeaderTapRouting` on `ViewMode` in `:shared`

`com.weatherwidget.widget.ViewMode` already lives in `:shared` (just like `GraphSelector`). Rather than adding routing helpers on `HeaderContent.View` and mapping back and forth to `ViewMode` in both platforms, define one canonical `HeaderTapRouting` object in `:shared` (in `shared/src/main/kotlin/com/weatherwidget/shared/util/HeaderTapRouting.kt`) operating directly on `ViewMode`:

```kotlin
object HeaderTapRouting {
    enum class Zone { ICON, TEMP_AND_DELTA, PRECIP }

    fun target(zone: Zone, current: ViewMode, isRainingNow: Boolean = false): ViewMode = when (zone) {
        Zone.TEMP_AND_DELTA -> if (current == ViewMode.DAILY) ViewMode.TEMPERATURE else ViewMode.DAILY
        Zone.PRECIP -> if (current == ViewMode.PRECIPITATION) ViewMode.DAILY else ViewMode.PRECIPITATION
        Zone.ICON -> if (isRainingNow) target(Zone.PRECIP, current) else target(Zone.TEMP_AND_DELTA, current)
    }
}
```

- Remove the narrower `HeaderContent.rainNowTapTarget(HeaderContent.View)` helper added in `550909e03` so there is only one shared routing rule and zero `HeaderContent.View ↔ ViewMode` conversion glue.
- *(Alternative if preferred for `Zone.ICON` when `!isRainingNow`: keep Android's `PRECIPITATION → CLOUD_COVER` and `CLOUD_COVER → TEMPERATURE` cycling inside `HeaderTapRouting.target` and share it with Desktop.)*

### 2. Wire Android to `HeaderTapRouting` (eliminating duplicate transition logic)

- **`WidgetPresentationStateStore.kt`**:
  - `toggleViewMode` computes `newMode = HeaderTapRouting.target(HeaderTapRouting.Zone.TEMP_AND_DELTA, current)` instead of duplicating `if (current == ViewMode.DAILY) ...`.
  - `togglePrecipitationMode` computes `newMode = HeaderTapRouting.target(HeaderTapRouting.Zone.PRECIP, current)` instead of duplicating `if (current == ViewMode.PRECIPITATION) ...`.
- **`PrecipViewHandler.kt` & `CloudCoverViewHandler.kt`**:
  - Delete the legacy `goCloudPending` / `goTempIconPending` icon-specific overrides (and check if `WidgetRequestCodes.iconViewToggle` is now unused and can be deleted).
  - Call `HeaderTapTargetHelper.bindWeatherIconHeader(context, views, appWidgetId, isRainingNow = content.isRainingNow)` identically across all four views (`Daily`, `Temperature`, `Precipitation`, `CloudCover`).
- **`HeaderTapTargetHelper.kt`**:
  - Simplify `bindWeatherIconHeader` by removing the now-unused `defaultPendingIntent` parameter; it binds `ACTION_TOGGLE_PRECIP` when `isRainingNow` else `ACTION_TOGGLE_VIEW`, both of which execute through `WidgetPresentationStateStore` → `HeaderTapRouting.target`.
- **`widget_weather.xml`**:
  - Move `paddingStart="4dp"` from `weather_icon_touch_zone` onto `current_weather_container` (with `paddingStart="0dp"` on both `weather_icon_touch_zone` and `current_temp_zone`) so `current_temp` keeps its 4 dp leading inset when the icon is hidden (`GONE`).
- **`TemperatureHeaderFit.kt` & `HourlyHeaderBinder.kt`**:
  - Pass `includeIcon = iconRes != 0` instead of hardcoded `includeIcon = true`, matching Desktop's `HeaderFit` measurement when an hourly source has no icon for the current hour.

### 3. Wire Desktop to `HeaderTapRouting` (eliminating ad-hoc branches, mapping glue & dead gaps)

- **`DesktopWidgetHeader.kt`**:
  - Weather icon click: `onUpdateConfig(config.copy(viewMode = HeaderTapRouting.target(HeaderTapRouting.Zone.ICON, config.viewMode, content.isRainingNow)))`, with the `4.dp` end spacing inside the clickable target so there is no source-cycling gap between icon and temp.
  - Temp + yesterday-delta + `"from yest"` caption: wrap all three in one clickable `Row` (`testTag("current_temp_toggle")`) calling `HeaderTapRouting.target(HeaderTapRouting.Zone.TEMP_AND_DELTA, config.viewMode)` so clicking delta/caption or the gap between them toggles `DAILY ↔ TEMPERATURE` instead of falling through to `switchSourceOnPress`.
  - Rain `%` click: call `HeaderTapRouting.target(HeaderTapRouting.Zone.PRECIP, config.viewMode)` (so clicking rain `%` in `PRECIPITATION` toggles back to `DAILY`, matching Android), fold `PRECIP_MARGIN_DP` into the clickable target's start padding, and add `.testTag("header_precip_toggle")`.

### 4. Isolate Desktop tests from live `~/.cache/weather-widget/radar-now.txt`

- In `desktop/build.gradle.kts`, configure `tasks.withType<Test>` with `environment("XDG_CACHE_HOME", layout.buildDirectory.dir("tmp/test-xdg-cache").get().asFile.absolutePath)` so JVM/Compose tests never read the host user's live `~/.cache/weather-widget/radar-now.txt` (or other XDG cache files).
- Simplify the new tests in `DesktopUiTest.kt` to use `stubConfig` directly instead of working around live local radar with New York coordinates.

### 5. Living Documentation & Tests

- **Docs (`arch/ARCHITECTURE.md`):** update the header interaction note to reference `HeaderTapRouting` as the single shared tap-routing rule for icon, temp/delta, and rain `%`.
- **Tests:**
  - `shared`: `HeaderTapRoutingTest` covering all `(Zone, ViewMode, isRainingNow)` combinations.
  - `desktop`: Compose UI tests in `DesktopUiTest` verifying rain `%` toggle (`DAILY ↔ PRECIPITATION`), delta click (`DAILY → TEMPERATURE`, not source cycle), non-rain icon click from `PRECIPITATION`/`CLOUD_COVER` → `DAILY`, and Mountain View `stubConfig` staying dry regardless of host `~/.cache/weather-widget/radar-now.txt`.
  - `app`: Robolectric tests in `WidgetTouchTargetRoutingContractTest` / `CurrentTempTouchRoutingRoboTest` verifying `weather_icon_touch_zone` across views, plus `DailyHeaderRainLabelTapIntegrationTest` and `PrecipTouchZoneInstrumentedTest` on emulator.
- **End-of-task gate:** `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`.

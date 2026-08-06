# Today Column Overlay Toggles (Daily View)

## Summary

Add three user-facing toggle settings for the large-Today-column overlay in the daily forecast
view, on both Android and desktop:

1. **Delta from forecast** — the observed-vs-forecast delta (`deltaValueText` + `fcst` caption).
2. **Dominant station temperature** — the dominant blend station's raw temperature
   (`dominantTempText`).
3. **Dominant temperature reading age** — the age of that reading (`dominantAgeText`).

**All three default OFF.** The overlay texts exist today (plan
`260804-today-column-station-overlay.md`) and render unconditionally whenever the
`LargeTodayOverlayPolicy` size gate passes; this change makes each piece opt-in.

> Behavior change for existing installs: the Today-column overlay currently shows all three texts
> by default. After this change they disappear until the user enables the toggles.

## Settings Storage

### Android (`:app`)

App-global preferences (like `useCelsius`), not per-widget:

- `WeatherDisplayPreferences.kt` — add three keys + getters/setters, all default `false`:
  - `show_today_overlay_delta`
  - `show_today_overlay_dominant_temp`
  - `show_today_overlay_dominant_age`
- `WidgetStateManager.kt` — facade methods:
  - `showTodayOverlayDelta()` / `setShowTodayOverlayDelta(Boolean)`
  - `showTodayOverlayDominantTemp()` / `setShowTodayOverlayDominantTemp(Boolean)`
  - `showTodayOverlayDominantAge()` / `setShowTodayOverlayDominantAge(Boolean)`

### Desktop (`:desktop`)

`DesktopConfig` gains three `@Serializable` fields, all default `false` (omitted from
`config.json` while off, `encodeDefaults` handling already in place):

- `todayOverlayDelta: Boolean = false`
- `todayOverlayDominantTemp: Boolean = false`
- `todayOverlayDominantAge: Boolean = false`

## Shared Gating (`:shared`)

Gate in `TodayColumnOverlayContentResolver` so both platforms get identical semantics and the
"everything off → null content" path comes for free:

- Add three parameters to `resolveLatest` / `resolveAt` (both overloads):
  `showForecastDelta: Boolean = true`, `showDominantStationTemp: Boolean = true`,
  `showDominantReadingAge: Boolean = true` (resolver-side defaults preserve current behavior for
  tests and un-migrated callers; the *product* default-off lives in the settings layers).
- Apply as: `deltaText = deltaText.takeIf { showForecastDelta }`,
  `dominantTempText = ...takeIf { showDominantStationTemp }`,
  `dominantAgeText = ...takeIf { showDominantReadingAge }`.
- Keep `dominantContribution` populated regardless (diagnostics), but null the text fields per
  the flags; return `null` when no visible text remains.

## Renderer Changes (temp-off / age-on combination)

Both renderers currently build the dominant block only when `dominantTempText` is non-blank
(`dominantTempText?.let { rows = listOfNotNull(temp, age) }`). With independent toggles, age
must render without the temperature. Change block construction in:

1. `app/.../widget/TodayColumnOverlayRenderer.kt` (`draw`, ~line 63)
2. `desktop/.../DailyForecastGraph.kt` (`drawDesktopTodayOverlay`, ~line 706)

to: build rows from `listOfNotNull(temp, age)` and emit the `dominant_temp_age` block whenever
that list is non-empty. Placement/planner logic is unchanged.

## Wiring

### Android

- `DailyGraphRenderer.buildTodayOverlayData` (~line 344): read the three settings from
  `ctx.stateManager` and pass them into `TodayColumnOverlayContentResolver.resolveAt`.
  Short-circuit: if all three are off, skip the resolution and return `null` (avoids blend work).
- Add the toggle states to the `TODAY_OVERLAY` `app_logs` row for diagnostics
  (`flags=delta:off,temp:on,age:off`).

### Desktop

- `DesktopDailyForecastModel.buildViewState` (~line 176): same — short-circuit when all off,
  otherwise pass `config.todayOverlay*` into `resolveLatest`. Extend the existing
  `todayOverlay resolve` debug log with the flag states.

## Settings UI

### Android — `activity_settings.xml` + `SettingsActivity.kt`

- New section "Daily View — Today Column" directly below the Units section (mirrors desktop
  card order), containing three `SwitchCompat` rows:
  - `@+id/today_overlay_delta_switch` — "Show delta from forecast"
  - `@+id/today_overlay_dominant_temp_switch` — "Show dominant station temperature"
  - `@+id/today_overlay_dominant_age_switch` — "Show reading age"
- New strings in `strings.xml` (section title + three labels).
- `SettingsActivity.onCreate`: init each switch from `widgetStateManager`, and on change persist
  then broadcast `WidgetActions.ACTION_REFRESH` with `EXTRA_UI_ONLY` — the same repaint pattern
  as the Use Celsius switch (no WorkManager round-trip).

### Desktop — `SettingsWindow.kt`

- New `SettingsCard(title = "Daily View — Today Column")` right after the Units card, with three
  `Switch` rows (`testTag`s `today_overlay_delta_switch`, `today_overlay_dominant_temp_switch`,
  `today_overlay_dominant_age_switch`), each doing
  `updateConfig(currentConfig.copy(todayOverlayX = it))`. Existing auto-save (5s) and explicit
  Save flush handle persistence; no extra wiring needed.

## Tests

### `:shared` (all `@Category(ShortDuration)`)

Extend `TodayColumnOverlayContentResolverTest`:

1. `delta flag off suppresses only the delta text` — dominant temp/age still present.
2. `dominant temp off still shows reading age alone` (and vice versa).
3. `all flags off returns null content`.

### `:app` (Robolectric, `RobolectricTest` base → `@Category(LongDuration::class)`)

New `WeatherDisplayPreferencesTest` (or extend an existing prefs test):

1. All three toggles default to `false` on a fresh prefs store.
2. Set/get round-trips for each toggle via `WidgetStateManager`.

### `:desktop`

Update `DesktopDailyForecastModelTest`:

1. Existing `large desktop Today overlay uses dominant raw temperature and Blend age` — set the
   three config flags to `true` so it keeps asserting full content.
2. New test: default config (all flags false) → `state.todayOverlay == null`.

### Validation tasks

`./gradlew :shared:testShortShared`, `./gradlew :desktop:testShortDesktop`, and the matching
`:app` Robolectric bucket (every new/updated test class must declare exactly one `@Category`).

## Verification (Evidence-First)

1. Emulator (`Medium_Phone_API_36` or foldable): large widget (≥10 cols × ≥4 rows per
   `LargeTodayOverlayPolicy`). Screenshot with all toggles off → no overlay text; enable each
   toggle in Settings and screenshot after the UI-only repaint → confirm each text appears
   independently (incl. age-only with temp off). Check `app_logs` `TODAY_OVERLAY` rows carry the
   flag states.
2. Desktop: `./gradlew :desktop:run`, toggle switches in Settings, confirm the daily view
   updates after save and `config.json` persists the three fields.
3. Confirm no overlay regression on small widgets (policy gate still dominates).

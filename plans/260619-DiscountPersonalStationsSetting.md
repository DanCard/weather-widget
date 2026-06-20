# Settings: Discount Personal Weather Stations

## Context

Personal weather stations (PWS / "backyard units") frequently over-read in direct sun
and are poorly sited. In the actual-temperature IDW (inverse-distance²) blend, a nearby
PWS can dominate and pull the computed actual high above the official observation network.

The blend already has a hook for this — `PERSONAL_STATION_WEIGHT_MULTIPLIER` in
`ActualTemperatureSeriesBuilder.kt` — but it is a hardcoded constant. It was briefly set to
`0.5` (half weight) in the last commit, then reverted to `1.0` (no discount) in the current
working tree. The reversion is intentional: the value should be **user-configurable**, not
baked in.

**Goal:** Add a Settings control (both Android and desktop, for parity) that lets the user
discount personal stations from **0% (no discount — default) to 100% (ignore PWS entirely)**,
using a continuous slider. The discount applies app-wide, mirroring the existing global
"visible sources" setting.

**Semantics:** user-facing *discount %* → internal *weight multiplier* = `1 - discount/100`.
- 0% discount → multiplier `1.0` (today's behavior, default)
- 50% discount → multiplier `0.5` (half weight)
- 100% discount → multiplier `0.0` (PWS excluded from the blend)

## Design Decisions (confirmed with user)

- **Scope:** one app-wide value (not per-widget). Mirrors `visibleSources`.
- **Control:** continuous slider (Android `SeekBar` 0–100, desktop Compose `Slider`).
- **Shared API carries the multiplier** (`Double`, default `1.0`); each platform converts
  from its stored discount % at the call boundary.

---

## Part 1 — Shared: thread the multiplier through the blend

File: `shared/src/main/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesBuilder.kt`

1. Keep `PERSONAL_STATION_TYPE = "PERSONAL"`. Remove the hardcoded
   `PERSONAL_STATION_WEIGHT_MULTIPLIER` constant (it becomes a parameter). Update the comment
   to explain the parameter and its `1.0` default.
2. Add `personalStationWeight: Double = 1.0` parameter to:
   - `build(...)` (~line 68) — forward it into the `blendObservationSeries(...)` call (~line 113).
   - `blendObservationSeries(...)` (~line 204) — forward it into `blendCandidateTemperature(...)`.
   - `blendCandidateTemperature(candidates)` (~line 369) — accept the multiplier and use it at
     line 384: `val typeWeight = if (candidate.isPersonal) personalStationWeight else 1.0`.
   - The near-zero-distance branch (lines 374–375) already prefers official over personal; leave
     as-is, but when multiplier is `0.0` a personal-only `veryClose` set should still be skipped so
     the official network elsewhere wins — verify and, if needed, drop personal candidates whose
     effective weight is `0.0` before the `veryClose` pick.

File: `shared/src/main/kotlin/com/weatherwidget/shared/actuals/ActualsAggregator.kt`

3. Add `personalStationWeight: Double = 1.0` to `aggregate(...)` and forward it into both
   `blendObservationSeries(...)` calls (lines 43 and 141).

## Part 2 — Android: store, expose, and pass the setting

**Storage** — `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`
- Add `private const val KEY_PERSONAL_STATION_DISCOUNT = "personal_station_discount"` (global key,
  alongside `KEY_VISIBLE_SOURCES_ORDER` at line 62).
- `fun getPersonalStationDiscountPercent(): Int = prefs.getInt(KEY_PERSONAL_STATION_DISCOUNT, 0)`
- `fun setPersonalStationDiscountPercent(v: Int) { prefs.edit().putInt(KEY_PERSONAL_STATION_DISCOUNT, v.coerceIn(0,100)).apply() }`
- `fun getPersonalStationWeight(): Double = 1.0 - getPersonalStationDiscountPercent() / 100.0`

**UI** — `app/src/main/res/layout/activity_settings.xml` + `app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt`
- Add a labeled section ("Discount personal weather stations") with a `SeekBar` (`max=100`) and a
  live "%"-value `TextView`. Initialize from `getPersonalStationDiscountPercent()`; on
  `onProgressChanged`/stop, call `setPersonalStationDiscountPercent(...)` and update the label.
  Follow the existing section pattern used for the sources list (SettingsActivity ~lines 208–287).

**Consumption** — pass `widgetStateManager.getPersonalStationWeight()` at every real blend entry:
- `app/.../widget/TemperatureHourDataBuilder.kt` (~line 178) → `ActualTemperatureSeriesBuilder.build(... personalStationWeight = ...)`
- `app/.../widget/ObservationResolver.kt` (~lines 73, 100) → `ActualsAggregator.aggregate(... personalStationWeight = ...)`
- `app/.../data/repository/ObservationRepository.kt` (~line 548) → `blendObservationSeries(... personalStationWeight = ...)`
- These classes need access to a `WidgetStateManager` instance (most already have one or a context to build it); confirm during implementation.

## Part 3 — Desktop: config field, slider, pass the setting

**Config** — `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopConfig.kt`
- Add `val personalStationDiscount: Int = 0` to the `@Serializable data class DesktopConfig`
  (default `0`; `encodeDefaults=false` omits it until changed). No migration needed —
  `ignoreUnknownKeys=true` already tolerates old configs.

**UI** — `desktop/src/main/kotlin/com/weatherwidget/desktop/SettingsWindow.kt`
- Add a section with a Compose `Slider(value = currentConfig.personalStationDiscount.toFloat(),
  valueRange = 0f..100f, onValueChange = { currentConfig = currentConfig.copy(personalStationDiscount = it.roundToInt()) })`
  plus a label showing the current %. Saved via the existing `onSave(currentConfig)` flow.

**Consumption** — convert in a small helper (e.g. `config.personalStationWeight()` extension =
`1.0 - personalStationDiscount/100.0`) and pass:
- `desktop/.../TemperatureGraph.kt` (~line 185) → `ActualTemperatureSeriesBuilder.build(... personalStationWeight = ...)`
- `desktop/.../DesktopWeatherRepository.kt` (~line 261) → `ActualsAggregator.aggregate(... personalStationWeight = ...)`

## Part 4 — Tests

File: `shared/src/test/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesBuilderTest.kt`
- The existing test `personal and official stations get equal weight in the IDW blend`
  (expects `78.2`) stays valid as the **default (no discount)** case — keep it, optionally rename
  to clarify it asserts the 0%-discount default.
- Add a test that calls `blendObservationSeries(... personalStationWeight = 0.5)` with the same
  PWS 79°@2km vs official 75°@4km fixture and asserts the blended value shifts toward the official
  reading (compute expected: weights `0.5*0.25*79 + 0.0625*75` over `0.5*0.25 + 0.0625`).
- Add a `personalStationWeight = 0.0` test asserting the PWS is fully excluded (result == official 75°).

(Optional) Add a tiny unit assertion in `WidgetStateManager` test (if one exists) for the
discount→weight conversion, and/or a `DesktopConfig` round-trip test confirming the new field
serializes and defaults to `0`.

---

## Verification

1. **Shared logic (fast, no device):**
   `./gradlew :shared:test --tests "*ActualTemperatureSeriesBuilderTest*"`
   Confirms default still blends to 78.2 and the 50%/100% discount cases shift toward official.
2. **Desktop end-to-end:** build + restart per CLAUDE.md
   (`scripts/buildStart.sh`). Open Settings, drag the new slider to ~50%, Save. Then inspect the
   hourly/daily actual line on a day where a hot nearby PWS exists — the actual high should drop
   toward the official network reading. Confirm `~/.config/weather-widget/config.json` gains
   `"personalStationDiscount": 50` only after a non-zero change.
   (Stop the app fully before editing config.json by hand — it rewrites continuously.)
3. **Android:** `./gradlew installDebug`, open Settings, move the SeekBar, confirm the % label
   updates and the value persists across app restart (re-open Settings). Add a widget showing the
   actual high and confirm it responds to the discount.
4. **Regression:** `./gradlew :shared:test` (full shared suite) to confirm the new default
   parameter didn't disturb the daily-vs-hourly extrema agreement.

## Out of Scope / Notes

- `ActualsAggregator.resolveCurrentObservation(...)` (current-temp pick) is **not** part of the IDW
  blend and is left unchanged; if the user later wants the current-temp pick to also de-prioritize
  PWS, that's a separate follow-up.
- No DB schema or migration changes — this is purely a preference + in-memory weighting parameter.

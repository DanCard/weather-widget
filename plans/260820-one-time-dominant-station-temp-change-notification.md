# One-time notification when the dominant station's temperature changes

Date: 2026-08-20
Platforms: Android (`:app`) + Desktop (`:desktop`), shared logic in `:shared`

## Request

> Add a one time notification option in settings for when dominate station primary api changes
> temperature. After the notification fires uncheck / clear the setting.
> The notification should say something like "KNUQ 69.9, was 68"

## What "dominant station" means here

The station holding the largest final IDW weight in the observation blend behind the *currently
displayed* temperature — `ActualsAggregator.resolveCurrentObservationDetails(...).dominantContribution`,
the same value the today-column overlay's station row and the hourly graph's `knuq 73.4°` label
already name. "Primary api" = the displayed source (`WidgetStateManager.getPrimarySource()` on
Android, `config.settings.weatherSource` on desktop).

The compared number is `BlendContribution.rawTemp` — the thermometer's own reading, **not** the
blended/extrapolated value. Naming a station beside an extrapolated number would be a lie; this is
the same raw-vs-resolved rule `DominantStationLabel` documents.

## Design

### Arm / fire / disarm

The setting is an **arm** action, not a steady-state display preference:

1. User checks the box → state becomes `armed = true`, no baseline yet.
2. Next evaluation with a real dominant station → the baseline (`stationId` + `rawTemp` in °F) is
   captured. Still armed, nothing fires. (Arming cannot fire immediately — there is nothing to
   compare against, and "changed" needs a before.)
3. A later evaluation whose dominant reading **displays** differently → fire the notification,
   clear the baseline, set `armed = false`.

"Any different value" is measured on the **formatted** value (`TempUtils.formatTemp` at the user's
current unit), not the raw float. A station re-reporting 69.87 → 69.92 renders identically, and a
notification reading "KNUQ 69.9°, was 69.9°" would look like a bug. Storing the baseline in °F and
formatting at compare time also means a unit change between arm and fire cannot desync the message.

**Two independent triggers** (user clarification 2026-08-20 — "if the dominant station changes, that
should trigger a notification also"):

1. the displayed reading changed, or
2. a *different* station took over the blend, even at an identical temperature — a handover from an
   airport ASOS to a backyard PWS is a change in what the app is telling you regardless of degrees.

Trigger 2 needs its own message format, or a same-temperature handover reads `KSJC 68°, was 68°` and
looks like nothing happened. Station ids compare case-insensitively, so casing alone is not a
handover.

### Synthetic rows are skipped

`BlendContribution.isSynthetic` rows (`OPEN_METEO_MAIN` etc.) are the source's own hourly forecast
re-filed as observations, not thermometers. They are held, never armed or fired against, matching
`DominantStationLabel.format`'s refusal to name them. **Consequence:** under a forecast-only source
(everything but NWS) there is no real station, so the watch will sit armed indefinitely. Logged as
`DOMINANT_TEMP_WATCH hold reason=synthetic` so the reason is queryable rather than silent.

### Message

- same station, new reading: `KNUQ 69.9°, was 68°`
- a different station took over: `KSJC 72°, was KNUQ 68°`

Station ids uppercased (they are callsigns here, not the lowercased graph label). Title:
`Dominant station reading changed`.

The wording is **not** in `:shared` — that module has no Android resources, and hardcoding English
there would ship an English notification to a device running in German. `DominantTempWatchStrings`
carries the title and both body formats; Android passes localized resources (added to `values/` and
all 19 translations), desktop takes the defaults since it has no localization layer.

## Files

### `:shared` (new) — `shared/src/main/kotlin/com/weatherwidget/shared/notify/DominantTempWatch.kt`

Pure, platform-free, no I/O — per testing-strategy, extract the decision so it is unit-testable
without a notification harness:

```kotlin
data class DominantTempWatchState(armed, baselineStationId, baselineTempF)
sealed interface DominantTempWatchDecision { Idle | Hold(reason) | Capture(state) | Fire(title, body, state) }
data class DominantTempWatchStrings(title, bodyFormat, bodyStationChangedFormat)
object DominantTempWatch {
    fun evaluate(state, dominant: BlendContribution?, useCelsius, strings): DominantTempWatchDecision
    fun formatBody(stationId, newTempF, oldTempF, useCelsius, strings, previousStationId): String
}
```

### `:app`

- `AndroidManifest.xml` — add `POST_NOTIFICATIONS`.
- `widget/DominantTempWatchPreferences.kt` (new) — flag + baseline in `weather_prefs`; arming clears
  the baseline. Flag and baseline live together because they are one unit.
- `widget/WidgetStateManager.kt` — expose `notifyOnDominantTempChange()` / setter for Settings.
- `notify/DominantTempChangeNotifier.kt` (new) — channel creation, `suspend fun check(...)`:
  early-returns on a single prefs read when disarmed; otherwise loads observations + hourly for the
  primary source, resolves the dominant contribution, applies `DominantTempWatch.evaluate`, notifies
  and disarms on `Fire`.
- `widget/FullSyncPipeline.kt` — call `check(...)` after the actuals stage.
- `widget/WeatherWidgetWorker.kt` — call `check(...)` at the end of `handleCurrentTempOnlyWork`
  (the frequent path where fresh observations actually land).
- `res/layout/activity_settings.xml` + `res/values/strings.xml` — new "Notifications" section with
  the switch and a one-line explainer.
- `ui/SettingsActivity.kt` — wire the switch; request `POST_NOTIFICATIONS` on API 33+ when enabling;
  register a prefs listener so the switch un-checks itself live if it fires while Settings is open.

### `:desktop`

- `DominantTempWatchStore.kt` (new) — `~/.local/share/weather-widget/dominant-temp-watch.json`.
  **Deliberately NOT in `DesktopConfig.settings`:** the daemon must clear the flag when it fires,
  but the UI process does not watch `config.json` for external edits, so a stale in-memory
  `config` re-saved by the Settings window's auto-save would resurrect the armed flag. A separate
  single-purpose file has no such clobber path.
- `DesktopWeatherRepository.kt` — `resolveDominantContribution(hourly, observations, now)` using the
  same narrow window as `resolveForForecastResult`, so the watcher and the displayed temperature
  cannot disagree about which reading is current.
- `DaemonProcess.kt` — evaluate after each successful `refreshObservations()` in the temp-actuals
  loop; on `Fire` call `notifyDesktop(...)` and clear the store.
- `SettingsWindow.kt` — a "Notifications" card whose checkbox reads/writes the store directly
  (immediate effect, like "Refresh Data" / "Exit app"), bypassing the draft/auto-save machinery.

## Verification

### Unit tests — `shared/src/test/kotlin/com/weatherwidget/shared/notify/DominantTempWatchTest.kt`

- disarmed → `Idle`, regardless of the contribution.
- armed, `dominant == null` → `Hold("no_dominant")`, state unchanged.
- armed, synthetic dominant → `Hold("synthetic")`, no baseline captured.
- armed, no baseline, real dominant → `Capture` with that station id and rawTemp.
- armed + baseline, identical rawTemp → `Hold("unchanged")`.
- armed + baseline, rawTemp differs but formats identically (69.87 → 69.92 in °F) → `Hold`.
- armed + baseline, changed value → `Fire`, body == `"KNUQ 69.9°, was 68°"`, returned state disarmed
  with the baseline cleared.
- fire under `useCelsius = true` formats both numbers in °C from the same stored °F baseline.
- dominant station identity changes with a different value → `Fire`, body names BOTH stations
  (`"KSJC 72°, was KNUQ 68°"`).
- handover at an identical temperature → `Fire` (trigger 2), same two-station body.
- station id differing only in case → `Hold("unchanged")`.
- caller-supplied wording is used verbatim for both body formats.
- state returned by `Fire` re-evaluated against the same contribution → `Idle` (proves one-shot).

### Unit tests — desktop

- `DominantTempWatchStoreTest` — round-trip; a missing file reads as disarmed; a corrupt file reads
  as disarmed rather than throwing.

### Robolectric — `app/src/test/java/com/weatherwidget/ui/SettingsActivityRobolectricTest.kt`

- extend the existing switch table with `notify_dominant_temp_change_switch` ↔
  `notifyOnDominantTempChange()`.

### Manual

- Android: enable, `adb logcat` for `DOMINANT_TEMP_WATCH`, confirm the baseline row, wait for the
  next observation, confirm the notification text and that the switch is off on re-entering Settings.
- Desktop: enable, watch the autostart log for `DOMINANT_TEMP_WATCH`, confirm `notify-send` fires
  once and the checkbox is clear on reopening Settings.

## Follow-ups (not in this change)

- No re-arm-on-next-change ("notify every time") mode; the request is explicitly one-shot.
- Forecast-only sources never fire (synthetic-skip above). If that turns out to matter, the fix is a
  separate decision about whether naming a synthetic row is acceptable.


## Status — implemented 2026-08-20

All of the above shipped. Test results:

- `:shared` `DominantTempWatchTest` — 17 tests, green.
- `:desktop` `DominantTempWatchStoreTest` — 6 tests, green.
- `:app` `SettingsActivityRobolectricTest` — 3 new cases (default off, arm/disarm, live un-check),
  10 total, green. The live un-check case was proved able to fail by removing the prefs-listener
  registration.
- Full suites green: `:shared:test`, `:desktop:test`, `:app:testDebugUnitTest` (1984 tests).

One thing the plan did not anticipate: `LocaleResourceParityTest` enforces that every translatable
string exists in all 19 shipped locales, so the eight new strings were translated rather than left
English-only. That is also what forced the wording out of `:shared` and into
`DominantTempWatchStrings`.

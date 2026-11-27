# Previous-location toggle in Settings

Status: **approved 2026-11-27 (P1, P2, P3 + follow-device amendment)**

## Request (user, 2026-11-26)

> I'm constantly toggling between two locations. I'm leaving Sunday for San Fran. What do you think
> about having a button on settings to toggle back to previous location settings, if there is more
> than one location setting that has been set in last x days?
>
> Write plan, track use of toggle button so that if older than 2 weeks and toggle button hasn't been
> used then don't show it.

## Current state (verified in code)

- Both platforms already keep a recent-locations list: `RecentLocation(lat, lon, label, timestamp)` +
  `RecentLocationsHelper` in `:shared`, which keeps up to 8 entries, newest first, and dedupes on a
  0.01° box or the same label.
  - Android: `WidgetStateManager.getRecentLocations()/addRecentLocation()` (prefs JSON).
  - Desktop: `DesktopConfig.recentLocations`. `LocationPickerWindowHost` adds the pick and saves with
    the picker source.
- `timestamp` is set when a location is **picked**; nothing updates it when you **leave** it.
- Switching back today takes Settings → Set location → focus search → pick the recent → done.
  - Android: picking a recent calls `ConfigActivity.saveChosenLocation(..., LocationMode.FIXED)`,
    which runs the source-availability check and then `finishGlobalSave` (recents, `applyToAllWidgets`,
    "Getting weather for {place}…" toast).
  - Desktop: `resolveDesktopConfigSave` lets the picker source change only `lat`, `lon`, `label` and
    `recentLocations` (it guards against config clobber; see plans/261121-…).
- Weather data is keyed by coordinates (`LocationMatch`), so switching back reuses that site's cached
  forecasts and history. The toggle doesn't need anything new on the data side.

## Behavior

A second button in the Settings → Location card, under "Set Location…": **"↔ San Francisco"**. The
button names the place it will switch to.

**Target** = the first recent entry that does not match the current location (same match rule as
`RecentLocationsHelper`).

**Visible when** a target exists **and** at least one of these holds:
1. the target was last used ≤ 14 days ago, or
2. the toggle button was pressed ≤ 14 days ago.

Otherwise the button is hidden. Using the toggle regularly keeps it visible even if you stay in one
place for longer than two weeks. If you go a while without moving and without using it, it goes away.

**On tap:** switches to the target exactly as picking it from the recents dropdown does: same save
path, same fetch, same notice. Then it stamps `lastToggleUsedAt = now`. The button label then flips to
the place you just left.

### Claude proposals (not stated by the user; strike any you don't want)

- **P1 — "last used" = when you left it.** When the active location changes (picker *or* toggle), set
  the timestamp of the location being left to `now`, without changing its order.
  - Without this, "older than 2 weeks" measures when you *picked* home, not when you were last there.
    Example: home for 6 weeks → pick SF in the picker → home's stamp is 6 weeks old and the toggle
    has never been used → the button is hidden the first time you need it.
  - With it, home's stamp is "the moment I left home", so the button shows on day one in SF.
- **P2 — Follow-device mode (Android).** The button stays visible, and tapping it sets
  `LocationMode.FIXED`. Picking a recent already does this today. Desktop has no location mode.
- **P3 — window = 14 days**, one shared constant `PreviousLocationToggle.VISIBILITY_WINDOW_MS`.

### Follow-device amendment (user approved 2026-11-27)

- `RecentLocation` gains `followDevice: Boolean = false`. Old stored entries read as `false`.
- Choosing "Use device location" records a recent with `followDevice = true`. The toggle target for that
  entry is the *setting* "Follow device", not the fix's coordinates. The button reads "↔ Follow device"
  and tapping it re-enables `LocationMode.FOLLOW_DEVICE` and takes a fresh device fix (the same path
  as the "Use device location" button).
- At most one follow-device entry is kept in recents. A new one replaces the old one, whatever its
  coordinates, so GPS fixes in different cities don't pile up as separate entries.
- Background follow-device moves (`LocationUpdater.applyFollowDeviceLocation`) are **not** recorded
  into recents. While following the device the phone already moves itself; recording each move would
  push hand-picked places out of the 8-entry list.
- Current-location matching: when the current mode is FOLLOW_DEVICE, the follow-device entry is the
  current setting (never the target). A fixed entry at the same coordinates as the current site is
  also excluded.
- Desktop has no location mode. Follow-device entries never exist there, and the desktop target
  skips them if one ever arrives.

## Design

### `:shared` (owns the whole rule)

New `shared/.../shared/util/PreviousLocationToggle.kt`:

```kotlin
object PreviousLocationToggle {
    val VISIBILITY_WINDOW_MS = 14 * 24 * 3_600_000L
    /** The location the toggle would switch to, or null when the button is hidden. */
    fun target(recents, currentLat, currentLon, lastToggleUsedAtMs: Long?, nowMs): RecentLocation?
    /** Recents after switching from `current` to `target` (P1 stamps `current`; `target` to front). */
    fun recentsAfterSwitch(recents, current: RecentLocation?, target: RecentLocation, nowMs): List<RecentLocation>
}
```

- `RecentLocationsHelper.isMatch` becomes `internal`/public `matches` so the toggle excludes the
  current location with the same rule.
- `RecentLocationsHelper.addRecent` (used by both pickers) gets an optional `leaving` argument and
  calls the same stamping logic, so P1 applies to picker moves as well.
- Logging: `Log.d("LOCATION_TOGGLE", "from=… to=… lastToggle=…")` on use. `Log.v` for the visibility
  decision (it is evaluated on every Settings open).

### Android (`:app`)

- `activity_settings.xml`: `@+id/previous_location_button` under `set_location_button`. Styling
  follows the existing secondary buttons, ~21sp. Visibility is `gone` by default.
- `SettingsActivity.setupLocationSettings()` / `onResume`: compute the target and set the label and
  visibility.
- Tap → start `ConfigActivity` with `EXTRA_APPLY_RECENT_LAT/LON/LABEL`. `ConfigActivity` calls
  `saveChosenLocation(..., FIXED)` and finishes without showing its form.
  - This reuses the source-availability prompt and `finishGlobalSave` unchanged, so there is no
    second save path that could drift.
- `WidgetStateManager`: `getLastLocationToggleAt()/markLocationToggleUsed(now)` (prefs key).
  `persistWidgetLocation`/`finishGlobalSave` pass the leaving location to `addRecent` (P1).
- New string `switch_to_location` ("↔ %1$s") in all 19 locales (locale parity test).

### Desktop (`:desktop`)

- `SettingsWindow.kt` Location card: the same button under "Set Location…" (testTag
  `previous_location_btn`), with the same rule and label.
- `DesktopConfig`: new nullable `locationToggleUsedAtMs: Long? = null`. Old configs read as null
  (`ignoreUnknownKeys` and defaults), so no migration is needed.
- Tap → build the config the way `LocationPickerWindowHost` does (shared
  `recentsAfterSwitch`). Save with a new source `DesktopLocationChangeFeedback.TOGGLE_SOURCE`.
  - `resolveDesktopConfigSave` treats it like the picker: only `lat`, `lon`, `label`,
    `recentLocations` and `locationToggleUsedAtMs` change.
  - It gets the same location-change banner and fetch as the picker.
- The picker path also passes the leaving location (P1).

No Room or SQLite schema change.

## Tests

- **`:shared` unit** (`PreviousLocationToggleTest`):
  - no recents
  - only entry = the current location
  - target fresh → shown
  - target 15 d, toggle never used → hidden
  - target 15 d, toggle used 13 d ago → shown
  - target 15 d, toggle used 15 d ago → hidden
  - `recentsAfterSwitch` stamps the leaving entry without reordering it, and puts the target first
  - the 6-weeks-home scenario from P1
- **Android Robolectric integration**: `SettingsActivity` with real `WidgetStateManager` prefs.
  - Seeded recents + active location → the button shows "↔ San Francisco". Stale entries → `gone`.
  - Click → `ConfigActivity` with the extras → active location applied, `LocationMode` FIXED, toggle
    stamp written, recents reordered.
- **Desktop integration**: Compose test on `SettingsWindow` + `resolveDesktopConfigSave`.
  - Click → the saved config has the new lat/lon, recents and stamp.
  - Window geometry, zoom and settings are unchanged (the clobber guard still holds for the new source).
- **Device and desktop verification**:
  - Pixel/emulator: Settings screenshot showing the button, tap → widget repaints at the other site.
    Logcat `LOCATION_TOGGLE`.
  - Desktop: the same through the Settings window.

## Docs

- `arch/operations.md` config-save paragraph: add the toggle source to the list of writers allowed to
  change only the location.
- `arch/ARCHITECTURE.md` "Data and location identity": one line on the toggle and its 14-day
  visibility rule, linking to the shared constant rather than repeating it elsewhere.

## End-of-task gate

`./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`

## Revision 2026-11-27 (user feedback after the first build)

> Button text to big, and there should be something that says toggle to previous location. Text needs
> higher contrast. Difficult to see.

- Label: "Toggle to previous location: {place}" (`R.string.toggle_previous_location`, translated;
  follow-device target: `toggle_previous_follow_device`). Replaces the bare "↔ {place}".
- 16sp, sentence case, up to two lines (was 21sp, all caps on Android).
- Dark `#1A1A1A` text on the blue fill (was white). Desktop: `SecondaryActionDarkTextButton`.

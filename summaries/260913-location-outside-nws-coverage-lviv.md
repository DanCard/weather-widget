# Location Search & Sources Outside NWS Coverage (Lviv)

*2026-09-13* — no plan file; driven live from device reports (Fold4, Pixel 7 Pro, two emulators, desktop).

## Outcome

Setting the location to Lviv, Ukraine now works end to end on Android and desktop. Before, it failed at
five separate points, each of which was reported one at a time:

1. **Search reported "No matches found" when the phone was offline.** `SharedLocationResolver.searchText`
   swallowed every exception into `emptyList()`. The Fold4 had no network at all (`Active default network:
   none`), so the failure was blamed on spelling. Search now throws; Android shows "Search failed — check your
   network connection" and logs `SEARCH_FAILED`; the desktop picker shows "Search failed: …".
2. **Results came back in Ukrainian** (`Львів, Львівська область, Україна`). Nominatim names places in the
   local script unless asked; both `search` and `reverse` now send `accept-language=en,*`.
3. **NWS stayed the displayed source at a site it does not cover.** `api.weather.gov/points` returns 404
   `InvalidPoint` for Lviv, yet the widget kept `source=NWS` and painted the *previous* site's cached NWS
   forecast (Mountain View's 62°/57°) under the Lviv label. The existing `SetupSourceSelector` coverage check
   only ran in the widget-add flow; the Settings → Default Location path skipped it by design
   (d310e671 "keep settings edits from changing source selection"). Reversed for location changes. The
   desktop had the mirror bug: the picker defaulted to Open-Meteo but left NWS in the source cycle, so one
   header tap put it on NWS and the UI sat on "Loading...".
4. **NWS never came back** when returning to the US, once retired. Added an "auto-retired" flag on both
   platforms so a move back inside coverage restores NWS — but only when the app removed it, never when the
   user unticked it in Settings.
5. **The search-result dialog did not read as a choice.** Title was the raw query and the rows were plain
   text; a wrapped first result and the second read as one paragraph. Now "Tap the location to use (N
   matches)" over bordered cards with bold name, coordinates, and a USE affordance; desktop rows got the
   same treatment with a heading and Use buttons. The global-save toast also names the place ("Location
   set to Lviv, Lviv Oblast for all widgets").

Also: "Default Location" moved above "Icon gallery" on both Settings screens (via `SettingsSection` order).

---

## What Changed

### Shared (`:shared`)
- **`shared/util/NwsCoverage.kt`** *(new)*: the US/AK/HI/PR bounding box (`covers`), `retireNws` (drop NWS,
  never empty the list, Open-Meteo fallback), `restoreNws` (NWS back in front), `visibleSourcesFor`.
  Replaces the box that was inline in desktop `toConfig()` and the removal rule that was written twice.
- **`data/remote/NominatimApi.kt`**: `accept-language=en,*` on `search` and `reverse`.
- **`data/repository/SharedLocationResolver.kt`**: `searchText` no longer swallows exceptions.
- **`shared/settings/SettingsSection.kt`**: `DEFAULT_LOCATION` before `ICON_GALLERY`.

### Android (`:app`)
- **`ui/ConfigActivity.kt`**: `applySetupSourceSelection()` extracted and now also called from the global
  (Settings) save path; search-failure toast; `LocationChoiceAdapter` for the results dialog; friendly-name
  toast via `friendlyName()`; passes/updates the `nws_auto_retired` flag.
- **`ui/SetupSourceSelector.kt`**: `SetupSourcePolicy` delegates removal to `NwsCoverage.retireNws` and
  restores via `restoreNws` when `nwsAutoRetired`; `checkNws` consults the coverage box when the live probe is
  INCONCLUSIVE (offline/timeout) — outside the box → `UNSUPPORTED` with reason `<cause>_outside_coverage_box`.
- **`widget/WeatherSourcePreferences.kt`** / **`WidgetStateManager.kt`**: `retireNwsOutsideCoverage()` heal +
  `isNwsAutoRetired`/`setNwsAutoRetired` (`nws_auto_retired` pref). The user's `setVisibleSources` path
  clears the flag.
- **`widget/FullSyncPipeline.kt`**: calls the heal once the active location is known (`SYNC_STAGE:
  nws_retired_outside_coverage`), so prefs written by older builds converge on the first sync.
- **`data/repository/ForecastFetchCoordinator.kt`** + **`widget/GraphFailureWatermarkRenderer.kt`**:
  `NwsPointUnavailableException` → new `NO_COVERAGE` code → watermark detail "Not available in this region"
  instead of "404 Not Found".
- **`res/layout/activity_settings.xml`**: Default Location block moved above Icon Gallery.
- **`res/layout/item_location_choice.xml`**, **`res/drawable/bg_location_choice.xml`** *(new)*.
- **`res/values*/strings.xml`** (20 locales): `location_search_failed`, `location_search_choose_title`,
  `location_search_confirm_title`, `location_choice_use`, `location_saved_success_named`,
  `watermark_no_coverage`; `location_search_no_results` dropped "or network".

### Desktop (`:desktop`)
- **`DesktopConfig.kt`**: `DesktopSettings.nwsAutoRetired`; `withNwsCoverageApplied()` used by the
  `location-picker` save and by `DesktopConfigStore.load()` (heals an already-saved out-of-coverage config —
  this is what unstuck the running tray app). A `settings` save that changes `visibleSources` clears the flag.
- **`LocationResolver.kt`**: `toConfig()` uses `NwsCoverage.covers`.
- **`LocationPicker.kt`**: failed-vs-empty status; heading + outlined rows with Use buttons.
- **`SettingsWindow.kt`**: Location card above Icon gallery.

### Tests
- New: `NwsCoverageTest` (shared, 6); desktop `DesktopConfigSavePolicyTest` +4, `DesktopConfigStoreTest` +1;
  Android `SetupSourceSelectorTest` +2, `WidgetStateManagerApiRotationRoboTest` +3,
  `ConfigActivityRobolectricTest` +1 (global mode drops NWS for Lviv).
- Flipped: `ConfigActivityRobolectricTest` "Settings/global saves must not run setup source checks" → runs
  once. Two checker tests moved from London to a US coordinate since London-with-timeout is now (correctly)
  `UNSUPPORTED`.

---

## Insights & Technical Constraints

1. **A source that cannot load must leave the visible list, not just lose the fetch.** The display source
   is a per-widget pref decoded against the visible list; nothing at paint time asks whether the source has
   data *for this site*. Retiring it from the list is what moves the display source and stops the stale
   previous-site rows from being painted.
2. **Live probe first, bounding box second.** Android keeps the API probe (it is right for Guam / USVI where
   the box is wrong) and uses the box only to break an INCONCLUSIVE tie. The desktop uses the box alone on
   config load because that path must not make a network call. Both share the box.
3. **Restore needs provenance.** Symmetric restore without the flag would override a user who unticked NWS
   on purpose. The flag is set by the app's retirements (setup check, sync heal, desktop picker/load) and
   cleared by any user edit of the list. Devices retired by the pre-flag builds from earlier in this session
   cannot be told apart from manual unticks; the flag was set by hand on emulator-5554 and the Fold4
   (`run-as` + `sed` on `widget_state_prefs.xml`). The Pixel and the desktop `config.json` still need it.
4. **Nominatim `display_name` is not a label.** It is comma-joined, most-specific-first, in the local
   language. `accept-language` fixes the script; `friendlyName()` (reverse geocode with `addressdetails`)
   gives the compact "City, Region" for toasts and headers.
5. **Locale parity is enforced.** Every new string had to land in all 20 `values-*/strings.xml` or
   `LocaleResourceParityTest` / lint `MissingTranslation` fail.
6. **`adb input text` quirks.** A space or comma sent via `input text` fired the config screen's back
   button (`BACK_TAP`) on the emulator — an `adb` artifact, not a finger-on-keyboard path. Typing single
   words worked. The Pixel's wireless-ADB transport was too slow for `logcat -d`; use USB.
7. **Not caused by this work:** on emulator-5556 Open-Meteo was already unticked before the Lviv change
   (`charging_loop` fetch at 06:46 had no Open-Meteo). Separately noticed: the Config header once showed
   "Widget Location: Manual Refresh (37.4168, …)" — `FriendlyLocationName.nameFromPois` picking up a POI
   literally labelled "Manual Refresh". Unfixed.

---

## Verification

- Suites run green after the final change: `NwsCoverageTest`, `NominatimApiTest`, `SettingsSectionTest`
  (shared); `DesktopConfigSavePolicyTest`, `DesktopConfigStoreTest`, `SettingsDraftRebaseTest`,
  `SettingsWindowSectionsTest` (desktop); `SetupSourceSelectorTest`, `ConfigActivityRobolectricTest`,
  `ConfigActivityAddFlowRoboTest`, `WidgetStateManagerApiRotationRoboTest`, `SettingsSectionOrderRoboTest`,
  `WatermarkLocalizationRoboTest`, `LocaleResourceParityTest`, `HardcodedUserFacingStringTest` (app).
  The full `scripts/unit-tests.sh` run was **not** executed this session.
- **emulator-5554**: Settings → "lviv" → English results → `NWS_SETUP_CHECK … result=unsupported
  reason=invalid_point`, `SOURCE_ORDER [NWS, …] -> [OPEN_METEO, …, WEATHER_API]`, widget header "Meteo" with
  Lviv temps. Later "Use precise device location" (San Francisco) → `reason=nws_restored`.
- **emulator-5556**: Lviv → Mountain View round trip restores NWS; toast "Location set to Lviv, Lviv Oblast
  for all widgets"; new card dialog screenshot confirmed.
- **Fold4 `RFCT71FR9NT`** (offline): sync-time heal fired — `SOURCE_ORDER: NWS retired outside coverage`,
  all three widgets repainted `source=OPEN_METEO`.
- **Desktop**: config healed on load (`weatherSource=OPEN_METEO`, NWS out of `visibleSources`), refresh
  succeeds, no "Loading...".
- **Pixel 7 Pro**: has an intermediate build (heal, no restore flag, old dialog); needs the current APK over
  USB plus the `nws_auto_retired` nudge.

All changes are **uncommitted** in the working tree at time of writing (44 files).

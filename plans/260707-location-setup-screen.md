# Unified Location Setup Screen + Pinned Location Mode

## Context

Yesterday's change made all location reads passive (cached fused `lastLocation`) to stop Samsung's "widget got your precise location" warning. Side effect on the **emulator**: this morning the background auto-heal consumed the emulator's stale fused location (Austin, TX — leftover from geo-fix testing) and rewrote widgets 52/53 to Austin (`GPS_RESAMPLE outcome=healed` at 08:17, verified in pulled DB). There is also a latent design gap: the auto-heal overwrites **deliberately chosen** locations (zip/coords) whenever the device location differs.

**Goals (user-confirmed):**
1. One location-setup screen with four options — **precise device location, zip, address, manual coordinates** — used by initial widget setup (APPWIDGET_CONFIGURE) and reachable from a new **Settings button**.
2. "Use precise location" = **one-time active GPS fix** (foreground, user-initiated — allowed exception to the passive-only rule; Samsung's warning targets background access). Sets mode **FOLLOW_DEVICE**.
3. Zip/address/coords **pin** the location (mode **FIXED**): background + foreground auto-heal skip. This is also the emulator remedy — user will pin a fixed temporary location there (later, separately: copy phone location via adb).
4. No emulator-specific GPS code.

## Design decisions

- **Reuse `ConfigActivity` as the shared screen** (no new activity — OEM launchers cache the APPWIDGET_CONFIGURE component; the RESULT_OK/CANCELED handshake stays untouched). Settings launches it with `EXTRA_GLOBAL_CONFIG=true`; in that mode the invalid-widget-id early-finish is relaxed, save goes through `LocationUpdater.applyToAllWidgets`, and it ends with plain `finish()` (**never RESULT_OK without a widget id**).
- **Address + zip = one search field** backed by existing `SharedLocationResolver.searchText` (Nominatim, `shared/.../SharedLocationResolver.kt:12-15`, currently unused by UI). Results confirmed via `AlertDialog.setItems` of labels. Drop platform `Geocoder` zip path (`saveZipCodeLocation`).
- **Mode flag**: new `app/src/main/java/com/weatherwidget/util/LocationMode.kt` object — `weather_prefs` key `location_mode`, values `follow_device` (default when absent → existing installs keep healing) / `fixed`. Via `SharedPreferencesUtil.getPrefs` only.
- **Gate in `GpsResampler.healIfNeeded`** (covers worker AND MainActivity foreground path; MainActivity toast suppressed by the `false` return) + early-return in `resample` before the Play-services read. Breadcrumb `outcome=skipped_pinned trigger=…`. NOT in `shouldHealTo` (would mislabel as `same_site`).
- **Drop both `reportsStandardGps` GONE guards** (ConfigActivity.kt:74-77 coords section, SettingsActivity.kt:364-368 whole location section) — all four options on all devices; pinning makes manual coords legitimate on GPS phones.
- **Per-widget save stays decoupled** (widget config writes only `widget_lat_/lon_$id`; no `historical_pois` side effect). Every save (widget or global) also writes the global `location_mode`.

## Implementation (ordered)

### Phase 1 — pin mechanism (independently shippable; stops the emulator re-heal)
1. CREATE `app/src/main/java/com/weatherwidget/util/LocationMode.kt` — consts + get/set as above.
2. MODIFY `app/src/main/java/com/weatherwidget/widget/GpsResampler.kt` — mode gates in `resample` (after permission check, `trigger=worker`) and `healIfNeeded` (first line, `trigger=$trigger`, return false); update KDoc (pinning; the active-fix exception lives in ConfigActivity).
3. MODIFY `app/src/test/java/com/weatherwidget/widget/GpsResamplerTest.kt` — add: fixed-mode skips worker (`providerCalls==0`, exact breadcrumb); fixed-mode `healIfNeeded` returns false (`trigger=foreground`); absent-key-defaults-to-follow regression. Reset the pref in setUp.
4. CREATE `app/src/test/java/com/weatherwidget/util/LocationModeTest.kt` — default + round-trip (Robolectric).
5. MODIFY `MainActivity.kt` — doc comment only on `maybeAutoHealLocationFromGps`.

### Phase 2 — unified screen (ConfigActivity)
6. MODIFY `app/src/main/res/layout/activity_config.xml` — title gets `@+id/config_title` (runtime text per mode); GPS button text → `@string/use_precise_location` + explainer TextView; replace zip block with `@+id/location_search_input` (textPostalAddress) + `@+id/search_location_button`; keep `coordinates_section`; add pinning explainer at card bottom. NB: `use_current_location` string is used for BOTH header (line 39) and button (line 49) — change only the button.
7. MODIFY `app/src/main/java/com/weatherwidget/ui/ConfigActivity.kt`
   - `EXTRA_GLOBAL_CONFIG` const, `isGlobalMode`, relaxed early-finish, per-mode title.
   - Remove coords-section guard.
   - Search flow: disable button → `lifecycleScope.launch { sharedLocationResolver.searchText(query) }` → 0: toast / 1: confirm dialog / N: pick-list → `saveChosenLocation(lat, lon, label, FIXED)`.
   - Coords handler → same sink, FIXED (keeps `fromCoordinates` label lookup).
   - `getCurrentLocation()` → **active fix** `getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)`; null/failure → `lastLocation` → default; all branches save with FOLLOW_DEVICE. Disable button + "Getting location…" while in flight. REQUIRED call-site comment stating the foreground/user-initiated exception (otherwise it reads as a CLAUDE.md violation).
   - `saveChosenLocation(lat, lon, label, mode)`: `LocationMode.set`; global → `applyToAllWidgets` (has its own force-refresh) + toast + `finish()`; widget → existing per-widget write + `triggerWidgetUpdate()` + `finishWithSuccess()`.
   - Permission/background-disclosure flow unchanged.
8. MODIFY `app/src/main/res/values/strings.xml` — add `use_precise_location`, `widget_location_title`, `or_search_location`, `location_search_hint`, `search_location`, `location_search_no_results`, `getting_location`, `follow_device_explainer`, `pinned_location_explainer`, `set_location_button`, `location_mode_pinned`, `location_mode_follow`; remove dead zip strings (grep first).

### Phase 3 — Settings entry
9. MODIFY `app/src/main/res/layout/activity_settings.xml` (`location_settings_section`, lines 236-305) — keep header + `current_location_label`; delete lat/lon row + `save_location_button`; add `@+id/set_location_button`.
10. MODIFY `app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt` — remove the GONE guard (section always visible); label = current location + " • Pinned"/" • Follows device", populated from `onResume()`; button → `startActivity(Intent(this, ConfigActivity::class.java).putExtra(EXTRA_GLOBAL_CONFIG, true))`; delete dead `saveLocationGlobally` + lat/lon parsing (androidTest has no references — verified).

### Phase 4 — docs & repo plan copy
11. MODIFY `CLAUDE.md` (~line 48) — amend the "never active fix" rule with the user-initiated foreground exception; document `location_mode`.
12. Save a copy of this plan to `plans/260707-location-setup-screen.md` (repo convention: new plan file per task).

## Verification

- Unit: `./gradlew testDebugUnitTest --tests '*GpsResamplerTest' --tests '*LocationModeTest' --tests '*ConfigActivityRobolectricTest' --tests '*SettingsActivityRobolectricTest'` — including NEW Config tests: global-mode launch doesn't finish early, saves route to all widgets, RESULT_OK never set in global mode; search flow via mocked `searchText` + ShadowAlertDialog; mode=fixed after manual save. Then full `testDebugUnitTest`.
- Instrumented: `./scripts/emulator-tests.sh` (never connectedDebugAndroidTest).
- Manual, emulator: add widget → all four options visible; search a zip → pick → Settings shows "Pinned"; trigger worker + reopen app → location must NOT re-heal to the Austin fused cache; pull DB → `GPS_RESAMPLE outcome=skipped_pinned`. Settings → Set Location… opens the screen without a widget id and applies to all widgets.
- Manual, Pixel: "Use precise device location" → active fix lands, mode "Follows device", heal works again; upgrade-in-place (no mode key) still heals; widget-host contract: back out of config → widget not placed, complete → placed.
- Samsung (when available): confirm no precise-location notice from background paths; a notice on the explicit tap only is acceptable.

## Risks
- RESULT_OK in global mode would confuse widget hosts — guarded by branch + explicit test.
- Active `getCurrentLocation` can stall on emulator — fallback chain + disabled button handle it; add a timeout only if manual testing shows hangs.
- Existing emulator widgets are already at Austin: after implementing, pin a real location via the new screen (fix-forward, no data surgery needed).

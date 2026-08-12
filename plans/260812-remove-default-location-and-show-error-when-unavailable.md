# Remove hardcoded Mountain View default & show error when no location

**Status:** Plan (rev 2) · **Target:** H1 from `plans/260812-code-review-refresh-coordination.md`
**Goal:** Delete the Google-HQ hardcoded default/fallback location (`WeatherWidgetWorker.DEFAULT_LAT/LON` = 37.4220, -122.0841). When no location is resolvable, the widget shows an explicit "no location" error instead of silently fetching/labeling Mountain View weather.

**Rev 2 changes:** added the upgrade migration (§4, the change most likely to regress existing users),
added `HourlyObservationBackfill` (§6, previously missing entirely), corrected the §7 defensive-sweep
remedy from "abort render" to "degrade feature", added the missed call sites, and corrected the
"cannot build locally" claim.

---

## 1. Problem statement

`DEFAULT_LAT/LON` (= Google HQ) plays **two distinct roles** today, and only one of them is a bug:

1. **Legit — auto-heal placeholder sentinel.** When a widget is added (or "Use precise device location" is tapped) before GPS resolves, `ConfigActivity` saves `DEFAULT_LAT/LON` + `LocationMode.FOLLOW_DEVICE`. `LocationUpdater.allWidgetsAtDefault()` uses "equals these coords (or unset)" as the signal that GPS never resolved, so the background heal keeps trying. **Keep this *behavior*, but drop the Google-HQ coordinates as its representation.**

2. **Bug — last-resort fetch/render coordinate.** When nothing resolves, `ActiveLocationResolver.resolve()` returns `DEFAULT_LAT/LON` and the worker **queries live weather APIs for Google HQ**, then `WeatherWidgetWorker.getLocationName()` labels it **"Mountain View, CA"**. A user with no resolvable location is shown Google-HQ weather as if it were their own. **Remove this entirely.**

Resolution: replace the coordinate sentinel with an explicit **"no location / unset"** state, migrate installs that already have the sentinel on disk, propagate unset through the fetch path, and render an error on the widget when it's reached.

---

## 2. Design

### 2a. No real coordinate is ever a placeholder
The "unset" state is represented by the **absence of finite coordinates** (key absent / `NaN`), which the code already supports on the read side:
- `ActiveLocationResolver.current()` — `contains()` check then `isFinite()` (ActiveLocationResolver.kt:16-19)
- `WidgetLocationStore.stored()` — same pattern (WidgetLocationStore.kt:31-34)
- `LocationUpdater.allWidgetsAtDefault()` — already treats NaN/absent as default (LocationUpdater.kt:63)

We stop *persisting* Google-HQ coords as the placeholder. The write-side primitive already exists:
**`WidgetLocationStore.clearWidget(widgetId)`** (WidgetLocationStore.kt:47).

### 2b. Nullable active-location resolution
`ActiveLocationResolver.resolve()` becomes `Pair<Double, Double>?` — `null` when there is genuinely no location (no canonical active location, no configured widget location, no latest weather). It must no longer persist the sentinel during the one-time migration.

### 2c. Worker is the single gate
`WeatherWidgetWorker.doWork` (all modes that need a location) checks the resolved location:
- **null → do not fetch; render the no-location error to every widget; log; return success.** No network, no backfill, no schedule. (UI-only repaint from an empty cache would show the error too.)
- **non-null → current pipeline.**

The view handlers' `?: DEFAULT_LAT/LON` fallbacks become unreachable in the no-location case (the worker never paints data for a null location). We still replace them defensively (§7) — but defensively means *degrade the feature*, never blank the widget.

### 2d. Error message
Reuse the existing minimal-error render pattern (`WidgetRenderer.updateWidgetError`, WidgetRenderer.kt:100), with a new user-facing string. Add a `WidgetPushDispatcher.Origin.NO_LOCATION` for diagnostics.

---

## 3. Core edits

### 3.1 `widget/ActiveLocationResolver.kt`
- `resolve(...)` return type → `Pair<Double, Double>?`.
- Remove the `?: (WeatherWidgetWorker.DEFAULT_LAT to DEFAULT_LON)` fallback (L53) in the `resolved` chain.
- Guard the one-time-migration `persist(...)` (L55) so it only writes when `resolved` is non-null. Never write a fake coordinate to the canonical `active_weather_location` prefs.
- Add a public `clear(context)` (promote the body of `clearForTesting`, L31) — §4's migration and §5's ConfigActivity paths both need it.

### 3.2 `widget/WeatherWidgetWorker.kt`
- Delete `DEFAULT_LAT`/`DEFAULT_LON` from the companion (L771-772) — **but see §4: they survive one release as `private` legacy constants used only by the migration.**
- `getLocationName()` (L756): delete the `"Mountain View, CA"` branch. The remaining body already degrades correctly — `FriendlyLocationName.cached(...) ?: "%.2f, %.2f".format(lat, lon)` never fabricates a city. **Do not** make it nullable; that ripples for no benefit.
- Gate every `resolve()` call site on null → `renderNoLocationErrorToAll()` + `Result.success()` without fetching:
  - **L146** `handleCurrentTempOnlyWork`
  - **L245** the non-active-sources current-temp path *(missed in rev 1)*
  - **L318** `handleFullSyncWork`
  - **L619** `refreshWidgetsFromCache` — render error and return, skip fetch
- `WorkInput` backfill defaults (WorkInput.kt:50-51): backfill must not target Google HQ. See §6.

### 3.3 `widget/WidgetRenderer.kt`
- Add `updateWidgetNoLocation(context, appWidgetManager, appWidgetId, origin)` modeled on `updateWidgetError` (L100-127): `text_container` VISIBLE, `graph_view` GONE, `day2_low` = `R.string.widget_no_location`. Keep it in the same "must never itself throw" style.
- Guard the `locationLat`/`locationLon` chain (L172-183): if all four sources are null → bail to the no-location render rather than defaulting.

### 3.4 `widget/WidgetPushDispatcher.kt`
- Add `NO_LOCATION` to `Origin` (L38-51).

### 3.5 `res/values/strings.xml`
- Add `widget_no_location` (e.g. `No location — tap to set`).
- Existing strings to reuse where apt: `no_location_set` (L247), `obs_no_location_to_refresh` (L258).
- `location_fix_failed_default` (L244) says "Using default." — reword; there is no default anymore.

---

## 4. Upgrade migration — the sentinel already on disk ⚠️

**This is the highest-risk part of the change and was absent from rev 1.**

Existing installs already have `37.4220`/`-122.0841` persisted in:
1. `active_weather_location` prefs (`ActiveLocationResolver` `latitude`/`longitude` floats)
2. per-widget `weather_widget_prefs` floats (`widget_lat_<id>` / `widget_lon_<id>`)

Once §5 drops the `lat == defaultLat && lon == defaultLon` check from `allWidgetsAtDefault`, those
coordinates read as **finite, non-NaN, and therefore legitimate**. The consequences:

- `allWidgetsAtDefault()` returns false → the GPS auto-heal **stops trying**
- `ActiveLocationResolver.current()` returns them → the §2c null gate **never fires**, so no error shows
- Net effect: the user is **permanently pinned to Mountain View with healing disabled** — strictly
  worse than today's behavior, for exactly the population this fix targets.

### The migration
One-time, on first run after upgrade (versioned flag in `weather_prefs`, e.g. `legacy_default_cleared_v1`):

1. If `ActiveLocationResolver.current()` is same-site the legacy sentinel → `ActiveLocationResolver.clear()`.
2. For every widget id, if `WidgetLocationStore.stored(id)` is same-site the legacy sentinel →
   `WidgetLocationStore.clearWidget(id)`.
3. Log a `LOCATION_MIGRATION cleared=<n>` row so the rollout is observable in `app_logs`.

### Two constraints on the comparison
- **Use `LocationMatch.sameSite`, never `==`.** The codebase already learned this: `HourlyObservationBackfill.kt:44-46` documents that the old `==` guard silently missed because the coordinate had been 3-dp quantized (−122.0841 → −122.084). Prefs also round-trip through `Float`, which loses precision independently.
- **Keep the constants for one release.** Move them to `private const LEGACY_DEFAULT_LAT/LON` in the migration's own file. This **supersedes rev 1's §5 "grep must return zero hits" and §6 "delete the constants"** — those two instructions were in direct conflict with the migration and are corrected in §8 below.

### Not affected
`historical_pois` should be clean: the sentinel was always saved with `label = null`
(ConfigActivity.kt:358, 579-588), and `LocationUpdater.recordHistoricalPoi` only runs when
`label != null`. Verify with a one-off check on a real device DB before shipping, but no migration
step is planned for it.

---

## 5. Auto-heal placeholder — keep behavior, drop Google-HQ coords

Replace "the placeholder equals Google HQ" with "the placeholder is *unset*", so healing still works without any real coordinates.

### `ui/ConfigActivity.kt`
- **L358** (`LocationFixFlow.Outcome.Default`, manual path): currently `saveChosenLocation(DEFAULT_LAT, DEFAULT_LON, null, FOLLOW_DEVICE)`. Replace with a `saveNoLocation(FOLLOW_DEVICE)` that records FOLLOW_DEVICE and clears coordinates (`ActiveLocationResolver.clear` + `WidgetLocationStore.clearWidget`).
- **L578-583** (`completeWidgetAddOnExit`, `cancelledPendingCheck` branch) and **L584-589** (`else` branch): same — write unset instead of Google-HQ coords.
- **L571-573** (pinned / prefetched-fix paths) are fine — they use a real fix.
- Update the KDoc at L555-556 ("otherwise the FOLLOW_DEVICE default placeholder") to describe the unset representation.

### `ui/LocationUpdater.kt`
- **L44** `shouldHealTo`: the `?: (DEFAULT_LAT to DEFAULT_LON)` default encodes "unset should be healed". Keep the intent — `stateManager.getWidgetLocation(id) == null` → return true (needs heal) — without comparing against Google HQ.
  - ⚠️ Note `getWidgetLocation` → `WidgetLocationStore.resolve()` (WidgetLocationStore.kt:23) falls back through `deltaStore.legacyLocation()` **and `historicalPoiFallback()`**. So a widget with no stored coords can still resolve to a POI. Decide deliberately whether heal-eligibility should use `resolve()` or the stricter `stored()`. **Recommendation: `stored()`** — heal eligibility is about what the user actually configured, not what we can infer.
- **L54-65** `allWidgetsAtDefault`: drop the `lat == defaultLat && lon == defaultLon` check; "at default" = absent/NaN only. Blocked on §4's migration landing first (or in the same commit).
- **L96** `effectiveLocation`: final fallback returns Google HQ. Make `EffectiveLocation` nullable / add an `Unknown` case; `describe()` (L119-144) then renders `R.string.no_location_set` plus the mode suffix instead of formatting fake coordinates.
- **L193** `proposeFollowDeviceLocation` *(missed in rev 1)*: `?: (DEFAULT_LAT to DEFAULT_LON)` supplies `activeLocation` to `LocationHandoffStore.propose`. Unset must mean "any fresh fix is an improvement" — check what `propose` does with a null active location and make that path explicit rather than defaulting.

### `widget/GpsResampler.kt` (L100) & `handlers/WidgetRefreshContextResolver.kt` (L40)
- Remove `?: DEFAULT_LAT/LON`; treat null/unset as "no location" (skip resample / skip context resolution) instead of defaulting to a coordinate. `GpsResampler` already has a skip-with-outcome idiom (`GPS_RESAMPLE outcome=…`) — add `outcome=skipped_no_location`.

---

## 6. `HourlyObservationBackfill` — the one *correct* use of the constant

*(Missing entirely from rev 1. A mechanical grep-sweep would delete a working safety check.)*

`resolveBackfillLocation` (HourlyObservationBackfill.kt:52-59) uses the constant as a **guard**: a
widget location same-site as Googleplex returns `BackfillLocation.Unanchored("unanchored_default_location")`,
which correctly skips the fetch.

Under the new design this **simplifies** — `BackfillLocation.Unanchored` already encodes exactly the
right concept:

```kotlin
internal fun resolveBackfillLocation(widgetLocation: Pair<Double, Double>?): BackfillLocation {
    if (widgetLocation == null) return BackfillLocation.Unanchored("unanchored_no_widget_location")
    val (lat, lon) = widgetLocation
    return BackfillLocation.Anchored(LocationMatch.quantize(lat), LocationMatch.quantize(lon))
}
```

- Delete the sentinel branch **only after §4's migration guarantees no live install still carries those coords** — otherwise a pre-migration widget would start backfilling observations at Google HQ.
- Rewrite the KDoc at L43-46 (it documents the `==`-vs-`sameSite` history; preserve that lesson as a note on the migration in §4 rather than losing it).
- Update `HourlyObservationBackfillLocationTest` — the `unanchored_default_location` case goes away.
- Same reasoning applies to `WorkInput.kt:50-51`: `getDouble(KEY_BACKFILL_LAT, DEFAULT_LAT)` must default to `NaN`, and the backfill path must treat non-finite as `Unanchored`.

---

## 7. Defensive sweep — degrade the feature, never blank the widget

These are unreachable for a null location once §3 gates the worker, but each is a latent
"fake-coordinate" trap.

**⚠️ Rev 1 said to replace each with `?: return@…` (abort rendering). That is the wrong remedy.**
These fallbacks fire only when the backing list is empty, and they feed *optional* features:

| Site | What the coordinate feeds | Correct degradation |
|---|---|---|
| `TemperatureStateResolver.kt:107-108` | `SunPositionUtils.getSunInfo` | skip day/night shading |
| `CloudCoverViewHandler.kt:219-220, 538-539` | sun position | skip day/night shading |
| `PrecipViewHandler.kt:164-165, 514-515` | sun position | skip day/night shading |
| `TemperatureTouchTargets.kt:189-190` | sun position | skip day/night shading |
| `TemperatureHourDataBuilder.kt:181-182` | sun position | skip day/night shading |
| `DailyViewHandler.kt:203-204` | `ClimateGapFiller.cachedNormalsByMonthDay` | skip normals; PARTIAL rows stay partial |
| `TemperatureViewHandler.kt:115-118, 312-313` | location for site unification | propagate null to caller |
| `WidgetRenderer.kt:177, 183` | site unification | covered in §3.3 — bail to no-location render |

Aborting the render in these handlers would blank a widget that today renders fine minus icon
accuracy — adding a new path to the codebase's existing family of "widget went blank" bugs. Since
§2c already guarantees the worker never paints for a null location, these are **pure defense in
depth, and defense in depth must not be able to blank the UI.**

Make the sun-position input nullable and skip the shading. Do not abort.

- `widget/DataFreshness.kt:159-160` & `widget/UIUpdateScheduler.kt:39-40`: treat null as "no data" — skip the freshness check / skip scheduling — so no MV fetch occurs. No DEBUG-only default; a dev-only fake coordinate is how this bug got in.

---

## 8. Deleting the constants (revised)

Rev 1 said "grep returns zero hits in `app/src/main`" and "delete the constants". **Both are wrong
while the migration exists.** The corrected end state:

- **This release:** `DEFAULT_LAT`/`DEFAULT_LON` are removed from `WeatherWidgetWorker.Companion` and
  reappear as `private const LEGACY_DEFAULT_LAT/LON` inside the §4 migration file only.
  Acceptance grep: `grep -rn "DEFAULT_LAT|DEFAULT_LON|Mountain View" app/src/main` returns hits in
  **exactly one file** (the migration).
- **A later release:** once telemetry (`LOCATION_MIGRATION`) shows the migration has run everywhere
  that matters, delete the migration and the legacy constants. Then the zero-hits grep applies.
- Never reintroduce a DEBUG-only default coordinate.

---

## 9. Test impact & risks

- **Build locally.** Rev 1 claimed this "cannot be compiled in this environment (no Android SDK)" — that is **incorrect**. `local.properties` points at `/home/dcar/.Android/Sdk` with platforms 34/35/36 installed. Use `./gradlew installDebug` and `./gradlew testDebugUnitTest`; instrumented via `./scripts/emulator-tests.sh` (never `connectedDebugAndroidTest`).
- **Upgrade/migration test (highest value, add first).** Seed the old Google-HQ coords into both `active_weather_location` and per-widget prefs, run the migration, assert: coords cleared, `allWidgetsAtDefault()` true, heal re-enabled, no-location render reached. This is the regression that is *invisible to fresh-install testing* and hits every existing user.
- **Existing tests referencing the constant** — grep the whole tree, not just main. Known: `ActiveLocationResolverTest`, `LocationUpdaterTest`, `ConfigActivityRobolectricTest`, `HourlyObservationBackfillLocationTest`, `AddWidgetIntegrationTest`, `LocaleSwitchIntegrationTest`, `DailyHistoryClickIntegrationTest`, `DailyFutureDayNoHourlyClickIntegrationTest`, `DailyMainColumnVsBottomIconClickTargetIntegrationTest`.
  - Note the separate class of tests that merely *use* 37.422 as an arbitrary test coordinate (`SunPositionDiagnosticTest`, `CurrentTempRepositoryPoiTest`, `ForecastRepositoryHourlyChangeTest`, …). Those are unrelated and must not be swept.
- **`resolve()` nullable ripple**: 5 call sites in `main` (WeatherWidgetWorker ×4 — L146/245/318/619 — and `WidgetRefreshContextResolver` L40) plus 4 in `ActiveLocationResolverTest`. *(Rev 1 said "7 call sites".)*
- **No-location UX test:** instrumented test that places a widget with no resolvable location and asserts the no-location view (text visible, `graph_view` GONE, message = `widget_no_location`) and that no network fetch is enqueued.
- **Risk of over-healing:** ensure `allWidgetsAtDefault` triggers only for genuinely unset widgets and never for a user's real location that happens to be near Google HQ. Criterion is "key absent/NaN", never coordinate proximity. The `sameSite` comparison belongs **only** in the one-time migration, never in the steady-state heal check.
- **Desktop:** unaffected — verified: a repo-wide `grep -rn "DEFAULT_LAT|DEFAULT_LON" --include=*.kt` hits only files under `app/`. Nothing in `:desktop` or `:shared`.

---

## 10. Suggested commit sequence

1. `Add widget_no_location string + NO_LOCATION origin + updateWidgetNoLocation renderer` — no behavior change.
2. **`Migrate installs off the legacy Google-HQ sentinel coordinates`** — §4, standing alone and shipped *before* anything depends on it. Includes the upgrade test.
3. `Make ActiveLocationResolver.resolve() nullable; gate the worker on no-location` — §3.1 + §3.2 **in one commit**. Rev 1 split these; they cannot be split, because making the return type nullable forces every caller to handle null immediately or insert `!!` that the next commit deletes.
4. `ConfigActivity/LocationUpdater: represent placeholder as unset, not Google-HQ coords` — §5. Safe now that step 2 has cleared the old coords.
5. `HourlyObservationBackfill/WorkInput: unset means unanchored` — §6.
6. `Handlers: degrade gracefully instead of defaulting to a coordinate` — §7.
7. `Remove DEFAULT_LAT/DEFAULT_LON from WeatherWidgetWorker; drop Mountain View label` — §8 first stage.

*(Deferred, a later release: delete the migration + legacy constants once rollout telemetry confirms.)*

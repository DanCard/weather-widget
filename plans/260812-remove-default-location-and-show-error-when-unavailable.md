# Remove hardcoded Mountain View default & show error when no location

**Status:** Plan · **Target:** H1 from `plans/260812-code-review-refresh-coordination.md`
**Goal:** Delete the Google-HQ hardcoded default/fallback location (`WeatherWidgetWorker.DEFAULT_LAT/LON` = 37.4220, -122.0841). When no location is resolvable, the widget shows an explicit "no location" error instead of silently fetching/labeling Mountain View weather.

---

## 1. Problem statement

`DEFAULT_LAT/LON` (= Google HQ) plays **two distinct roles** today, and only one of them is a bug:

1. **Legit — auto-heal placeholder sentinel.** When a widget is added (or "Use precise device location" is tapped) before GPS resolves, `ConfigActivity` saves `DEFAULT_LAT/LON` + `LocationMode.FOLLOW_DEVICE`. `LocationUpdater.allWidgetsAtDefault()` uses "equals these coords (or unset)" as the signal that GPS never resolved, so the background heal keeps trying. **Keep this *behavior*, but drop the Google-HQ coordinates as its representation.**

2. **Bug — last-resort fetch/render coordinate.** When nothing resolves, `ActiveLocationResolver.resolve()` returns `DEFAULT_LAT/LON` and the worker **queries live weather APIs for Google HQ**, then `WeatherWidgetWorker.getLocationName()` labels it **"Mountain View, CA"**. A user with no resolvable location is shown Google-HQ weather as if it were their own. **Remove this entirely.**

Resolution: replace the coordinate sentinel with an explicit **"no location / unset"** state, propagate that through the fetch path, and render an error on the widget when it's reached.

---

## 2. Design

### 2a. No real coordinate is ever a placeholder
The "unset" state is represented by the **absence of finite coordinates** (key absent / `NaN`), which the code already partially supports (`ActiveLocationResolver.current()` returns null for non-finite values; `allWidgetsAtDefault()` treats NaN/absent as default). We simply stop persisting Google-HQ coords as the placeholder.

### 2b. Nullable active-location resolution
`ActiveLocationResolver.resolve()` becomes `Pair<Double, Double>?` — `null` when there is genuinely no location (no canonical active location, no configured widget location, no latest weather). It must no longer persist the sentinel during the one-time migration.

### 2c. Worker is the single gate
`WeatherWidgetWorker.doWork` (all modes that need a location) checks the resolved location:
- **null → do not fetch; render the no-location error to every widget; log; return success.** No network, no backfill, no schedule. (UI-only repaint from an empty cache would show the error too.)
- **non-null → current pipeline.**

The view handlers' `?: DEFAULT_LAT/LON` fallbacks become unreachable in the no-location case (the worker never paints data for a null location). We still replace them defensively (see §4).

### 2d. Error message
Reuse the existing minimal-error render pattern (`WidgetRenderer.updateWidgetError`), with a new user-facing string (e.g. `widget_no_location` = "No location — tap to set"). Add a `WidgetPushDispatcher.Origin.NO_LOCATION` for diagnostics.

---

## 3. Core edits

### 3.1 `widget/ActiveLocationResolver.kt`
- `resolve(...)` return type → `Pair<Double, Double>?`.
- Remove the `?: (WeatherWidgetWorker.DEFAULT_LAT to DEFAULT_LON)` fallback in the `resolved` chain.
- Remove the one-time-migration `persist(...)` of the sentinel (do not write a fake coordinate to the canonical `active_weather_location` prefs). If a migration write is still desired for pre-sentinel installs, persist only when `resolved` is non-null.

### 3.2 `widget/WeatherWidgetWorker.kt`
- Delete `DEFAULT_LAT`, `DEFAULT_LON`, and the `"Mountain View, CA"` branch in `getLocationName()`. Make `getLocationName` render `"%.2f, %.2f"` (coordinate string) instead of a fake city; better, return a nullable name so no city is ever fabricated.
- In `handleCurrentTempOnlyWork` (L146) and `handleFullSyncWork` (L318): resolve location; if **null**, call `renderNoLocationErrorToAll()` and return `Result.success()` without fetching. `refreshWidgetsFromCache()` (L619) — if null location, render error and return (skip fetch).
- Guard `DEFAULT_LAT/LON` usage in `WorkInput` (backfill default) — backfill must not target Google HQ; skip backfill when coords are the unset sentinel.

### 3.3 `widget/WidgetRenderer.kt`
- Add `updateWidgetNoLocation(context, appWidgetManager, appWidgetId, origin)` modeled on `updateWidgetError`, setting `day2_low` to `R.string.widget_no_location`.
- Guard the `locationLat/locationLon` chain (L172-182): if all sources null → bail to the no-location render rather than defaulting.

### 3.4 `widget/WidgetPushDispatcher.kt` (add to `Origin`)
- Add `NO_LOCATION`.

### 3.5 `res/values/strings.xml`
- Add `widget_no_location` (e.g. `No location — tap to set location`). Existing `no_location_set` / `obs_no_location_to_refresh` can be reused where apt.

---

## 4. Auto-heal placeholder — keep behavior, drop Google-HQ coords

Replace "the placeholder equals Google HQ" with "the placeholder is *unset*", so healing still works without any real coordinates.

### `ui/ConfigActivity.kt`
- L358 (`LocationFixFlow.Outcome.Default` path): currently `saveChosenLocation(DEFAULT_LAT, DEFAULT_LON, null, FOLLOW_DEVICE)`. Replace with a new `clearChosenLocation()` / `saveNoLocation(FOLLOW_DEVICE)` that records FOLLOW_DEVICE + an unset (NaN/absent) coordinate.
- L579-586 (`completeWidgetAddOnExit` fallback branch): same — write unset instead of Google-HQ coords.
- (L571-573 pinned path is fine — it uses a real fix.)

### `ui/LocationUpdater.kt`
- `shouldHealTo` (L39): the `?: (DEFAULT_LAT to DEFAULT_LON)` default means "unset should be healed"; keep that intent, but represent unset explicitly (e.g. treat missing location as "needs heal") rather than comparing against Google-HQ coords.
- `allWidgetsAtDefault` (L54): drop the `lat == defaultLat && lon == defaultLon` check; "at default" = absent/NaN only. Healing then triggers for any genuinely unset widget.
- `effectiveLocation` (L96): final fallback should return an **unset/unknown** (not Google HQ). Callers that format it for the summary label must render "No location set" (string already exists).

### `widget/GpsResampler.kt` (L100) & `WidgetRefreshContextResolver.kt` (L40) & `WorkInput.kt`
- Remove `?: DEFAULT_LAT/LON`; treat null/unset as "no location" (skip resample / context resolution) instead of defaulting to a coordinate.

---

## 5. Defensive sweep — remove `?: DEFAULT_LAT/LON` from handlers

These are unreachable for a null location once §3 gates the worker, but each is a latent "fake-coordinate" trap. In each, replace `x ?: DEFAULT_LAT` with `x ?: return@…` (abort rendering/processing) or propagate null:

- `handlers/CloudCoverViewHandler.kt` (L219-220, L538-539)
- `handlers/DailyViewHandler.kt` (L203-204)
- `handlers/PrecipViewHandler.kt` (L164-165, L514-515)
- `handlers/TemperatureHourDataBuilder.kt` (L181-182)
- `handlers/TemperatureStateResolver.kt` (L107-108)
- `handlers/TemperatureTouchTargets.kt` (L189-190)
- `handlers/TemperatureViewHandler.kt` (L115-118, L312-313)
- `widget/WidgetRenderer.kt` (L177, L183) — covered in §3.3
- `widget/DataFreshness.kt` (L159-160) & `widget/UIUpdateScheduler.kt` (L39-40): fall back to the default coords only in DEBUG; otherwise treat as "no data" (skip the freshness check / skip scheduling) so no MV fetch occurs.
- `widget/GpsResampler.kt` (L100) — covered above.

After the sweep, grep `DEFAULT_LAT|DEFAULT_LON|Mountain View|37.4220|-122.0841` should return **zero** hits in `app/src/main`.

---

## 6. Delete the constants

Remove `DEFAULT_LAT` / `DEFAULT_LON` from `WeatherWidgetWorker.Companion` once all references are gone (blocked on §3–§5). Keep `DEBUG`-only behavior if a developer build genuinely wants a known default (document it as dev-only, never shipped).

---

## 7. Test impact & risks

- **Existing tests referencing `DEFAULT_LAT/LON` or "Mountain View"** must be updated (search both `app/src` and `desktop/`; the constant is also referenced in tests — grep `DEFAULT_LAT` across the whole tree, not just main).
- **`ActiveLocationResolver.resolve()` nullable change** ripples to all 7 call sites (§3.2, §3.4, §4). Update the migration/instrumented tests for `ActiveLocationResolver`, `ConfigActivity`, `LocationUpdater`, and the worker.
- **No-location UX test:** add an instrumented test that places a widget with no resolvable location and asserts the no-location error view (text visible, graph GONE, message = `widget_no_location`), and that no network fetch is enqueued.
- **Risk of over-healing:** with the sentinel removed, ensure `allWidgetsAtDefault` triggers only for genuinely unset widgets and not for a user's real location that happens to be near Google HQ. Use "key absent/NaN" as the criterion, never coordinate proximity.
- **Build gate:** this cannot be compiled in this environment (no Android SDK). Each change must be reviewed and built in CI before release.

---

## 8. Suggested commit sequence (keep history reviewable)

1. `Add widget_no_location string + NO_LOCATION origin + updateWidgetNoLocation renderer` (no behavior change yet).
2. `Make ActiveLocationResolver.resolve() nullable; drop Google-HQ fallback` + update callers/tests.
3. `Worker: render no-location error and skip fetch when location is null`.
4. `ConfigActivity/LocationUpdater: represent placeholder as unset, not Google-HQ coords`.
5. `Handlers: replace ?: DEFAULT_LAT/LON with abort-or-null`.
6. `Delete DEFAULT_LAT/DEFAULT_LON; remove Mountain View label; grep-clean`.

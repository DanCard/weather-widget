# Plan: Make the widget's location track the phone, not a stale pref

## Context — why this change

Two co-located phones (Pixel, Samsung), both charging all day, reported different
"yesterday high" for the same day (Pixel 72.7°, Samsung 72.0°). Root cause, confirmed by
diffing the device databases and the `DAILY_HISTORY_BLEND` app_logs:

- The daily high is an **inverse-distance-weighted (IDW) blend** of nearby stations,
  weight ∝ `1/distanceKm²` (`ActualTemperatureSeriesBuilder.kt:448`). The dominant nearby
  station is a **warm personal station "AE6EO"** (stored ID `AW020`, ~75°, name
  "AE6EO MOUNTAIN VIEW").
- Each device stamps `distanceKm` onto observations **at fetch time, from whatever location
  the fetch used**. Pixel fetched from a real GPS-derived 37.417/-122.089 → AE6EO at 2.24 km;
  Samsung fetched from the **hard default** (37.4220/-122.0841 Googleplex) → AE6EO at 2.94 km.
  IDW weight for AE6EO drops ~42%, cooling Samsung's blended high by ~0.7°.

Behind that sit two structural defects:

1. **Split-brain fetch location.** The main forecast fetch resolves location
   widget-prefs-first (`WeatherWidgetWorker.kt:149-153`), but the current-temp (`:520`),
   non-primary (`:610`), and cache-refresh (`:708`) paths, plus
   `WidgetIntentRouter.resolveLocation` (`:65-73`), resolve `getLatestLocation()`-first — a
   self-referential echo of "wherever the last row was stamped." The observation-stamping
   fetch follows that, so it can bake in a location that disagrees with what the display uses.
2. **Stale latch never re-heals.** GPS is only re-sampled one-shot in `MainActivity`
   (`:198-227`), gated on `LocationUpdater.allWidgetsAtDefault` — it fires only while *every*
   widget still sits at the hard default. Once a widget holds any non-default value (even a
   wrong/stale one like Samsung's), it never re-checks. And it's a **home-screen widget** —
   there is no regular "open the app" moment to trigger even that.

Intended outcome: the location used to fetch/stamp observations matches the phone's actual
position and stays current autonomously, so co-located installs converge on the same high.

## Constraints (from the user)

- Location must reflect **where the phone actually is** (live GPS), not a stale/default pref.
- Battery matters, but **not while charging** (both phones were charging all day).
- **Piggyback on the existing battery-aware fetch tiers — no new wakeups.**

### Actual fetch tiers (code, not the stale CLAUDE.md table)
`BatteryTier.kt` + `BatteryFetchStrategy.kt`:

| State | Fetch interval | GPS re-sample (this plan) |
|---|---|---|
| Charging / plugged / ≥100% | 30 min | **Active GPS** |
| On battery > 70% | 240 min (4 h) | **Active GPS** (phone may be moving; marginal cost only — wakeup+radio already sunk) |
| On battery > 50% | 480 min (8 h) | Skip |
| ≤ 50% | no fetch (`null`) | none |

`isEffectivelyCharging` counts STATUS_CHARGING/FULL, plugged>0, or level≥100
(`BatteryStatePolicy.kt`).

---

## Approach

### Part 1 — Unify the fetch location source
Introduce a single accessor so every fetch path stamps observations with the same location
the display already resolves (`getWidgetLocation`/S6 first). No such shared accessor exists
today; the precedence is inlined at `WeatherWidgetWorker.kt:149-153`.

Add `ActiveLocationResolver` (widget package, alongside `BatteryStatePolicy`), pure of view
logic, keyed on `(WidgetStateManager, ForecastDao)`:

```
getAppWidgetIds(...).firstNotNullOfOrNull { stateManager.getWidgetLocation(it) }   // S6 prefs
  ?: forecastDao.getLatestWeather()?.let { it.locationLat to it.locationLon }       // S2
  ?: (WeatherWidgetWorker.DEFAULT_LAT to DEFAULT_LON)                               // S3
```
(Standalone object, not a `WeatherRepository` method, because `WidgetIntentRouter` already
holds `WidgetStateManager` + `ForecastDao` but not a repository.)

Route through it:
- `WeatherWidgetWorker.kt:149-153` — replace inline block (pure refactor, keep log line).
- `:520`, `:610`, `:708` — replace `getLatestLocation() ?: DEFAULT` (**behavior change: the fix**).
- `WidgetIntentRouter.resolveLocation`/`resolveRefreshContext` (`:65-87`) — take lat/lon from
  the resolver but keep `fetchedAt` from `getLatestWeather()` for the staleness check at `:85`;
  update the `@VisibleForTesting LocationResult` accordingly.

Do **not** touch render-side handlers — they already prefer `getWidgetLocation`; this makes
the fetch side match them. Leave `LocationMatch` untouched (write-keys stay 3dp-quantized).

### Part 2 — GPS re-sample piggybacked on the charging + >70% tiers
`WeatherWidgetWorker.doWork` already has `isPlugged` (`:78`) and `batteryLevel` (`:86`) and
computes the tier. When **`isPlugged || batteryLevel > BatteryTier.TIER_HIGH_THRESHOLD`**,
as part of the fetch that's already running:
1. Actively sample GPS (`FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY,
   token)`, awaited in the coroutine worker).
2. If the fix is **not** `LocationMatch.sameSite(fresh, storedWidgetLoc)` (~200 m, so
   stationary jitter never rewrites), propagate via `LocationUpdater.applyToAllWidgets(...)`
   (updates all widget prefs + historical_pois + force-enqueues a fetch).

Runs in the background worker, so it needs the **`ACCESS_BACKGROUND_LOCATION` runtime grant**
— already declared in the manifest (`AndroidManifest.xml:8`), just never requested today.
Request it at **widget placement** in `ConfigActivity` (already a foreground GPS moment). If
not granted, degrade to passive `getLastLocation` (best-effort) — never crash. On battery
≤70% or unplugged-low, do not sample (no fetch runs anyway at ≤50%).

No new alarm/job/wakeup: the sample rides the existing 30-min (charging) / 4-h (>70%) fetch.

### Part 3 — Relax the stale-latch heal gate
So a stale-but-non-default location (Samsung's exact case) can be corrected, not just the hard
default. In `LocationUpdater` add `shouldHealTo(context, freshLat, freshLon): Boolean` that
scans widgets for the currently-resolved location and returns `!LocationMatch.sameSite(...)`
(unit-testable; encapsulates the rule). Replace both `allWidgetsAtDefault` checks in
`MainActivity.maybeAutoHealLocationFromGps` (`:203`, `:211`) with it, and reuse it for the
Part 2 worker decision. This preserves "never overwrite a deliberately chosen location": for a
stationary user the chosen location *is* `sameSite` with the GPS fix, so nothing is rewritten;
only a genuinely-wrong (>200 m) pref gets corrected.

### Part 4 — Diagnostic logging
`ObservationRepository.recomputeDailyExtremesForDay` builds `DAILY_HISTORY_BLEND` at
`:620-624` and already has `latitude`/`longitude` in scope (the user location that produced
every station's `distanceKm`). Append `userLat=$latitude userLon=$longitude` to the line, so a
future cross-install divergence is one-glance: identical station distances follow from
identical user location; a diff in the high traces straight to a diff in the logged location.

## Sequencing
1. Part 4 (log) — isolated, land first for observability.
2. Part 1 (`ActiveLocationResolver` + 4 call-site swaps) — core fix.
3. Part 3 (gate → `shouldHealTo`) — pairs with Part 1 to correct Samsung's stored pref.
4. Part 2 (GPS piggyback) — depends on Part 3's `shouldHealTo`; background-grant request in `ConfigActivity`.

## Risks
- `getWidgetLocation`'s fallback chain (`WidgetStateManager.kt:773-804`) can return a
  historical_pois / delta-pin location; after Part 1 the current-temp paths follow that same
  chain the main fetch + renderers already use — verify no *third* distinct location appears.
- `refreshWidgetsFromCache` (`:708`) may read an empty cache right after a location change;
  `applyToAllWidgets` force-enqueues a fetch, so confirm no blank-widget flash.
- Background GPS is throttled on Android 10+; 30-min/4-h cadence is within limits, but the fix
  can be null — always degrade gracefully.
- Heal thrash near the 200 m boundary — bounded by `sameSite` 0.002° box + 3dp write-quantize;
  watch `DAILY_HISTORY_BLEND` for repeated location flips.

## Critical files
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt` (fetch paths
  `:149-153, :520, :610, :708`; `isPlugged` `:78`, `batteryLevel` `:86`; new resolver + GPS piggyback)
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt` (`resolveLocation`/`resolveRefreshContext` `:65-87`)
- `app/src/main/java/com/weatherwidget/ui/LocationUpdater.kt` (`allWidgetsAtDefault` `:30-41` → `shouldHealTo`; `applyToAllWidgets` `:47-82`)
- `app/src/main/java/com/weatherwidget/ui/MainActivity.kt` (`maybeAutoHealLocationFromGps` `:198-227`)
- `app/src/main/java/com/weatherwidget/ui/ConfigActivity.kt` (background-location grant request at placement)
- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt` (`DAILY_HISTORY_BLEND` log `:620-624`)
- New: `app/src/main/java/com/weatherwidget/widget/ActiveLocationResolver.kt`
- Reuse (no edit): `WidgetStateManager.getWidgetLocation` (`:773-804`),
  `shared/.../LocationMatch.kt` (`sameSite`), `BatteryStatePolicy`/`BatteryTier`.

## Verification (connected emulator + Pixel + Samsung; app is debuggable)
1. **Baseline:** `python3 scripts/backup_databases.py`, then per device query
   `observations` for `AW020` `distanceKm` (expect the 2.24 vs 2.94 km split) and
   `daily_history.highTemp` (72.7 vs 72.0).
2. **Force a charging-tier fetch:** `adb shell dumpsys battery set ac 1`; trigger the widget
   refresh broadcast (or `cmd jobscheduler run -f com.weatherwidget <id>`); `dumpsys battery reset`.
3. **Divergent fix:** `adb emu geo fix <lon> <lat>` ~1 km off the stored pref → confirm
   `widget_lat_<id>`/`widget_lon_<id>` (via `run-as cat shared_prefs/...xml`) get rewritten;
   confirm a sub-200 m jitter does **not** rewrite (thrash guard).
4. **Post-fix diff:** re-run backup; assert both devices now show **identical**
   `observations.locationLat/locationLon`, identical `distanceKm` for `AW020`, and identical
   `daily_history.highTemp`.
5. **Log:** `SELECT message FROM app_logs WHERE tag='DAILY_HISTORY_BLEND' ORDER BY timestamp
   DESC LIMIT 5;` — confirm new `userLat/userLon` present and equal across installs.
6. **No new wakeups:** `adb shell dumpsys jobscheduler | grep com.weatherwidget` unchanged;
   unit-test `shouldHealTo` (sameSite true → no heal; >200 m → heal) and `ActiveLocationResolver`
   precedence.

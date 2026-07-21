# Fix observation location fragmentation (3 root causes)

**Date:** 2026-07-21
**Status:** Implemented + verified live 2026-07-21. All fixes done; C/B unified at the enqueuer
via `resolveBackfillLocation` (authoritative `getWidgetLocation`, null→skip, sameSite-default→skip,
quantize), A via `ObservationEntity.withQuantizedLocation()` at write boundaries. Emulator confirmed:
post-fix NWS writes land at `37.417` (device key), HQ cluster receives no more writes. No migration
shipped (heals on next fetch, as planned). Tests: `HourlyObservationBackfillLocationTest` (7, green).
**Scope:** Pre-existing bug on the current build; unrelated to the fetch-first change
(`260721-fetch-both-nws-web-prefer-newest.md`). Do not conflate the two branches.

## Symptom

Emulator "Current observations" activity lists nothing for NWS, despite 6,564 observation rows in
the DB with fresh NWS readings (KPAO 09:47). The device never moves, yet `observations` holds one
physical location under **four** coordinate keys:

| Key | Origin |
|-----|--------|
| `37.416797637939453, -122.08899688720703` | raw GPS/geocoded fix, full `double` (Open-Meteo/Tomorrow write verbatim) |
| `37.417, -122.089` | same fix, 3-dp quantized (`LocationMatch.quantize`) |
| `37.422, -122.0841` | `DEFAULT_LAT/LON` (Googleplex), written raw by a fallback |
| `37.422, -122.084` | the default, 3-dp quantized (`-122.0841 → -122.084`) |

`WeatherObservationsActivity.loadObservations` scopes to the device location and collapses the box
result with `LocationMatch.selectNearestSite`, which correctly drops the `37.422` HQ cluster — where
the fresh NWS rows actually live — so NWS filters to empty.

## Root causes (all position-independent — a stationary emulator reproduces them)

**A. Inconsistent write precision.** Some writers persist the coordinate verbatim
(`buildObservationEntity`: `locationLat = latitude`, full double → `37.416797…`), others quantize to
3 dp. Room keys on the stored number; `selectNearestSite` splits at `SAME_SITE_TOLERANCE_DEG =
0.002°`. Same spot, two keys, purely from rounding. `observations` is NOT run through
`LocationMatch.quantize()`, unlike `hourly_forecasts`/`forecasts`.

**B. `DEFAULT_LAT/LON` fallback writes a phantom cluster.** The pervasive idiom
`dataList.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT` (in `TemperatureStateResolver`,
`DailyViewHandler`, `TemperatureHourDataBuilder`, `TemperatureTouchTargets`, `UIUpdateScheduler`,
`LocationUpdater`) resolves to Googleplex whenever its list is empty. When the NWS proximity box is
empty (`reason=no_nws_observations`), the enqueuer fetches and writes fresh NWS obs at HQ, ~0.7 km
from the real fix — a permanent fragment. **Self-perpetuating:** the empty box is *caused by* the
fragmentation, and defaulting to HQ reinforces it (`OBS_HOURLY_BACKFILL_DONE lat=37.422 lon=-122.084`).

**C. The sentinel guard is defeated by quantization.** The prior fix
(`HourlyObservationBackfill.kt:91`) skips when `lat == DEFAULT_LAT && lon == DEFAULT_LON`. But it is
exact-equality against the pristine constant `-122.0841`, while the coordinate flowing through has
been 3-dp quantized to `-122.084` (it now lives in `hourly_forecasts` rows, so `firstOrNull()`
returns it as if it were real data). `-122.084 ≠ -122.0841`, so the guard never fires — the logs show
`OBS_HOURLY_BACKFILL_RUN … lat=37.422 lon=-122.084` sailing past it.

B and C share a cure (an explicit "is this an anchored location?" signal); A is independent.

## Existing infrastructure to reuse (do not reinvent)

- `LocationMatch.quantize(coord)` — 3-dp write-key rounding. `WRITE_QUANTIZE_DECIMALS = 3`.
- `LocationMatch.sameSite(lat1,lon1,lat2,lon2)` — `0.002°` "same physical site".
- `LocationMatch.selectNearestSite(...)` — read-path collapse to nearest site.
- `LocationUpdater.EffectiveLocation(..., isWidgetLocation: Boolean)` — already distinguishes a real
  widget location from the default fallback (line 87 returns `isWidgetLocation = false`). This is the
  anchored signal B/C need; thread it instead of inventing a new one.
- Precedent: `widget_fetch_location_decoupled` (worker prefers `getWidgetLocation`),
  `hourly_backfill_default_location_fragment` (the same bug, first fix).

## Fix

### Fix C — guard on an explicit anchored flag, not coordinate equality (do first; smallest, stops the bleed)

Primary: give `maybeEnqueueHourlyObservationBackfill` an `anchored: Boolean` (or pass
`EffectiveLocation`) and `return` with `reason=unanchored_default_location` when `!anchored`, instead
of comparing coordinates. The two callers already know whether their `lat/lon` came from real data or
the `?: DEFAULT` branch — surface that instead of throwing the information away and re-deriving it
from a lossy coordinate compare.

Fallback (if threading the flag is too invasive for one pass): replace `==` with a quantization-safe
compare — `LocationMatch.sameSite(lat, lon, DEFAULT_LAT, DEFAULT_LON)` — so the quantized default
`37.422/-122.084` is still recognized. **Risk to document:** a user genuinely within ~200 m of
Googleplex would be treated as unanchored (inherent to using a real place as the sentinel; the
exact-match had a narrower version of the same flaw). The anchored-flag approach avoids this entirely
— prefer it.

Regression test: `HourlyObservationBackfillTest` — assert skip for the **quantized** default
`(37.422, -122.084)`, not just the raw constant, and (flag version) assert fetch proceeds for a real
`(37.417, -122.089)` marked anchored.

### Fix B — resolve fetch location authoritatively, never from the data being backfilled

At every observation *fetch/enqueue* site, resolve `lat/lon` from the widget's authoritative location
(`WidgetStateManager.getWidgetLocation(id)` / `LocationUpdater.EffectiveLocation`), not from
`observations.firstOrNull()?.location ?: DEFAULT`. Callers to audit (the `?: DEFAULT_LAT` idiom):
`TemperatureStateResolver:415`, `DailyViewHandler:515`, and the resolvers feeding them
(`TemperatureHourDataBuilder:172`, `TemperatureTouchTargets:185`). Rendering-only fallbacks to
DEFAULT (showing *something* at HQ) can stay; only the paths that **fetch/write** must not.

### Fix A — quantize every coordinate-keyed write through one choke point

Route all `observations` writes through `LocationMatch.quantize()` for `locationLat/locationLon`:
`buildObservationEntity`, the synthetic `NWS_BLEND` row, and the Open-Meteo/Tomorrow observation
writers (currently the raw `37.416797…` source). Ideally quantize once at the entity boundary so no
writer can forget. Confirm the fetch/query `lat/lon` is quantized consistently with the write so
`INSERT … REPLACE` overwrites instead of accumulating a per-precision fragment.

Test: assert a `buildObservationEntity` (and blended row) built from `37.416797…` stores
`37.417/-122.089`; assert two writes at `37.41680` and `37.41684` collapse to one key.

## Existing-fragment cleanup

New writes converge after B/C, and `selectNearestSite` already drops the stale HQ cluster on read, so
Current Observations heals as soon as fresh NWS obs are written at the real (quantized) location. The
frozen HQ rows age out under the 1-month retention. **A migration is optional** — include a one-time
re-anchor/purge of `stationId != NWS_BLEND` NWS rows at the quantized-default key only if we want
instant healing rather than next-fetch healing. Default recommendation: skip the migration; verify
healing on the next fetch.

## Verification (device-driven, per project workflow)

1. Build + `installDebug` to the emulator (do **not** clear app data).
2. Pull `weather_database` (+ `-wal`/`-shm`); confirm new NWS obs write at the real quantized key
   (`~37.417`), not `37.422`.
3. Grep `app_logs`: `OBS_HOURLY_BACKFILL_RUN` now shows the real `lat/lon` (or a
   `unanchored_default_location` skip), never `lat=37.422 lon=-122.084`.
4. Open "Current observations" — NWS rows now list. Screenshot (convert PNG→JPG per CLAUDE.md).
5. Assert no NEW rows accumulate at `37.422/-122.084` after a few refresh cycles.

## Ordering & rollout

C → B → A. C stops the active bleed immediately with a tiny change; B removes the source of the bad
coordinate; A hardens against the raw-vs-quantized split so it cannot recur through a new writer.
Each is independently shippable and independently testable. No schema version bump needed unless the
optional cleanup migration is included.

## Risks / notes

- Googleplex false-positive in the Fix-C *fallback* (tolerance compare) — avoided by the anchored-flag
  primary approach.
- `DEFAULT_LAT/LON` remains a *rendering* fallback; this plan only stops it from anchoring **fetches**.
- Watch for other coordinate-keyed tables with the same raw-write gap (daily_history, climate_normals)
  — out of scope here but worth a follow-up audit; same `LocationMatch.quantize()` choke point applies.

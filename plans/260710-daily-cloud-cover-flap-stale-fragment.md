# Daily view cloud cover flaps between renders (stale coordinate fragment wins on refresh path)

**Date:** 2026-07-10
**Status:** IMPLEMENTED & VERIFIED 2026-07-10 — `unifyToNearestSite` added to
`GraphDataLoader` and applied at all three raw loads in `WidgetIntentRouter`;
`WidgetRenderer` refactored to use it. Unit tests green
(`GraphDataLoaderUnifyToNearestSiteTest`, `DailyNoonCloudCoverTest`). On-device: after
install, `refresh_action_cache_first` renders show `ratio=0.65 hourlyRows=226`, identical
to `onUpdate` — flap gone.
**Symptom:** Samsung daily forecast view showed Monday (2026-07-13) with low cloud cover
compared to desktop and emulator, then repeatedly "changed to match" and flipped back
within minutes while being watched.

## Plain-English explanation

It is NOT different weather stations, and nothing is being asked live — both renders read
from the **same NWS forecast cache in the phone's local database**. The problem is *which
cached copy* they read.

**The phone has three cached copies of the NWS forecast, from three slightly different
places.** Every fetch stores forecast rows keyed by the phone's coordinates at that moment.
On July 8 the phone was at one spot (`37.39,-122.081`), on July 9 at another (`37.424`),
and since then at the current spot (`37.417`). Each got its own set of rows. Only the
current one keeps getting refreshed — the other two are frozen snapshots of whatever NWS
was predicting *back when they were fetched*.

**The forecast itself changed.** On July 8, NWS thought Monday would be fairly sunny (25%
cloud at noon). Last night NWS revised that to 65%. The current-location cache has the new
65% prediction; the frozen July-8 cache still says 25% — not because that place has
different weather, but because it's a two-day-old prediction that never got updated.

**The widget has two render paths, and only one is careful about this.** A repaint queries
"all hourly forecast rows near me", and "near me" is a box big enough to catch all three
cached locations. One path correctly narrows to the current location's rows → 65%. The
other path skips the narrowing, gets all three sets mixed together, and takes whichever
Monday-noon row comes first in the list — the stale 25% one. The two paths run
back-to-back on every update cycle, so the widget flips: correct 65%, then a second later
overwritten with stale 25%, over and over.

Same forecast provider, same actual forecast — one path shows NWS's *current* opinion of
Monday, the other shows NWS's *two-day-old* opinion still sitting in the cache from when
the phone was half a mile away. (The coordinates aren't "stations": NWS forecasts any
lat/lon; the app just uses coordinates as the cache key, so small changes in where the
phone was create separate cache entries. Desktop and emulator agree with each other
because each has only ever been "at" one location — nothing stale to pick up.)

## Root cause (technical)

The widget renders the DAILY view through **two different code paths that disagree about
which hourly rows to use**, and they alternate on every update cycle:

| Path | Hourly data | Monday noon cloud | Log fingerprint |
|------|-------------|-------------------|-----------------|
| `onUpdate` (startup batch) | Unified to nearest site via `WidgetRenderer.kt:164-178` (`LocationMatch.sameSite` filter) | **65%** (correct) | `dailyTodayInputs … hourlyRows=227`, `ratio=0.65` |
| `refresh_action_cache_first` (`WidgetIntentRouter.refreshDailyView`) | Raw `hourlyDao.getHourlyForecasts()` at `WidgetIntentRouter.kt:789` — **no site unification** | **25%** (stale) | `hourlyRows=474`, `ratio=0.25` |

The raw proximity-box query returns rows from **three coordinate fragments** present in the
Samsung DB (GPS quantization sites from real device movement):

- `37.417,-122.089` — current site, fetched 2026-07-10 08:51, Monday noon cloud = **65**
- `37.424,-122.088` — frozen at 2026-07-09 12:48, noon = 31
- `37.39,-122.081`  — frozen at 2026-07-08 10:45, noon = **25** ← this row wins

`DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent`
(`shared/src/main/kotlin/com/weatherwidget/shared/util/DailyNoonCloudCover.kt:40-48`) does
`.filter { source }.firstOrNull()` on rows matching noon — **no site disambiguation, no
fetchedAt ordering** — so with an unmerged list the July-8 fragment's `noon=25` row beats
the fresh `noon=65` row purely by list order. The resolver silently depends on its caller
having collapsed fragments first.

Because the cache-first repaint runs *last* in each cycle (it follows `onUpdate` by 1-5 s),
the widget **settles in the wrong state**, flipping briefly to correct on each `onUpdate`.

### Why it surfaced now

NWS raised Monday's cloud forecast in the 2026-07-09 ~21:00 model run (noon 31% → 65%;
visible in `hourly_forecast_history` buckets). Until then the fragments roughly agreed, so
the flap was invisible. Desktop and emulator don't flap: their DBs have no stale fragment
inside the proximity box (the emulator's extra sites are in Austin, TX).

### Diagnostic evidence (Samsung SM-F936U1, 2026-07-10)

```
09:05:21  path=onUpdate                    hourlyRows=227  resolveNoonCloudCoverRatio ratio=0.65
09:05:22  path=refresh_action_cache_first  hourlyRows=474  resolveNoonCloudCoverRatio ratio=0.25
```
Same widget, same NWS display source, identical `dataLoc=37.41700,-122.08900` — only the
row set differs (474 ≈ two sites' worth of NWS rows, unmerged).

This is the same fragmentation family as the earlier hourly quantize+Selector and
in-memory-pin fixes: **any raw DAO proximity read that bypasses the shared merge/unification
resurrects the bug.**

## Fix

### 1. Extract the site unification into a shared helper (primary)

`WidgetRenderer.kt:164-178` already contains the proven logic (pick the coordinate pair
nearest the widget location, keep every row at that same physical site via
`LocationMatch.sameSite`). Extract it:

```kotlin
// GraphDataLoader.kt
/**
 * Collapse a raw proximity-box query result to the single physical site nearest
 * (lat, lon). Sub-precision fragments of that site are kept (sameSite box); genuinely
 * different markers (stale sites from earlier GPS fixes, e.g. 37.39 vs 37.417) are
 * dropped — their frozen forecasts otherwise win firstOrNull-style selections downstream
 * (DailyNoonCloudCover picked a 2-day-old noon cloud row over the fresh one).
 */
fun unifyToNearestSite(
    rows: List<HourlyForecastEntity>,
    lat: Double,
    lon: Double,
): List<HourlyForecastEntity> {
    val best = rows.asSequence()
        .map { it.locationLat to it.locationLon }
        .distinct()
        .minByOrNull { (rLat, rLon) -> abs(rLat - lat) + abs(rLon - lon) }
        ?: return rows
    return rows.filter { LocationMatch.sameSite(it.locationLat, it.locationLon, best.first, best.second) }
}
```

Apply it at:

- `WidgetIntentRouter.refreshDailyView` — wrap the load at `WidgetIntentRouter.kt:789`:
  `GraphDataLoader.unifyToNearestSite(hourlyDao.getHourlyForecasts(...), lat, lon)`
- `WidgetRenderer.kt:164-178` — replace the inline block with the helper (behavior-neutral
  refactor; keeps one source of truth).

### 2. Audit the other raw loads in `WidgetIntentRouter` (secondary)

- `WidgetIntentRouter.kt:474` (`getDailyActuals` today-live path) — raw
  `getHourlyForecasts` feeds `aggregateObservationsToDailyBySource`. Apply the same
  unification; low visible impact but same latent hazard.
- `WidgetIntentRouter.kt:520` (`getHourlyForecastsBySource`) — check whether the consumer
  unifies; if not, wrap likewise.
- `GraphDataLoader.loadCurrentTempResolutionHourlyForecasts` (`GraphDataLoader.kt:160`) —
  used for current-temp interpolation; verify its consumers
  (`CurrentTempResolver`) already scope by site, fix if not.

Note: `loadGraphWindowHourlyForecasts` (hourly graph view) already filters
`LocationMatch.sameSite` + runs `HourlyForecastStitcher` — no change needed there.

### 3. Optional hardening in the shared resolver

`DailyNoonCloudCover` currently cannot break ties (its `HourlyForecast` mapping drops
`fetchedAt`). If we ever want defense in depth: carry `fetchedAt` through
`mapHourlyForecastsForNoonCloud` and pick `maxByOrNull { it.fetchedAt }` among noon
candidates. Not required once callers unify; skip unless touching that file anyway.

## Tests

Per repo testing strategy (no mocking framework — pure-function extraction):

- **`unifyToNearestSiteTest`** (plain JUnit, app unit tests): three fragment sites
  (37.417 fresh / 37.424 / 37.39 stale) with distinct noon `cloudCover` (65/31/25);
  widget location 37.41681,-122.08892 → expect only 37.417 rows survive. Include the
  sub-precision case (37.4168014 vs 37.4168434 both kept) and the empty-list case.
- **Regression at the symptom level**: `DailyNoonCloudCover` fed the *unified* list returns
  65; fed the raw 3-site list returns whichever sorts first (documents the hazard the
  caller must prevent) — mirrors `DailyPartlyCloudyFloorTest` placement in `:shared`.

## Verification on device

1. `./gradlew installDebug` (Samsung `RFCT71FR9NT` attached).
2. Tap the widget's refresh action; then let an `onUpdate` cycle pass.
3. `adb logcat -d | grep 'resolveNoonCloudCoverRatio: date=2026-07-13'` — ratio must stay
   identical (0.65) across `path=onUpdate` and `path=refresh_action_cache_first` renders;
   `dailyTodayInputs hourlyRows` must match across passes.
4. Visual: Monday bar's cloud split stable across refreshes and matching desktop/emulator.

## Related

- Memory: `daily_noon_cloud_refresh_path_unmerged`, `hourly_coordinate_fragmentation_fix`,
  `hourly_inmemory_location_pin_fragmentation`, `shared_location_match_predicate`
- The stale fragments themselves are legitimate (device was elsewhere on 07-08/07-09);
  1-month retention will age them out. The bug is selection, not storage.

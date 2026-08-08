# daily_history actuals: both values from `/stations/{id}/observations`

Date: 2026-08-08
Follows `plans/260808-nws-actuals-forecast-contamination.md`, which removed the forecast-as-actual
writers and introduced the per-day NWS station pull. This plan extends that pull to also produce the
**blend**, so both stored actuals for a past day come from NWS's own observation endpoint.

User direction:
> "For blend, should use user preference. If it is 5% of personal station, then that calc should be
> part of blend. Should use 5 closest stations including personal stations, not just official
> stations."
> "For computed high low temp in daily history table, should use blend including personal stations
> from /stations/{id}/observations"

## Problem

`computedHighTemp`/`computedLowTemp` are blended from the stored `observations` table, which is
**not** the NWS API:

| `api` = NWS, 3-day sample | rows | Synoptic (`isWebFallback`) |
|---|---|---|
| all stations | 692 | 327 (47%) |
| AW020 (PWS, 2.22 km) | 78/day | 76 |
| KNUQ (3.83 km) | 66/day | 48 |

Synoptic arrives via the prefer-newest latest-observation path, which exists to keep the *current*
temperature fresh. For a past day it just means the historical blend is assembled from whatever the
fetch cadence happened to capture.

### The stored pool is measurably thinner than the endpoint

Live `/stations/{id}/observations`, 2026-08-06, one day:

| Station | endpoint | stored | endpoint range | stored range |
|---|---|---|---|---|
| **AW020** | **144 readings** | 81 rows (78 Synoptic) | 62.0–77.0 | 63.0–77.0 |
| LOAC1 | 24 | 24 (0 Synoptic) | 61.0–81.0 | 60.0–81.0 |
| KNUQ | 72 | 67 (50 Synoptic) | 60.8–75.2 | 60.8–75.2 |

The nearest station to the user is under-sampled by 44% and its low is off by 1 °F.

**Correction to the previous plan.** It recorded that the blend should *not* move to the endpoint,
partly on the claim that AW020's API coverage was ~2 readings/day. That was inferred from the stored
`isWebFallback` split rather than measured, and is wrong — the endpoint serves 144/day. The
objection does not hold.

## What changes

### 1. Pull all 5 nearest stations, not official-only

Revert the official-only filter added earlier today (`NwsApiDailyActualsFetcher.MAX_STATIONS` +
`type == OFFICIAL`) and the nearest-first lazy short-circuit. The blend needs every station,
including personal ones, so all 5 must be fetched. Cost returns to 5 requests per missing day.

Per-day windows stay (the endpoint caps at 500 features and returns the newest — see the previous
plan).

### 2. One pull produces both values

`NwsDailyExtremesFetch.resolveForDates` returns a richer result per date:

```kotlin
data class DailyActualsFromStations(
    val blendHigh: Float,
    val blendLow: Float,
    val station: StationDailyExtremes.StationDailyExtreme?,  // null when no official station qualifies
)
```

- **Blend** — delegates to `ActualsAggregator.aggregate(...)` over the pooled readings with the
  caller's `personalStationWeight`. Reusing `aggregate` rather than reimplementing keeps one blend
  implementation; the only difference from the live path is the input pool.
- **Station extreme** — unchanged: `StationDailyExtremes.resolve`, nearest OFFICIAL station passing
  the coverage guard.
- Hourly forecasts for the day are passed through for `extrapolateForward`. With a complete series
  there are few gaps, so extrapolation rarely fires — a side benefit, since it is the blend's one
  path back to the forecast.

### 3. Persist both, and freeze the day

`DailyActualsStore.persistNwsStationActuals` → `persistNwsDailyActuals`, writing
`computedHighTemp`/`computedLowTemp` alongside `apiHighTemp`/`apiLowTemp`/`apiStationId`/
`apiStationDistanceKm`.

**The blend is only overwritten when the pull actually produced one** — a failed or empty pull must
never replace a good stored blend with a worse one.

`persistExtremes` gains a guard: for a **past** date whose stored row already has
`apiStationId != null`, keep the stored `computedHighTemp`/`computedLowTemp` instead of overwriting
with the stored-pool recompute. Without this the ordinary recompute (which runs on widget loads and
history-screen opens) would immediately undo the API-derived blend. `apiStationId != null` is the
marker that a day's actuals have been resolved from the endpoint.

Today's row is untouched by all of this — the live blend keeps using the stored pool plus Synoptic,
because that is what makes the current temperature fresh, and it is recomputed on the widget render
path where a network call does not belong.

### 4. Trigger unchanged

Still keyed on NWS rows in the last 7 days missing an api actual. A past day is therefore pulled
exactly once: the pull fills both values, `apiStationId` becomes non-null, the date drops out of the
missing set, and the freeze guard keeps the recompute from touching it again.

## Testing

Shared, pure, no mocking framework:

- `NwsDailyExtremesFetchTest` — extend:
  - returns both a blend and a station extreme from one day's pull.
  - the blend includes personal stations: a PWS-only pool still yields a blend, while `station` is
    null (no official station to satisfy the api actual).
  - `personalStationWeight` changes the blend: the same pool at weight 1.0 vs 0.0 must produce
    different values, and weight 0.0 must land on the official stations' answer.
  - a day whose pull is empty yields no entry at all (neither value written).
  - existing per-day-request and lookback tests still hold.
- `StationDailyExtremesTest` — unchanged; the official-only rule still governs the api actual.

Android:

- `NwsStationActualsStoreTest` — extend:
  - `persistNwsDailyActuals` writes blend and api values together across all same-date fragments.
  - a past row with `apiStationId != null` keeps its blend across a `recomputeDailyExtremesForDay`.
  - a past row with `apiStationId == null` is still recomputed normally (guard must not over-apply).
  - today's row is always recomputed, even when `apiStationId` is set.

Desktop: mirror the freeze guard in `DesktopApiActualsMergeTest`.

Prove the freeze guard can fail by removing it and re-running.

## Risks

- **Overwriting history.** The blend for past days changes value on first pull. That is the point,
  but it means stored accuracy numbers shift once. Expected magnitude is small (AW020's low moves
  1 °F); the widget's past-day bars will move correspondingly.
- **Request cost.** 5 requests per missing day, normally 1 day/cycle = 5/day. A cold 7-day backfill
  is 35 requests, spread over one fetch cycle.
- **The freeze marker is implicit.** `apiStationId != null` means "resolved from the endpoint". If a
  future writer sets that column for another reason the guard would misfire. A dedicated boolean
  would be explicit but costs a migration; revisit if a second writer appears.

## Found during implementation

**The hourly lookup tripped `HourlyProximityQueryAllowlistTest`.** The blend needs the day's hourly
forecasts for `extrapolateForward`, and the first cut called `hourlyForecastDao.getHourlyForecasts`
directly from `NwsApiDailyActualsFetcher`. That raw proximity-box read spans every cached
coordinate site in the box, and feeding un-collapsed fragments into a blend is precisely the
coordinate-fragmentation bug family the guard polices. Fixed properly rather than by widening the
allowlist: the lookup moved to `DailyActualsStore.nwsHourlyForecastsForDay`, wrapped in
`GraphDataLoader.unifyToNearestSite`, which is the sanctioned chokepoint and already allowlisted.

**Emulator testing caught a bug this plan's own risk section had half-anticipated.** The plan said
"a failed or empty pull must never replace a good stored blend with a worse one" — the first
implementation guarded against *empty* but not *partial*, and a partial day is the common case at
the edge of the window. The endpoint's retention is a rolling window from now, so the oldest
in-range day arrives truncated at the current wall-clock hour: at 2026-08-08 09:00 every station's
2026-08-01 series began at hour 09, the overnight minimum had aged out, and the blend wrote a low
of 64.13 over a correct stored 58.95 — **5.18 °F too warm**.

Fixed with `poolCoversDay`, a pool-level equivalent of `StationDailyExtremes`' per-station guard
(same window constants, but any station type counts, since the blend may legitimately rest on
personal stations). A day that does not span both windows now writes nothing at all. This also
made an earlier test's fixture invalid — the "blend but no station extreme" case now needs pool
coverage split across two stations, which is the more precise expression of it anyway.

**A test inverted, correctly.** `a day whose stations fail the coverage guard is absent from the
result` no longer holds: the coverage guard governs only the single-station api actual, so a thin
day now yields a blend with `station = null`. Rewritten to assert exactly that.

## Out of scope

- Today's live blend, the current-temperature path, and the Synoptic prefer-newest policy.
- Non-NWS sources: their blends still come from `<SOURCE>_MAIN` rows.
- The accuracy baseline setting and `ActualsBaselineResolver`.

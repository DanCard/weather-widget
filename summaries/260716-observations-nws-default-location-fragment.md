# Current Observations empty for NWS — backfill wrote obs under DEFAULT location

## Symptom (2026-07-16, emulator-5554)

The Current Observations screen showed "No recent observations found for NWS" even though the DB
held 1,652 fresh NWS observation rows fetched minutes earlier.

## Key finding: NWS obs stored at a different coordinate than the widget

Two location clusters in the last-24h observations:

| Location            | Rows | Sources                                  | Correct? |
|---------------------|------|------------------------------------------|----------|
| `37.4168, -122.089` | 226  | Open-Meteo / Silurian / Tomorrow.io      | ✓ (widget/GPS) |
| `37.422, -122.0841` | 1652 | AW020, KSJC, KNUQ, KPAO, LOAC1 (all NWS) | ✗ (= DEFAULT_LAT/LON) |

`WeatherObservationsActivity.loadObservations` scopes to the widget location and calls
`LocationMatch.selectNearestSite`, which — as designed (its doc uses "37.422 vs 37.4168" as the
textbook *different-marker* case) — keeps the nearest cluster (non-NWS) and drops the 0.7 km-away
NWS cluster. The NWS source filter then yields nothing. The temperature graph looked fine because
its ±0.1° box query still returns the fragment.

## Root cause

`HourlyObservationBackfill.maybeEnqueueHourlyObservationBackfill` derived its fetch location from
`observations.firstOrNull()?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT`. The decisive trigger
is `no_nws_observations`, in which case the list is empty (or holds only other sources), so it fell
back to `DEFAULT_LAT/LON` (Google HQ, `37.422/-122.0841`) — a *plausible* coordinate ~0.7 km from
the real GPS fix, so it failed silently instead of erroring. Confirmed by
`OBS_HOURLY_BACKFILL_DONE lat=37.422 lon=-122.0841`. Self-perpetuating: the enqueuer then re-derived
location from the already-fragmented rows, rewriting them at 37.422 on every refresh.

## Fix

Two parts:

1. Thread the widget's resolved `lat/lon` (both callers — `TemperatureStateResolver`,
   `DailyViewHandler` — already hold it and use it for `getObservationsInRange`) into
   `maybeEnqueueHourlyObservationBackfill` and fetch under it. Never derive fetch location from the
   data being backfilled. Removed the `DEFAULT_LAT/LON` fallback inside the enqueuer.

2. **Defensive guard** (added after the fix didn't fully hold): both callers still resolve their own
   `lat/lon` as `<dataList>.firstOrNull()?.locationLat ?: DEFAULT_LAT` — so a ghost/unconfigured
   widget id (found live: 6 bound widgets, only 3 configured; ids 136/138 had no stored location)
   renders with an empty list and collapses to the DEFAULT sentinel, and the backfill fetched NWS
   there anyway. `maybeEnqueueHourlyObservationBackfill` now skips with
   `reason=unanchored_default_location` when `lat == DEFAULT_LAT && lon == DEFAULT_LON` (real GPS data
   is never exactly the constant — same sentinel test as `WeatherWidgetWorker.kt:816`). A backfill
   with no location to anchor to has nothing meaningful to write, so it must not fetch at a guess.

## Verification

Live on emulator-5554 (after the user removed the extra widgets and the guard was installed): the
Current Observations station list displays NWS stations again — the recurring `37.422` writes stopped
(ghost-widget backfills now skip as `unanchored_default_location`), so NWS obs land at `37.4168` with
the rest of the data and the location-scoped screen finds them.

General lesson: a "plausible default" fallback coordinate fails silently where a NaN/error would fail
loudly. See [hourly_backfill_default_location_fragment], [snapshot_paths_must_select_a_site],
[location_box_admits_stale_nearby_site].

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

Thread the widget's resolved `lat/lon` (both callers — `TemperatureStateResolver`,
`DailyViewHandler` — already hold it and use it for `getObservationsInRange`) into
`maybeEnqueueHourlyObservationBackfill` and fetch under it. Never derive fetch location from the data
being backfilled. Removed the `DEFAULT_LAT/LON` fallback.

## Verification

`:app:compileDebugKotlin` clean. Not live-verified per request (user moved on). Self-heals on-device
within ~1h: after the fix, the next gap-triggered backfill writes NWS obs at `37.4168`; the stale
`37.422` rows stop being refreshed and age out of the 24h window, after which `selectNearestSite`
picks the `37.4168` cluster that now contains NWS.

General lesson: a "plausible default" fallback coordinate fails silently where a NaN/error would fail
loudly. See [hourly_backfill_default_location_fragment], [snapshot_paths_must_select_a_site],
[location_box_admits_stale_nearby_site].

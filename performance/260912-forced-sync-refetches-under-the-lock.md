# Forced sync re-fetches all sources 70 ms after an identical fetch finished

Follow-up to `plans/260912-cancelled-temperature-render-pushes-blank-body.md`. Same incident, the
"four syncs in ten seconds" thread.

## What ran after the 12:09:34 location move (Pixel 7 Pro, 26091001)

| # | Sync | Network | Verdict |
|---|------|---------|---------|
| 1 | `on_update_stale` (unforced, started 0.3 s before the move landed) | none — battery gate not due (156 min < 240 min at 77 %) | 17 s local pipeline for Mountain View incl. a 12.7 s synoptic fetch for the site just left. Bounded; not addressed here. |
| 2 | `location_changed` `currentOnly=true` | current temp | fine |
| 3 | forced `unspecified` from the move | **5 sources, 14.7 s** (`NET_FETCH_START` 12:09:34.957 → `COMPLETE` 12:09:49.599) | fine — new site, no rows |
| 4 | forced `hourly_gaps` requested 12:09:44.569 | **5 sources, 10.1 s, `NET_FETCH_START` 12:09:49.667** — 70 ms after #3 completed, same site, same sources | **the duplicate** |
| 5 | `obsBackfillOnly` 12:09:59 | NWS station history | separate 30-min cooldown; fine |
| 6–7 | WM re-runs 12:10:20 after the flap | #4's re-run was de-forced (`FORCED_REFRESH_SATISFIED`) | fine |

Net: one duplicate full fetch per move whenever any forced request lands while another fetch is in
flight. `hourly_gaps` is the usual second requester because a fresh site's first paint always
reports gaps; on 26091001 those were *past* hours (`spans=[09-12 03:00..05:00]`) that no live
fetch fills — that loop was closed 2026-09-11 by `ElapsedForecastBackfill` and is not re-fixed
here. Any other forced requester (source toggle, Settings → Refresh, day-tap no-hourly follow-up)
hits the same window.

## Root cause

The staleness time-limit exists and is double-checked under the lock — for unforced requests only:

```kotlin
// ForecastRepository.getWeatherData, inside syncMutex.withLock
if (!forceRefresh && !fetchCoordinator.requiresNetworkFetch(...)) return cached
if (!forceRefresh && timeSinceLastFetch < MIN_NETWORK_INTERVAL_MS && ...) return cached
```

`forceRefresh` bypasses every check, so a forced caller waits for the in-flight fetch to release
`syncMutex` and then fetches again unconditionally.

The one check that understands *request time* — `WeatherWidgetWorker.dropSatisfiedForce`
(`requestedAtMs` vs last success, via `ForcedRefreshSatisfaction.isSatisfied`) — runs at worker
start, before the mutex wait. At 12:09:44.5 the fetch had not completed → "not satisfied" → force
kept → 5 s later under the lock nobody asks again.

## Fix

Ask the question where the decision is made: **under the lock, against the fetch that just
released it.**

1. `ForecastRepository` keeps an in-memory `lastCompletedFetch` (site, target, sources actually
   fetched, completion time), written next to `NET_FETCH_COMPLETE` under `syncMutex`. In-memory on
   purpose: the race is between two coroutines contending for the same process-local mutex.
2. `ForcedRefreshSatisfaction.isSatisfiedByCompletedFetch(requestedAtMs, lastFetch, lat, lon,
   targetSourceId)` — pure: `completedAt > requestedAt` (same rule as `isSatisfied`), same site
   via `LocationMatch.sameSite`, and source-compatible: an untargeted request needs an untargeted
   fetch; a targeted one needs its source in the fetched set (an untargeted fetch skips throttled
   non-primary sources, so "all" does not imply "this one"). Unstamped requests never satisfy.
3. `getWeatherData(..., requestedAtMs)`: after the lock re-reads the cache, a forced request the
   prior fetch satisfies logs `NET_FETCH_COALESCED reason=satisfied_under_lock requestedAt=
   fetchCompletedAt= fetched= target=` and returns the cache.
4. `WeatherRepository` passes it through; `FullSyncPipeline` passes `input.requestedAtMs`.
   `dropSatisfiedForce` stays as the cheap early exit at worker start.

**Why completion time and not row stamps.** The first cut compared `requestedAt` against the
site's newest `ForecastEntity.fetchedAt`. It passed its tests and failed the on-device race:
`fetchedAt` is set when each entity is *built* mid-fetch, so a request landing in the last few ms
of a fetch (emulator 14:46:47.603, `NET_FETCH_COMPLETE` 14:46:47.627) is newer than every row
the fetch produced and re-fetches anyway. It would also fail whenever the snapshot store
deduplicates unchanged rows. Completion time is what the worker's `dropSatisfiedForce` already
means by "satisfied", so the two checks now agree.

## Tests

- `ForcedRefreshSatisfactionTest` +7 (pure, ShortDuration): completes-after satisfies; the
  24-ms-before-completion case satisfies; completes-before does not; other site does not;
  targeted needs its source fetched; untargeted not satisfied by a targeted fetch; no prior fetch /
  unstamped never.
- `ForecastRepositoryForcedCoalesceRoboTest` (Robolectric + in-memory Room, relaxed API mocks so a
  "fetch" is `NET_FETCH_START`→`COMPLETE` with no rows — which is the point): a first forced
  unstamped call fetches; a second with `requestedAt < completedAt` coalesces (1 start, 1
  `NET_FETCH_COALESCED`); `requestedAt > completedAt` fetches (control); unstamped fetches; other
  site fetches; target not in fetched set fetches. Sequential is faithful: every pre-lock exit is
  `!forceRefresh`, so a forced call can only skip the fetch through the new branch.
- Sweep: repository package + `FullSync*` + `WeatherWidgetWorker*` + `WidgetDataBundleLoader*`:
  47 classes, 200 tests, 0 failures.

## On-device (emulator, 26091201 + fix, 14:51)

Recreated the incident's pairing: ConfigActivity → Use Coordinates (fires
`LocationUpdater.enqueueForceRefresh`, the non-unique unstamped force) then Settings → Refresh
data 0.5 s later (stamped, `WORK_NAME_ONE_TIME`).

```
14:51:41.605 NET_FETCH_START force=true                      ← location move
14:51:42.058 SYNC_START reason=settings_manual_refresh force=true   (requestedAt=…902027)
14:51:43.914 NET_FETCH_COMPLETE durationMs=2403
14:51:43.938 NET_FETCH_COALESCED reason=satisfied_under_lock requestedAt=1789249902027
             fetchCompletedAt=1789249903913 fetched=NWS,SILURIAN,TOMORROW_IO,OPEN_WEATHER_MAP target=all
14:51:44.5   SYNC_SUCCESS ×2
```

Before the fix the same recipe (14:46:47) produced a second `NET_FETCH_START` 4 ms after the
first completed. Not run on the Pixel: the pairing needs a location change, and the phone's
location is the user's.

**Also seen on the emulator, not fixed here:** after the location fetch, the first paint logged
`TEMP_GAPS_REFRESH … spans=[09-12 06:00..09-12 07:00]` and forced a 4-source re-fetch (10.5 s) at
14:37:06 — requested *after* the fetch completed, so correctly not coalesced. `ElapsedForecastBackfill`
did store NWS's 7 elapsed grid hours, but the WIDE window looks back 9 h, and the re-fetch offered
the same 7 (`covered=7 stored=0`). The detector counts past hours a live fetch can never fill and
will re-request every 15 min for the rest of the day. Separate plan.

## Not done

- Aborting #1 when the location changes under a running pipeline. 17 s of mostly local work once
  per move; revisit if it shows up again.
- `LocationUpdater.enqueueForceRefresh` is a plain non-unique `enqueue` with no request stamp. It
  is the legitimate fetch in this story, so the coalesce never needs to apply to it; stamping it
  would let it yield when *it* is the one queued behind an equivalent fetch.
- `TEMP_GAPS_REFRESH` forcing a fetch for past hours (above).

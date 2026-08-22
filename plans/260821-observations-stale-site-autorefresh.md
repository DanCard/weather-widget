# Stale Current Observations: auto-refresh on triggers, relaxed station reads, backfill coordinate guard

**Date:** 2026-08-21
**Device evidence:** RFCT71FR9NT (Samsung SM-F936U1), 2026-08-21 19:17–20:35
**Priority order (user): Phase C first, then A, then B.**

## Problem

The Current Observations screen and the hourly graph observation label ("KNUQ 68 @ 6:55 pm")
showed data over an hour old while the device was plugged in. The screen self-repaired at
20:35 when an unrelated fetch happened to write under the right key — the user asks that this
repair happen faster, or proactively.

### Evidence timeline (from pulled `weather_database` + `app_logs`)

| Time | Event |
|---|---|
| all day | Device at home site `37.4168,-122.0890` ("Avery Drive"); obs fragment `37.417,-122.089` |
| 19:29:56 | `GPS_RESAMPLE outcome=candidate_detected trigger=worker lat=37.4241 lon=-122.0883 label=Amphitheatre Parkway` — phone physically moved to Googleplex (~0.8 km); widget anchor follows to `37.424,-122.088` |
| 19:39:49 | `candidate_pending lat=37.4240` — still at Googleplex; **backfills enqueued here carry `37.424,-122.088`** |
| ~19:45–20:24 | Phone returns home; screen unplugged/idle; last obs write under home key is fetched 19:17 |
| 20:24:23 | USB power connected (`dumpsys battery`) → network/power constraints clear |
| 20:25:01–09 | Pending backfill (enqueued while at Googleplex) runs with `lat=37.424 lon=-122.088`, writes 1,240 fresh station rows (KSJC/KNUQ/KPAO/LOAC1/AW020) under the **Googleplex fragment** |
| 20:25:02 | `power_connected` resample detects the *home* fix as a new candidate; widgets re-anchor to `37.417,-122.089` |
| 20:28–20:32 | Screen + graph read the home fragment → newest rows are 19:10 reported / 19:17 fetched ("7:17 PM") even though 20:25-fetched data exists in the DB |
| 20:35:00 | `charging_loop` current-temp fetch writes observations under the home key → both UIs "self-repair" |

DB fragments for the same 5 stations: `37.417,-122.089` (since Aug 11), `37.418,-122.087`
(Aug 16–19), `37.424,-122.088` (Aug 15 → today). All contain the **same stations**; only the
device-coordinate key differs.

### Root cause (two independent gaps)

1. **Reporting reads are site-scoped too tightly.** The Observations screen
   (`WeatherObservationsActivity.stationsSince` → `LocationMatch.selectNearestSiteWith`,
   SAME_SITE_TOLERANCE_DEG = 0.002°) and the graph observation path collapse the ±0.1° proximity
   box to a single physical-site fragment. Fresh station data landing under a neighbouring
   fragment (device moved and returned) is invisible to them until a fetch writes their fragment.
   For *reporting* (station list, per-station labels) distance plays no part in the calculation,
   so the fragment key adds nothing — the station identity does.
2. **No proactive refresh when what the user is looking at is stale.** The activity reloads from
   DB on insert-flow changes (`observeCurrentObservationUpdates`) but never *fetches* on its own;
   repair depended on luck (the 20:35 charging loop).

Note the IDW blend path (`CurrentObservationReader`, `ActualTemperatureSeriesBuilder`) is **correct**
to stay site-strict today: IDW weights each station by distance from the user's location, so which
site the input selection is anchored to matters there. We deliberately do not relax that path.

## Goal

When the user is looking at observations (screen, activity, graph labels), stale display repairs
itself within seconds via an automatic location resample + targeted fetch — without waiting for
the periodic loop — and reporting reads stop going blind to fresh data filed under a nearby
fragment.

---

## Phase C (FIRST): Auto-resample + auto-fetch when viewing stale data

The user's trigger list: **plugged in, charging, screen on, user looking at activity with stale
info**. Inventory of what already exists:

| Trigger | Status |
|---|---|
| Power connected → resample | ✅ exists (`ScreenOnReceiver.handlePowerConnected` trigger=`power_connected`; fallback `PowerConnectedJobService`) |
| Screen unlock → resample | ✅ exists (`ScreenOnReceiver`, trigger=`screen_unlock`, debounced) |
| Periodic full sync → resample | ✅ exists (`FullSyncPipeline`, trigger=`worker`) |
| App foreground (MainActivity) → follow-device | ✅ exists (trigger=`foreground`) |
| Charging heartbeat → resample | ⚠️ indirect: `charging_loop` enqueues worker work which resamples every ~10 min; acceptable, no change needed |
| **Observations activity / graph showing stale data → resample + fetch** | ❌ missing — build this |

### Design

1. **Pure policy, framework-free:** new shared-free helper (app module,
   e.g. `widget/StaleDisplayRefreshPolicy.kt`) deciding "is what I am displaying stale enough to
   act?" from `(nowMs, newestReportedMs, newestFetchedMs)` of the rows actually shown:
   - Threshold: act when `newestFetchedAt` is older than **15 min** (matches the interactive
     cadence used by the cloud watchdog plan; observations are minute-resolution METAR data).
   - Also act when rows are non-empty but `newestReported` lags `now` by > 30 min *and* a fetch
     completed recently (stations themselves may be quiet — don't hammer).
   - Unit-testable with zero Android deps; feeds the decision into the caller.

2. **Wire into `WeatherObservationsActivity`:**
   - In `loadObservations()`'s Main-thread render block, after computing `observations` +
     staleness, evaluate the policy. When it fires (and only once per debounce window):
     - call `gpsResampler.resample(context, trigger = "observations_screen")` (passive fix; no
       active GPS; respects FIXED pin mode and no-permission cases already),
     - then enqueue the existing manual-refresh path (`refreshCurrentTemperature` with
       `reason = "stale_observations_screen"`, `forceRefresh = true`) for `currentSource`.
   - Debounce via a `WidgetStateManager`-style timestamp pref (reuse pattern from
     `ScreenOnReceiver`'s debounce) so a foreground/reload burst can't stampede: minimum **10 min**
     between automatic triggers.
   - Log one `app_logs` row per trigger: tag `OBS_STALE_AUTO_REFRESH`, message with
     `outcome=` token (`fired`, `skipped_recent_trigger`, `skipped_fresh`,
     `skipped_no_location`) so "why didn't it refresh?" is answerable from a pulled DB
     (same discipline as `GPS_RESAMPLE` outcomes).

3. **Graph label path rides along free:** once the resample+fetch lands fresh rows under the
   active key, the existing insert-flow observer repaints the graph label. No separate hook in
   Phase C.

4. **Candidate-promotion latency:** `GpsResampler` only proposes candidates; the anchor moves when
   candidate weather is "useful enough". That gating stays untouched in Phase C — the immediate
   fetch runs under the *current* anchor, which is the same key the display reads, so the user sees
   fresh data immediately regardless of whether the anchor later moves.

### Non-goals (Phase C)

- No active GPS request (Samsung notice; passive cache only — unchanged contract).
- No change to candidate promotion rules.
- No desktop parity work: desktop has no charger/screen-unlock concept and its location comes from
  its own config; the desktop equivalent ("viewing stale → refetch") can mirror later if wanted.

---

## Phase A: Relax coordinate matching for reporting reads (station-keyed)

Scope: paths where distance is NOT part of the calculation. Keep IDW paths strict.

1. **New pure helper in `shared` `LocationMatch`:**
   `collapseByStation(rows, latOf, lonOf, stationOf, timestampOf, maxStations)`:
   - group the ±0.1° box result by `stationId` across ALL fragments,
   - keep the freshest row per station by `timestamp` (tie-break `fetchedAt`),
   - drop stations whose newest row fails the existing freshness/QC predicates callers already use,
   - cap to the station count the poller actually uses (guards the historical "stale Los Gatos 6th
     station" leak — abandoned-site frozen rows can only surface if fresher than real ones).

2. **Callers to switch:**
   - `WeatherObservationsActivity.stationsSince` (NWS branch) — replace
     `selectNearestSiteWith` collapse with `collapseByStation`; keep the diagnostic string but add
     `fragments=<n>` so future incidents name the mechanism.
   - Hourly-graph observation label source (the "KNUQ 68 @ …" resolver) and
     `ObservationWatermark.of` — same relaxed read so watermark staleness measures the stations,
     not one fragment.
   - `HourlyObservationBackfill.evaluateHourlyBackfillNeed` coverage checks — count gaps across
     merged station series so a neighbouring fragment's fresh rows satisfy coverage instead of
     triggering redundant backfills.

3. **Explicitly unchanged:** `CurrentObservationReader.getMainObservationsWithComputedNwsBlend`
   (IDW input), `ActualTemperatureSeriesBuilder`, blend tab inputs — site-strict because distance
   weighting depends on the anchor.

4. **Desktop parity evaluation:** `LocationMatch` is shared and the desktop JDBC DAO mirrors the
   box predicates. The desktop stations list should get the same station-keyed collapse where it
   renders recent observations (check `desktop/Main.kt` read path during implementation; apply if
   it has the same single-site collapse).

## Phase B: Backfill KEEP coordinate guard

Even with A+C, a pending backfill keyed to an abandoned site wastes a full 5-station×72 h fetch and
writes another orphan fragment:

1. Location-scope the unique work name:
   `WORK_NAME_OBSERVATION_BACKFILL` → append quantized-site suffix, e.g.
   `"observation_backfill_37.424_-122.088"`. A request for the current anchor can then never be
   blocked by a pending request for a different site.
2. Best-effort hygiene: when the anchor changes (candidate promotion in `LocationUpdater`), the
   existing post-anchor refresh enqueue already supersedes; additionally cancel any
   `observation_backfill_*` names whose site is not same-site with any current widget anchor
   (cancel-by-name of *not-yet-running delayed* work only — complies with the REPLACE/cancel safety
   rules in AGENTS.md since we never touch a running worker).

---

## Testing

- **Unit (pure, `:app` test/ Short/Medium bucket per measured time):**
  `StaleDisplayRefreshPolicyTest` — thresholds, debounce, quiet-station guard.
  `LocationMatchCollapseByStationTest` (`:shared`) — multi-fragment merge, freshest-wins, cap,
  empty-box passthrough.
- **Robolectric (`:app`):** activity wiring — policy fires → resample invoked + refresh enqueued +
  log row written; debounce suppresses burst; FIXED-pin suppresses.
- **Existing tests to keep green:** `LocationMatchSelectNearestSiteWithTest`,
  `HourlyObservationBackfillLocationTest`, `HourlyStaleFragmentCollapseTest`.
- **Emulator verification:** reproduce the incident shape (move GPS fix between two points ~0.8 km
  apart via emulator location injection), confirm screen recovers < 1 min after opening with stale
  data, `OBS_STALE_AUTO_REFRESH` + `GPS_RESAMPLE` breadcrumbs present.

## Rollout

Phases land as separate commits in order C → A → B; each keeps the app shippable alone.

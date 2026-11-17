# How often should the NWS station rows be reconciled with the stations' own METARs?

Follow-up to `plans/261117-nws-station-rows-reconciled-with-own-metars.md` (approved and implemented).
That plan estimated the cost as "one Aviation Weather request per gap-fill pass". Implementing it
showed two things the plan missed, so the cadence goes back to the user (user, 2026-11-17: "Create a
table with pros and cons of each option. Should we make this a one time repair?" → "Add to plan or
create a plan with a table comparing the different options").

**Status: waiting for a decision. Nothing below is implemented yet.**

## What implementation turned up

1. **Two requests per pass, not one.** `reconcileMetars` needs the station list
   (`host.stations()`). On both platforms that calls NWS `/points/{lat},{lon}` uncached
   (`NwsApi.getGridPoint`); only the station list behind it is cached. So each pass sends the NWS
   lookup plus the Aviation Weather request.
2. **It breaks a documented rule.** `arch/forecast-and-observation-cadence.md` says "A complete week
   makes no request", and `DeferredObservationWindowTest` (`a wake with every day complete makes no
   request`, `coVerify(exactly = 1) { nwsObservationStations() }`) enforces it. With the reconcile
   running every pass, it fails (3 calls). This is the one gate failure left; the other
   (`CrossPlatformDuplicateNamesTest`, a `fetchMetarHistory` name in both `:app` and `:desktop`) is
   fixed by having Android's host call `NwsObservationSource.aviationWeatherApi` directly.

## Evidence

**How often the pass runs** (`NWS_DAILY_BACKFILL_RESULT` lines, 2026-11-16 10:00 → 11-17 10:00,
backups `backups/20261117_101124_*`):

| Device | Passes / 24 h |
|---|---|
| emulator-5554 | 6 |
| Pixel 7 Pro | 23 |
| Samsung SM-F936U1 | 35 |

**How often holes happen** (KNUQ `api='NWS'` rows since 11-13, gaps over 75 min):

| Device | Holes |
|---|---|
| emulator-5554 | 3 in 4 days: 11-16 13:15–14:55, 11-17 00:55–04:35, 11-17 05:55–08:55 |
| Pixel 7 Pro | none for KNUQ (not checked: whether it reads another nearest station) |
| Samsung SM-F936U1 | none for KNUQ (same) |

The holes recur on a device that sleeps or polls rarely. Today's fog came from the third one.

## Options

Each option uses the same `reconcileMetars` step that's already implemented; they differ only in when
it runs.

| Option | Requests | Pros | Cons |
|---|---|---|---|
| **1. Every pass** | 2 per pass: about 12/day emulator, 46 Pixel, 70 Samsung | Simplest code, no state. A new hole is repaired on the next pass, today's included. | Drops the documented "complete week makes no request" rule. Most requests, and most of them find nothing to add. |
| **2. At most once an hour per site** | ≤ 2 per hour: ≤ 48/day, usually fewer (a pass less than an hour after the last one skips) | Holes repaired within about an hour. Bounded cost. No new table or preference: the check reads the newest `NWS_METAR_RECONCILE` row in `app_logs` (`INFO`, so it persists on both platforms). | A small time check in `:shared`. Relaxes the "no request" rule to "at most one reconcile an hour". Relies on that log row (if it's pruned or missing, the pass just reconciles, which is safe). |
| **3. Only on Refresh and wake** | Only when you tap Refresh, or desktop launches/wakes | Fewest requests. Background syncs of a complete week stay request-free, as documented. | Phones repair only when you tap Refresh, so a hole that opens unseen (fog on Today) stays until then. Needs the trigger reason passed into the reconcile decision. |
| **4. One-time repair** | One pass per device and site, then never | Clears today's emulator fog and any hole in the last 72 h. Zero ongoing cost. Keeps the rule unchanged. | **Doesn't fix the cause:** the emulator would have had 3 holes in 4 days, and every later one stays. Needs a persisted "done" marker per site. Holes older than 72 h at that moment are missed. |

Any option except 3 also acts as a one-time repair on its first pass after install: it fixes today's
emulator fog and the last 3 days. So option 4 adds nothing the others don't, apart from stopping
afterwards.

## Recommendation (Claude's proposal, not a user decision)

**Option 2, at most once an hour per site.** The evidence shows holes recur, which rules out 4. Option 3
leaves phones wrong until a tap. Option 1 spends the most to find nothing most of the time. Option 2
keeps the repair automatic, with a hard cap on requests.

## Implementation per option

- **1:** update `DeferredObservationWindowTest` so a complete week makes the reconcile's two requests and
  no station-window fetch, and change the cadence doc's sentence to say so.
- **2:**
  - `NwsGapFillRun.reconcileMetars` skips when the host's newest `NWS_METAR_RECONCILE` log is under
    `RECONCILE_MIN_INTERVAL_MS` (1 h) old, logging the skip at `VERBOSE`.
  - The host gains `lastLogAtMs(tag)`, implemented by both platforms over `app_logs`.
  - Tests: skip within the hour, run after it, and run when no row exists.
  - `DeferredObservationWindowTest`: its second wake comes within the hour, so it makes no request
    again. The first wake now looks up stations twice (once for the reconcile, once for its fill), so
    the station-lookup count goes from 1 to 2. The second wake's "no request" assertion still holds.
  - Doc: one sentence.
- **3:** `dailyForSources` reconciles only when `reason` is a user refresh or a desktop wake/launch.
  Tests for both kinds of reason. Doc: one sentence.
- **4:** a per-site "reconciled once" marker (preference on Android, a desktop table or setting). Tests:
  first pass runs, second never. Doc: one sentence.

In every case: the end-of-task gate
(`./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`), then on the emulator an
`NWS_METAR_RECONCILE … added>0` line, KNUQ NWS rows for 06:15–08:35, no `DAILY_FOG_DRAWN date=2026-11-17`,
and a screenshot. Then the same check on desktop.

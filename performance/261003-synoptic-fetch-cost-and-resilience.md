# Synoptic fetch: download what is used, once, and recover quickly from timeouts

> **Superseded in part (2026-10-03):** fix 1 (two `limit=` queries) was withdrawn after live
> verification — Synoptic's `limit` is not nearest-first and `network=2` is RAWS. Android
> single-flight did not span worker runs. See
> `performance/261003-synoptic-fetch-review-fixes.md`.

## Evidence (emulator-5556 and desktop, 2026-10-03)

- **~31,700 observations downloaded to keep ~1,640 (5%) on every full sync.** `SYNOPTIC_FETCH
  stations=11 hours=24 rows=31687 stored=1636`. The request is
  `stations/timeseries?radius=<site>,25.0&recent=1440&qc=on&qc_checks=all&qc_flags=on`:
  - every station within 25 miles (~197, per `SkyReportingStationSlots`' 2026-08-27 count);
  - every variable they report;
  - the whole past 24 h.
  `SynopticObservationFetcher` keeps the nearest 10, plus up to 3 sky-reporting stations
  (`SkyReportingStationSlots`).
- **Re-downloaded every 5–10 min** when full syncs are frequent (16:49:30, 16:49:37, 16:55, 17:01,
  17:06, 17:16). `FullSyncPipeline` always passes `DEEP_HOURS` (24); desktop
  `DesktopWeatherService` does the same through `fetchObservationsResult(hours = …)`.
- **One stall counted as two failures.** Two full syncs overlapped (`periodic_60m` 21:58:39,
  `on_update_stale` 21:58:52). Each sent the identical request; both timed out at 21:59:22 (30 s).
  `SynopticFetchGate` escalated twice: streak 2 → 60-min backoff, so no Synoptic data until 22:59.
- **Timeouts are treated like quota rejections.** `SynopticBackoff` (30 min, doubling to 6 h) was
  built for the 2026-09-08 all-day token rejection. A 30-second network stall gets the same penalty.
  NWS `/points` also timed out at 22:09, which points to the network, not Synoptic.
- **The API token is logged.** `FetchOutcome.failed(e)` embeds the exception message, which
  includes the full URL with `token=…`. It is stored in `app_logs` (`SYNOPTIC_FETCH_FAIL`,
  `SYNOPTIC_FETCH_BACKOFF_SET`) on both platforms and sent in bug-report emails (last 300 log lines).
  `SynopticApi` also `Log.w`s the exception, which goes to the desktop's persisted log file.

## Fixes

### 1. Ask only for the stations that are kept (`:shared` `SynopticApi` / fetcher)

A plain `limit=` would break cloud actuals: the sky-reporting airports (KNUQ 3.8 km, KPAO 5 km,
KSJC 16 km) sit behind ~100 closer personal stations. `SkyReportingStationSlots` already says
"raising the limit is the wrong fix"; the same applies to lowering it. So use two small queries
instead of one huge one:
- **Temperature:** `radius=…&limit=10` — the nearest 10, which is what `select` keeps today.
- **Sky:** `radius=…&network=1,2&limit=MIN_SKY_STATIONS (3)` — NWS/FAA ASOS/AWOS, the networks
  that carry `metar_set_1` / cloud layers (`MNET_ID` 1 and 2 per `SynopticApi`'s comment).
- Merge, de-duplicate by station id, then run `SkyReportingStationSlots.select` unchanged as the
  final rule, so the kept set is identical to today's.
- Verify against a recorded full response (one real request, saved as a test fixture): the merged
  small queries select the same station ids.

### 2. Ask only for the variables that are parsed

`vars=air_temp,metar,cloud_layer_1,cloud_layer_2,cloud_layer_3,weather_summary,weather_condition` —
the fields `parseRadiusTimeseries` reads (`air_temp_set_1`, `metar_set_1`, `cloud_layer_N_set_1d`,
`weather_summary_set_1d`, `weather_condition_set_1d`, and the `QC` block for `air_temp`).
- The `_1d` fields are derived by Synoptic, and requesting them by name needs checking against the
  live API. Equivalence test: the parsed `ObservationReading`s from a `vars=` response equal those
  from the full response for the same stations and window.
- If a derived field is not returned under `vars=`, fall back to the base variable it derives from.
  Never drop a field the parser uses.

### 3. Fetch only the missing window (shared policy + both platforms)

New pure function `SynopticFetchWindow.recentMinutes(newestStoredMs, nowMs)` in `:shared`:
- No Synoptic rows at this site in the last 24 h (first run, new location, long gap): 1440 (deep).
- Otherwise: minutes since the newest stored reading + a 30-minute margin (late or re-QC'd reports),
  at least 120 and at most 1440.
- Android: `FullSyncPipeline` / `SynopticObservationRefresher` ask the observations DAO for the
  newest `api='SYNOPTIC'` row in the site's `LocationMatch` box instead of always passing
  `DEEP_HOURS`. Desktop `DesktopWeatherService` does the same from its DB.
- Daily extremes keep working: earlier hours are already stored (observations are kept 10 days).

### 4. One request when syncs overlap (`:shared` single-flight)

- `SynopticFetchGate` gets an in-process single-flight: while a fetch for the same site is running,
  a second caller awaits that result instead of sending its own. One request, one outcome, one
  backoff step.
- Plus a freshness floor: skip if a successful fetch for this site finished under 2 minutes ago (the
  16:49:30 / 16:49:37 pair).
- Both platforms use the gate, so both get it.

### 5. Timeouts back off gently; rejections keep the doubling

- Classify `FetchOutcome.Failed`: **transport** (timeouts, `IOException`, DNS) vs **API rejection**
  (`RESPONSE_CODE != 1`, quota, auth).
- Transport: retry at the next sync after a fixed **5-minute** backoff, no escalation.
- Rejection: today's `SynopticBackoff` (30 min doubling to 6 h), unchanged.
- `SYNOPTIC_FETCH_BACKOFF_SET` logs the class, e.g. `class=transport backoffMin=5`.

### 6. Never log API keys

- Redact credentials centrally in `FetchOutcome.failed`, which every API's error text passes
  through. Strip the values of the query parameters `token`, `appid`, `key`, `apikey` and
  `api_key` (→ `<redacted>`).
- Same redaction for the `Log.w(…, "$e")` calls in the remote API clients.
- One-time scrub of existing rows: `UPDATE app_logs SET message = <redacted> WHERE message LIKE
  '%token=%'` (and the other keys), on Android at startup migration and on desktop. Desktop log
  files under `~/.local/state/weather-widget/` rotate out; note them for the user rather than
  rewriting files.

## Order

6 (security) → 5 → 4 → 3 → 2 → 1. Each step is independently shippable and testable, the cheap
safe ones first, and the two that need live-API verification (2, 1) last.

## Tests

- **Unit (`:shared`):**
  - `SynopticFetchWindow` table.
  - Failure classification (timeout, IO, RESPONSE_CODE=2, null message).
  - Redaction (each key, multiple params, no key present, already redacted).
  - `vars=` / two-query equivalence against recorded fixtures.
- **Integration (Robolectric, 2+ classes):**
  - Two overlapping `FullSyncPipeline` runs with a fake `SynopticApi` that hangs then times out →
    exactly one API call and `streak=1`, `class=transport`, `backoffMin=5`.
  - A follow-up sync 3 min later → one request with `recent` ≈ the gap + 30.
- **Mutation check:** each test fails with its fix reverted.
- **Measured before/after:** one real request each way on the desktop (bytes, stations, rows, ms),
  recorded in this file. Expected: ~31,700 → a few hundred observations on a routine refresh.

## Out of scope

- The OWM → Synoptic default for Android (separate decision; the user switched the emulator's OWM
  actuals to METAR).
- Synoptic's `NON_PRIMARY` cadence (17:16 → 21:59 with no attempt).

## Implementation status (2026-10-03)

All six fixes shipped in order 6 → 5 → 4 → 3 → 2 → 1. Unit tests cover each (see below).

| Fix | Where | Tests |
|-----|-------|-------|
| 6 Redaction | `ApiKeyRedaction`, `FetchOutcome.failed`/`Failed.of`, SynopticApi/NwsApi `Log.w`, Android `MIGRATION_70_71`, desktop `APP_LOGS_KEY_SCRUB_DONE` | `ApiKeyRedactionTest` |
| 5 Failure class | `FailureClass` (TRANSPORT/REJECTION), `SynopticBackoff.TRANSPORT_BACKOFF_MS` = 5 min, gate logs `class=` | `FailureClassTest`, `SynopticFetchGateTest` |
| 4 Single-flight + freshness | `SynopticFetchGate` per-`siteKey` single-flight + 2-min floor (`FRESHNESS_FLOOR_MS`) | `SynopticFetchGateTest` |
| 3 Fetch window | `SynopticFetchWindow.recentMinutes`, `ObservationDao.getNewestTimestampForApi`, desktop `getNewestObservationTimestampForApi` | `SynopticFetchWindowTest` |
| 2 `vars=` | `SynopticApi.PARSED_VARS`, parse fallback `_set_1d` → `_set_1` | `SynopticApiVarsEquivalenceTest` |
| 1 Two queries | `fetchRadiusTimeseries(limit=, network=)`, fetcher merges temp+sky queries then `SkyReportingStationSlots.select` | `SynopticTwoQueryEquivalenceTest` |

**Pending live-API verification** (fixes 1 and 2 — the plan's "need live-API verification" items):

- Confirm Synoptic accepts `vars=weather_summary,cloud_layer_N,weather_condition` and returns the
  derived `_set_1d` fields (or confirm the `_set_1` fallback is what actually arrives).
- Confirm `limit=` and `network=1,2` behave as the two-query design assumes (nearest-N, network
  filter matching `MNET_ID` 1/2).
- Record one real before/after request (bytes, stations, rows, ms) and paste it here. Expected:
  ~31,700 → a few hundred observations on a routine refresh.

**Desktop log files:** `~/.local/state/weather-widget/` still holds historical exception text with
credentials. Those files rotate out under the existing retention; they were not rewritten. A
one-time manual scrub is optional if the files are being shared.

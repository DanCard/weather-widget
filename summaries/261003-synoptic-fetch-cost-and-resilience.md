# Synoptic fetch: download what is used, once, and recover quickly from timeouts

> **Superseded in part (2026-10-03):** fix 1 (two `limit=` queries) was withdrawn after live
> verification — Synoptic's `limit` is not nearest-first and `network=2` is RAWS. Android
> single-flight did not span worker runs. See
> `performance/261003-synoptic-fetch-review-fixes.md`.

**Date:** 2026-10-03
**Plan:** [performance/261003-synoptic-fetch-cost-and-resilience.md](../performance/261003-synoptic-fetch-cost-and-resilience.md)

## What happened

The user asked to implement the performance plan that documented six problems with the Synoptic
observations fetch, measured on emulator-5556 and desktop on 2026-10-03:

1. ~31,700 observations downloaded to keep ~1,640 (5%) on every full sync — a 25-mile radius query
   pulled every station, every variable, the whole past 24 h.
2. Re-downloaded every 5–10 min when full syncs were frequent.
3. One stall counted as two failures (overlapping syncs each sent the identical request).
4. Timeouts were treated like quota rejections (30 min → 6 h doubling built for a 2026-09-08
   all-day token rejection).
5. The API token was logged in full (Ktor exception messages embed the request URL), stored in
   `app_logs` on both platforms and sent in bug-report emails.
6. `FullSyncPipeline` and desktop always passed `DEEP_HOURS` (24 h) even when earlier hours were
   already stored (observations are kept 10 days).

The plan specified the fix order: **6 (security) → 5 → 4 → 3 → 2 → 1**, each independently
shippable, the cheap safe ones first and the two that need live-API verification last.

## Changes

### Fix 6 — Never log API keys

- **New `shared/.../data/remote/ApiKeyRedaction.kt`.** Strips the values of query parameters
  `token`, `appid`, `key`, `apikey`, `api_key` to `<redacted>`. Longest-first alternation so
  `apikey` wins over `key`; `\b` keeps `interactionToken=` and `mykey=` untouched. Already-redacted
  text is left alone (the `<` `>` in `<redacted>` are excluded from the value charset).
- **`FetchOutcome.failed(e)`** now routes through `Failed.of`, which redacts. All dynamic
  `FetchOutcome.Failed(...)` constructions in remote clients use `Failed.of`.
- **SynopticApi / NwsApi `Log.w`/`Log.e`** calls that embed `"$e"` or `e.message` redact before
  logging (the desktop persisted log file was the leak path here).
- **One-time scrub of historical rows.**
  - Android: `MIGRATION_70_71` (schema unchanged) rewrites matching `app_logs` messages. Version
    bumped 70 → 71; schema JSON `71.json` exported.
  - Desktop: `DesktopWeatherDao.scrubCredentialsFromAppLogs()` behind the permanent
    `APP_LOGS_KEY_SCRUB_DONE` marker, called from `DesktopWeatherRepository` alongside the other
    one-time backfills.
- Desktop log files under `~/.local/state/weather-widget/` still hold old exception text; they
  rotate out under existing retention. Noted for the user rather than rewritten.

### Fix 5 — Timeouts back off gently

- **New `shared/.../util/FailureClass.kt`.** `TRANSPORT` (timeouts, IOException, DNS, connect,
  socket, SSL) vs `REJECTION` (`RESPONSE_CODE=`, `synoptic:` API messages, parse of a received
  body, anything unknown — the conservative default).
- **`SynopticBackoff.TRANSPORT_BACKOFF_MS` = 5 min.** Transport: fixed 5-min wait, **no streak
  escalation**. Rejection: unchanged 30-min → 6-h doubling.
- **`SynopticFetchGate`** classifies each failure and logs `class=transport` or `class=rejection`
  in `SYNOPTIC_FETCH_BACKOFF_SET`. A stall no longer costs an hour of Synoptic.

### Fix 4 — One request when syncs overlap

- **`SynopticFetchGate`** gains an in-process single-flight keyed by `siteKey` (`SynopticFetchGate.siteKey(lat, lon)`):
  while a fetch for the same site is running, a second caller awaits that result instead of sending
  its own. One request, one outcome, one backoff step. Log tag `SYNOPTIC_FETCH_JOINED`.
- **Freshness floor** (`FRESHNESS_FLOOR_MS` = 2 min): skip when a completed fetch for this site
  finished under 2 minutes ago (the 16:49:30 / 16:49:37 pair). Log tag `SYNOPTIC_FETCH_FRESH_SKIP`.
  Exempt on `userLocationChange`, like the backoff bypass.
- Both platforms pass `siteKey` from their lat/lon: `SynopticObservationRefresher`,
  `DesktopWeatherService`.

### Fix 3 — Fetch only the missing window

- **New `shared/.../util/SynopticFetchWindow.kt`.** Pure function
  `recentMinutes(newestStoredMs, nowMs)`:
  - No rows in the last 24 h (first run, new location, long gap) → 1440 (deep).
  - Otherwise: minutes since the newest stored reading + 30-min margin, clamped to
    `[120, 1440]`.
- **`ObservationDao.getNewestTimestampForApi(api, lat, lon)`** (Android, `LocationMatch.ROOM_SAME_SITE_WHERE`)
  and **`DesktopWeatherDao.getNewestObservationTimestampForApi`** (desktop, `JDBC_SAME_SITE_WHERE`).
- `SynopticObservationRefresher.refreshIfDue` no longer takes an `hours` parameter — it computes
  the window from the DB. `FullSyncPipeline` no longer passes `DEEP_HOURS`.
- `SynopticObservationFetcher` / `SynopticObservationSource` gain an optional `recentMinutes`
  parameter that overrides the `hours`-derived value.
- Desktop `fetchBorrowedObservationsOnly` uses the same gap window instead of
  `RECOVERY_BORROWED_METAR_HOURS` (24).
- `SYNOPTIC_FETCH` / `SYNOPTIC_OBS_STORED` now log `recentMin=` instead of `hours=`.

### Fix 2 — Ask only for the variables that are parsed

- **`SynopticApi.PARSED_VARS`**: `air_temp,metar,cloud_layer_1,cloud_layer_2,cloud_layer_3,
  weather_summary,weather_condition` — the fields `parseStationObservations` reads. Sent as
  `vars=` on every radius request; empty list requests the full unfiltered response.
- **Parse fallback** `derivedOrBaseArray`: the derived `_set_1d` series when present, else the base
  `_set_1` series a `vars=` request may return instead. Never drops a field the parser uses.

### Fix 1 — Two small queries instead of one huge one

- **`SynopticApi.fetchRadiusTimeseries`** gains `limit: Int?` and `network: String?` parameters.
- **`SynopticObservationFetcher`** issues two queries and merges:
  1. Temperature: `limit=10` (nearest 10) — what `SkyReportingStationSlots.select` keeps for the
     proximity blend.
  2. Sky: `network=1,2&limit=MIN_SKY_STATIONS(3)` — NWS/FAA ASOS/AWOS, the networks that carry
     `metar_set_1` / cloud layers (`MNET_ID` 1 and 2).
- Merged, de-duplicated by station id, then `SkyReportingStationSlots.select` runs **unchanged** as
  the final rule — the kept set matches what a full ~197-station response would have selected.
- Partial failure: if one query fails the other's stations are still kept; both failing surfaces
  the first failure. `SYNOPTIC_FETCH` logs `tempStations=` / `skyStations=` / `merged=`.
- Constant `SynopticObservationFetcher.SKY_NETWORKS = "1,2"`.

## Tests

All in `:shared`, all `@Category(ShortDuration::class)`:

1. **`ApiKeyRedactionTest`** (12 cases): each key (`token`, `appid`, `key`, `apikey`, `api_key`),
   multiple params, no key present, already redacted, partial words (`interactionToken`, `mykey`)
   untouched, end-of-string value, case-insensitive names, `FetchOutcome.failed` / `Failed.of`
   redaction. Mutation check: removing redaction makes the "value is gone" assertions fail.
2. **`FailureClassTest`** (7 cases): timeout/IO/DNS → TRANSPORT; `RESPONSE_CODE=2`, `synoptic:
   Invalid token`, `synoptic: null` → REJECTION; parse of a received body → REJECTION; unknown →
   REJECTION; `synoptic: … timeout …` stays REJECTION (the API spoke).
3. **`SynopticFetchGateTest`** (8 cases, extended): rejection streak + skip; transport 5-min wait
   with no escalation; transport/rejection classify independently (streak 2 survives a stall, next
   rejection continues at 3); location-change bypass + success clears; failed bypass escalates;
   **overlapping fetches share one request** (async, `SYNOPTIC_FETCH_JOINED`); **freshness floor**
   skips a 7-second-later sync and allows one after the floor; floor does not block a location
   change; different sites do not share state.
4. **`SynopticFetchWindowTest`** (6 cases): null → deep; ≥24 h gap → deep; 5 min ago → 120 (floor);
   3 h ago → 210 (gap+margin); 23 h → 1410; 23 h 50 min → clamped 1440; exactly 24 h boundary.
5. **`SynopticApiVarsEquivalenceTest`** (4 cases): a `vars=` response (derived fields under base
   `_set_1` names) parses identically to a full response (timestamp, temp, summary, QC, METAR,
   cloud layers); `_1d` wins when both present; `_set_1` used when `_1d` absent; `PARSED_VARS`
   covers every parser input.
6. **`SynopticTwoQueryEquivalenceTest`** (4 cases): two small queries keep the same stations as one
   full response (KNUQ/KPAO/KSJC admitted past the PWS crowd); the fetcher issues exactly a
   temperature query (`limit=10`) and a sky query (`network=1,2`, `limit=3`); one query failing
   still returns the other's stations; both failing surfaces `FetchOutcome.Failed`.

**Mutation check:** each test class was written so reverting its fix makes the assertions fail (e.g.
always returning `DEEP_MINUTES` fails the gap cases; collapsing FailureClass fails the transport
cases; removing redaction fails the "value is gone" assertions).

**Compile/test gate:** `:shared:test` (full suite) passes. `:app` main + unit tests and `:desktop`
main + tests compile clean. Desktop tests for `DesktopSynopticBackoffStore`,
`DesktopSynopticFallback`, `DesktopBackfillChanceSnapshot` pass.

## Incidents during implementation

1. **Room schema JSON hand-edit went wrong.** Copying `70.json` → `71.json` and setting
   `d['version'] = 71` added a top-level `version` key the Room KSP parser rejects
   ("Encountered an unknown key 'version' at path: $.database"). The version lives at
   `database.version`. Fixed by rewriting the file with only `formatVersion` + `database` and
   bumping `database.version`.
2. **Kotlin companion `operator fun invoke` recursion trap.** A first attempt at making
   `FetchOutcome.Failed` redact in its constructor used `private constructor` + companion
   `operator fun invoke`; calling `Failed(redacted)` from inside `invoke` would recurse. Reverted
   to a public data class with a `Failed.of(...)` factory — simpler and safe.
3. **Fixture JSON trailing commas.** The two-query test's `stationJson` helper left a trailing
   comma after the last `OBSERVATIONS` field; kotlinx.serialization rejects it by default. Symptom
   was three opaque `AssertionError`s before the parse error surfaced in the XML report.
4. **PWS fixtures accidentally reported sky.** `weather_summary_set_1d: ["clear"]` maps to `CLR`
   via `mapSkyConditionToAmount`, so every PWS "reported sky" and `select` never added the
   airports. The real scene is PWS with *no* sky fields at all — fixed the fixture, not the parser.
5. **MockK parameter-count after removing `hours`.** `refreshIfDue` lost its `hours` parameter;
   existing tests matched with 5 `any()`s and still compile against the 5-parameter signature
   (`acceptTiers, lat, lon, reason, userLocationChange`). No mock updates needed.

## Pending (recorded in the performance doc)

- **Live-API verification of fixes 1 and 2** (the plan's "need live-API verification" items):
  - Confirm Synoptic accepts `vars=weather_summary,cloud_layer_N,weather_condition` and returns the
    derived `_set_1d` fields (or that the `_set_1` fallback is what actually arrives).
  - Confirm `limit=` and `network=1,2` behave as assumed (nearest-N, network filter = `MNET_ID` 1/2).
- **Measured before/after:** one real request each way on the desktop (bytes, stations, rows, ms).
  Expected: ~31,700 → a few hundred observations on a routine refresh. Not done — needs a live
  token and network.
- **Desktop log files** under `~/.local/state/weather-widget/` still contain historical exception
  text with credentials. They rotate out; a one-time manual scrub is optional if the files are
  being shared.

## Files touched

**`:shared` (new):**
- `data/remote/ApiKeyRedaction.kt`
- `shared/util/FailureClass.kt`
- `shared/util/SynopticFetchWindow.kt`

**`:shared` (modified):**
- `data/remote/FetchOutcome.kt` — `Failed.of`, `failed()` redacts
- `data/remote/SynopticApi.kt` — `PARSED_VARS`, `vars=`/`limit=`/`network=` params,
  `derivedOrBaseArray` parse fallback, redacted `Log.w`
- `data/remote/NwsApi.kt` — redacted `Log.e`, `Failed.of` for parse errors
- `shared/util/SynopticBackoff.kt` — `TRANSPORT_BACKOFF_MS`
- `shared/util/SynopticFetchGate.kt` — single-flight, freshness floor, `siteKey`, failure class
- `shared/observations/SynopticObservationFetcher.kt` — two-query merge, `recentMinutes` param,
  `SKY_NETWORKS`
- `data/local/desktop/DesktopWeatherDao.kt` — `getNewestObservationTimestampForApi`,
  `scrubCredentialsFromAppLogs`

**`:shared` (new tests):** `ApiKeyRedactionTest`, `FailureClassTest`, `SynopticFetchWindowTest`,
`SynopticApiVarsEquivalenceTest`, `SynopticTwoQueryEquivalenceTest`; extended
`SynopticFetchGateTest`.

**`:app` (modified):**
- `data/local/WeatherDatabase.kt` — version 71, `MIGRATION_70_71`
- `data/local/ObservationDao.kt` — `getNewestTimestampForApi`
- `data/repository/SynopticObservationSource.kt` — `recentMinutes` passthrough
- `widget/SynopticObservationRefresher.kt` — computes fetch window from DB, dropped `hours` param
- `widget/FullSyncPipeline.kt` — no longer passes `DEEP_HOURS`
- `app/schemas/.../71.json` — new

**`:desktop` (modified):**
- `DesktopWeatherService.kt` — borrowed Synoptic path uses fetch window + `siteKey`
- `DesktopWeatherRepository.kt` — `scrubApiKeysFromAppLogsIfNeeded`, permanent marker

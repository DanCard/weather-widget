# Fetch both NWS API + Synoptic web, prefer newest (top 3) + metrics (top 5)

**Date:** 2026-07-21
**Status:** Implemented 2026-07-21 (shared policy + merge, Android latest path, desktop bundle path,
tests). Backfill loops left on the old fallback, as planned. Metrics-tier window = 90 min.

## Motivation

The Synoptic **web** source frequently carries a *fresher* observation than the NWS
**API** source (`api.weather.gov` has ingest lag + `s-maxage=300` CDN caching; Synoptic
pulls the MADIS/METAR feed sooner). Today web is only ever fetched as a **reactive
fallback** — `ObservationFallbackPolicy.shouldUseWebFallback(index, newest, now)` fires
only when the API reading is **missing or >1h stale** (`STALE_AFTER_MS`) *and* the station
is within the nearest 3. That leaves a structural blind spot: whenever the API reading is
0–60 min old but web already has a newer one, the older API value is displayed and web is
never consulted.

## Decision (from the user)

- **Fetch-first, not fallback**: for the nearest **3** stations, always fetch *both* API
  and web every observation cycle — no staleness gate. (Drop the "non-personal" filter;
  plain distance-sorted top 3.)
- **Prefer newest**: the displayed / blend-anchoring "latest" reading for a station is the
  newest **non-QC-flagged** reading across the two sources.
- **Metrics for top 5 simultaneously**: for stations at index 3–4 (the 4th and 5th
  nearest), also fetch web, but **only to log a web-vs-API freshness comparison** — do
  *not* feed those web readings into storage/blend. This produces the data to decide later
  whether to widen the fetch-both set from 3 to 5.

## Scope

**Latest-observation path only.** The daily and hourly **backfill** loops are explicitly
**out of scope** — they keep their existing fallback-only behavior unchanged. This change
touches just the two "latest reading" sites:

| # | File | Method | Window requested | Notes |
|---|------|--------|------------------|-------|
| 1 | `app/.../ObservationRepository.kt` ~L152 | `fetchStationObservation` (latest ob) | `webFallbackWindowMinutes(...)` | current-temp / leading-edge anchor; **freshness-critical** |
| 4 | `desktop/.../DesktopWeatherService.kt` ~L359 | per-station bundle loop | `historyDays*24*60` | desktop equivalent of #1 |

Left untouched (fallback-only, as today):
- `app/.../ObservationRepository.kt` ~L346 — daily `daily_history` backfill loop
- `app/.../ObservationRepository.kt` ~L480 — hourly `backfillRecentNwsObservations`

Dropping backfill also removes the wide-window (`days*24*60`) web fetches, which were the
main added-cost concern; see Cost below.

## Current behavior (baseline to change)

`ObservationFallbackPolicy` (in `:shared`) is the single source of truth; both platforms
delegate. The in-scope sites gate web behind `isStale`/`shouldUseWebFallback` and iterate
`stations.take(MAX_RETRIES = 5)` (distance-sorted). Existing invariants to
preserve:
- **QC**: flagged web readings are *stored* (marked `isWeb`, `qcFailed`) so the Stations
  list can show the failure, but must **never** be the usable/blend latest.
- **REPLACE semantics**: `observationDao.insertAll` is REPLACE on `(stationId, timestamp)`,
  so re-inserting the same timestamp self-heals.
- **Staleness thresholds are distinct**: 1h = "re-fetch it" (`STALE_AFTER_MS`), 3h = "drop
  from blend" (`ObservationOrigin.BLEND_MAX_AGE_MS`). This change removes the 1h gate as a
  *fetch* trigger but leaves the 3h blend-decay untouched.
- **Timestamp normalization** must be identical between the API and Synoptic parse paths
  (truncate fractional seconds — see the `nws_observations_fractional_seconds` note) or the
  same physical ob lands as two rows and the union/dedup breaks.

## New behavior spec

### Shared policy (`ObservationFallbackPolicy.kt` — or a renamed `ObservationFetchPolicy`)

Add (keep the old fallback members until all sites migrate, then remove):

```kotlin
/** Nearest N stations fetch BOTH sources every cycle and use prefer-newest. */
const val WEB_FETCH_STATIONS = 3

/** Nearest N stations additionally log the web-vs-API comparison metric. */
const val WEB_METRICS_STATIONS = 5

/** Master switch so the whole experiment reverts to fallback-only in one line. */
const val FETCH_BOTH_ENABLED = true

fun shouldFetchWeb(stationIndex: Int): Boolean =
    FETCH_BOTH_ENABLED && stationIndex < WEB_FETCH_STATIONS

fun shouldLogWebMetrics(stationIndex: Int): Boolean =
    FETCH_BOTH_ENABLED && stationIndex < WEB_METRICS_STATIONS
```

Notes:
- `shouldFetchWeb` no longer takes staleness — it's unconditional for the top 3.
- Stations `WEB_FETCH_STATIONS..<WEB_METRICS_STATIONS` (index 3, 4) call web **only** for
  metrics; their web readings are discarded after logging.
- Window sizing: the freshness-critical latest path (#1/#4) can use a modest window
  (`MAX_WEB_FALLBACK_WINDOW_MINUTES` = 12h is fine and already justified). Backfill paths
  keep their wide windows. Metrics-only fetches (#index 3–4) should use a **small** window
  (e.g. 60–120 min) — we only need the newest reading to compare, not history, and this
  caps the added Synoptic cost of the metrics tier.

### Prefer-newest merge (new shared pure function)

```kotlin
// Returns the rows to store: union of both series, deduped by normalized timestamp.
// On exact-timestamp collision, API wins (official value); web-only timestamps (the
// fresher ones) are added. Flagged web rows are kept (for the Stations UI) but marked.
fun mergeForStorage(api: List<Obs>, web: List<Obs>): List<Obs>

// The station's usable latest = newest reading that is NOT qcFailed, across both sources.
fun latestUsable(merged: List<Obs>): Obs?
```

- **Tie-break rationale**: identical timestamp ⇒ same physical METAR; prefer the official
  API value. Web's contribution is the timestamps API *doesn't have yet* — exactly the
  freshness win. So "prefer newest" = union + API-wins-ties, and the newest usable reading
  naturally becomes web's fresh ob because API simply lacks that timestamp.
- Put both functions in `:shared` and unit-test them (no Android/Compose deps), then have
  all four call sites delegate. This matches the existing pattern of centralizing the
  policy so Android and desktop can't drift.

### Metrics log line

For every top-5 station where web was fetched (top 3 use + 2 metrics-only), emit one row:

```
tag: OBS_WEB_API_DELTA
msg: station=<id> index=<n> tier=<use|metrics>
     apiNewestMs=<..> webNewestMs=<..> deltaMin=<web-api in min, + = web fresher>
     apiTempF=<..> webTempF=<..> apiDecimals=<0|1> webDecimals=<0|1>
     webQcFailed=<bool> usedSource=<api|web|none>
level: INFO
```

`deltaMin > 0` ⇒ web is fresher. Aggregating this over ~1 week answers: how often is web
fresher, by how much, and would extending fetch-both to 5 help. Reuse the durable-DAO
logging on both platforms (`appLogDao.log` / `weatherDao.log`) so it lands in `app_logs`
and survives — desktop's fallback was once `Log.i`-only and thus invisible; don't repeat
that.

## Per-call-site changes

1. **`fetchStationObservation` (latest, #1)** — the important one. Always fetch API; if
   `shouldFetchWeb(index)`, also fetch web (modest window); `mergeForStorage` + store;
   return `latestUsable`. If `shouldLogWebMetrics(index)` but not `shouldFetchWeb`, fetch
   web with a small window for the log only, discard. Log `OBS_WEB_API_DELTA` in both
   cases. `index` here = the `attempt`/loop position over `stations.take(MAX_RETRIES)`.
2. **Desktop (#4)** — mirror #1: fetch both for top 3, prefer-newest into
   `ObservationBundle`; `usableLatest` = newest non-flagged across sources.
3. **Backfill loops (daily #2, hourly #3)** — **no change.** Keep the existing
   staleness-gated fallback.

## Cost / quota

- Synoptic calls for the latest-ob path go from *occasional fallback* to *every data-fetch
  cycle × up to 5 stations* (3 use + 2 metrics-only). This is the only added cost; backfill
  is untouched, so no wide-window web fetches are added.
- **Hard requirement — the UI-update tier must never trigger a web fetch.** Web is added
  *only* on the data-fetch tier (battery-aware 60–480 min), next to the existing API call at
  the same cadence: no new wakeups, no new cadence, one more request per station per existing
  fetch. The 15–60 min UI/current-temp tier stays cache-only and opportunistic and must not
  reach any Synoptic call. Before shipping, confirm `fetchStationObservation` (and anything
  it now calls) is unreachable from the UI-update path; if the paths share code, gate the web
  fetch on the fetch reason/tier so a UI refresh can never fan out to Synoptic.
- Metrics-only tier (stations 4–5) uses a small window to minimize marginal cost.
- Confirm Synoptic usage is counted in `api_usage_stats` (baked token). Add tracking if the
  latest Synoptic calls currently bypass it.

## Testing

- **Shared unit tests** (`:shared`, JVM, no font/Android deps):
  - `mergeForStorage`: web-only fresh timestamps added; exact-collision → API value kept;
    flagged web row retained but not chosen by `latestUsable`; all-flagged web + fresh API
    → API latest.
  - `latestUsable`: newest non-flagged wins; newest-but-flagged skipped.
  - `shouldFetchWeb`/`shouldLogWebMetrics` boundaries at index 2/3/4/5; `FETCH_BOTH_ENABLED
    = false` disables both.
- **Repository tests**: no-mock style — drive with fixture API + fixture Synoptic lists via
  the existing `FetchOutcome` seam; assert stored rows and the emitted `OBS_WEB_API_DELTA` /
  chosen source. Prove a test can fail (Robolectric has no font engine — assert data, not
  pixels).
- **Live validation**: after install, pull `app_logs`, grep `OBS_WEB_API_DELTA`, confirm
  deltas populate for 5 stations and prefer-newest picks web when `deltaMin>0`. Compare
  Android vs desktop rows for the same location (parity).

## Rollout & revert

- Single flag `FETCH_BOTH_ENABLED` flips the whole thing back to... note: flipping it off
  also disables metrics. If we want metrics-without-behavior-change as a first sub-step,
  add a second flag `WEB_USE_ENABLED` (gates the top-3 *use*) separate from
  `WEB_METRICS_ENABLED` (gates logging). **Recommended**: ship metrics + use together (same
  effort, immediate benefit) as the user chose, with the two flags available for quick
  narrowing.
- Keep the old `shouldUseWebFallback` / `webFallbackWindowMinutes` until the four sites are
  migrated and tests pass, then delete in a follow-up so the diff that adds behavior is
  separable from the cleanup.

## Future refinement: precision-aware preference (not in scope now)

"Prefer newest" is the v1 rule, but newest is not always *best*. A later source can carry
**higher-precision** data for effectively the same time — e.g. one source reports whole
degrees (`68`) while the other reports tenths (`67.8`). Idea (deferred): when two readings
are within a small time window (say ≤ one METAR cycle, ~5–10 min) but one is materially
more precise, prefer the precise one even if it's slightly older.

- This is already a live signal in the codebase: decimal-vs-integer temperature is treated
  as a *source fingerprint*, not noise (see the `decimal_high_is_source_not_bug` learning) —
  so a precision score per reading is cheap to derive (integer vs 1-decimal).
- Keep the merge function pure and give it a pluggable comparator: v1 = `byNewest`; v2 =
  `byNewestThenPrecision` (within a time tolerance). The `OBS_WEB_API_DELTA` metric should
  therefore **also log precision** (`apiDecimals`, `webDecimals`) now, so we have the data to
  design this rule before writing it.
- Watch the interaction with QC and with the API-wins tie-break: precision preference must
  still never select a `qcFailed` reading, and "more precise" should not resurrect a value
  the spatial QC rejected.

## Open questions

1. **Exact-timestamp value tie-break**: API-wins (recommended, official) vs web-wins (has
   QC). Plan assumes API-wins.
2. **Metrics-tier window**: 60 vs 120 min. Recommendation: 90 min (covers a missed METAR
   cycle without pulling history).

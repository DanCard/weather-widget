# Tier 4 — Desktop Robustness & Error-Handling Parity

> Builds on Tiers 1–3 (persistence, accuracy, fidelity). This tier brings the desktop up to the
> Android widget's **error-handling contract** and makes failure/staleness visible instead of silent.

## Context

The desktop refresh loop is currently fragile and opaque (`Main.kt`):
- `refresh()` failures are **swallowed silently** (`catch (e) { /* Ignore background errors */ }`) —
  no UI signal, no log row.
- The popup has only two states: `"Loading…"` (null snapshot) and a live view. There is **no offline
  indicator, no "last updated" timestamp, no error state, no "tap to configure"**.
- Refresh is a fixed 15-min timer — it **always refetches on launch** even when the cache is fresh,
  and ignores the already-ported `TemperatureInterpolator.getUpdatesPerHour` cadence hint.

Android's contract (from CLAUDE.md "Error Handling") is the parity target:

| Scenario | Behavior |
|----------|----------|
| No network | Show cached data + "offline" indicator + last-update timestamp |
| API failure | Try other API; if both fail, cached data + error indicator |
| No data | "Tap to configure" message |
| GPS unavailable | Fall back to last known / default; show location name |

## Goal

A small, explicit UI status model + a resilient refresh loop so the user always knows whether what
they're seeing is **live, stale (offline), or errored**, and the app refetches intelligently.

## 1. UI status model

Introduce a `DataStatus` the popup/tray render from, derived in the refresh loop:
- `Loading` — no cache yet, first fetch in flight.
- `Live(updatedAt)` — last refresh succeeded.
- `Stale(updatedAt, reason)` — showing cache; last refresh failed (offline / API error). Reason
  distinguishes "offline" (network) from "source error".
- `NoData` — no cache and no config / both sources failed with nothing cached → "Tap to configure".

Carry `updatedAt` (last *successful* fetch). Source of truth options: the max `app_logs` REFRESH
timestamp, the max `forecasts.fetchedAt`, or a dedicated `meta(key,value)` row. Prefer a small
`getLastSuccessfulFetch()` DAO read over `forecasts.fetchedAt` (already written every refresh) to
avoid new schema.

## 2. Resilient refresh loop (`Main.kt`)

Replace the silent catch with:
- On success → `status = Live(now)`, log `REFRESH` (already happens).
- On failure → keep showing cached `forecast`, set `status = Stale(lastUpdated, reason)`, and **log
  `REFRESH_FAIL`** to `app_logs` (currently failures vanish — this is the observability gap that hid
  the fractional-seconds bug class). Classify reason: connectivity exception → offline; otherwise
  source error. Preserve the `CancellationException` rethrow discipline from `bestEffort`.
- **Staleness-gated launch fetch**: on startup, if `loadCached` returns data newer than ~30 min,
  skip the immediate refetch; otherwise fetch now. (Mirrors Android "fetch only when stale".)
- **Adaptive cadence (optional)**: use `getUpdatesPerHour(hourly)` to shorten the loop delay when
  temps are swinging fast, lengthen when flat — replacing the hardcoded 15 min. Keep a sane floor
  (e.g. ≥10 min) to stay polite to APIs.

## 3. Popup / tray presentation

- **Header line**: "Updated 4m ago" (relative) from `updatedAt`; show "Offline — last updated …"
  with a muted/⚠ treatment when `Stale`.
- **NoData**: replace bare `"Loading…"` with "Tap to configure" when there's no config, and a
  distinct spinner/text only during genuine first load.
- **Tray tooltip/status**: append "(offline)" when stale so the state is visible without opening the
  popup.
- Keep it lightweight — a status row at the top of `WidgetPopup`, not a redesign.

## 4. (Stretch) app_logs viewer

A small window (reachable from the tray, next to "Forecast Accuracy") listing recent `app_logs`
rows via `getRecentLogs()` — turns the persistent log into something viewable without `sqlite3`.
Natural home for diagnosing REFRESH/REFRESH_FAIL history. Defer if the tier is getting large.

## Files

- `desktop/.../Main.kt` — refresh loop (status, staleness gate, REFRESH_FAIL log, cadence), tray status.
- `desktop/.../` popup composable (`WidgetPopup`) — status header, NoData/offline rendering.
- `shared/.../data/local/desktop/DesktopWeatherDao.kt` — `getLastSuccessfulFetch()` (+ optional
  `meta` table); reuse for the log viewer.
- `desktop/.../DesktopWeatherRepository.kt` — surface last-fetch + classify failure reason if the
  classification lives here rather than in `Main`.
- *(stretch)* new `LogViewerWindow.kt`.

## Tests

- Status derivation as a **pure function** over (cachePresent, lastFetchMs, refreshOutcome, now) →
  `DataStatus`; table-driven (live / stale-offline / stale-error / nodata / staleness-gate boundary).
- `getLastSuccessfulFetch()` DAO round-trip.
- Reason classification: a connectivity exception → offline vs a parse/HTTP error → source error.
- Existing suites stay green (run with the app stopped — `:desktop:test` vs running app conflict).

## Verification

1. `./gradlew :shared:test :desktop:test` green (stop the running app first).
2. Normal run: popup shows "Updated just now"; tray normal.
3. **Kill network** (e.g. drop Wi-Fi / block api.weather.gov), wait one refresh cycle → popup keeps
   last data, shows "Offline — last updated …", tray status shows "(offline)", and `app_logs` has a
   `REFRESH_FAIL` row. Restore network → flips back to `Live`.
4. Launch with a fresh (<30 min) cache → confirm no immediate refetch (no new REFRESH row on start);
   launch with stale cache → immediate refetch.

## Reuse / alignment

- `TemperatureInterpolator.getUpdatesPerHour` (ported in Tier 3, currently unused) → cadence.
- `app_logs` + `DesktopWeatherDao.log/getRecentLogs` (Tier 2) → REFRESH_FAIL + viewer.
- Honor [[nws-observations-fractional-seconds]] and [[desktop-test-running-app-conflict]] during work.

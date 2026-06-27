# Desktop Open-Meteo actuals: self-heal fetches + persistent failure label

## Context

**Problem reported:** On the desktop app showing Open-Meteo ("meteo"), the hourly
temperature actuals (pink line / current temp) were ">3 hours stale" — far worse than the
~10-minute observation cadence the design promises.

**Root cause (confirmed from live DB + logs on 2026-06-27):**
- Open-Meteo observation fetches are failing at the **TCP connect** stage:
  `REFRESH_FAIL  temp actuals: source_error Connect timeout has expired [api.open-meteo.com … connect_timeout=10000 ms]`
  (first today at 11:56, repeating every ~10-min cycle; newest OPEN_METEO obs frozen at 09:00 / fetched 09:55).
- It is **not** an Open-Meteo outage: `curl` to the exact app URL succeeds 5/5 at 0.22s connect.
  Ruled out: IPv6 stall (Open-Meteo is IPv4-only), multi-A dead IP (single A record), proxy
  (none in `/proc/<pid>/environ`), client-wide failure (NWS uses the *same* shared CIO client and
  refreshes fine).
- Two real gaps make this user-visible and sticky:
  1. **No retry / self-heal.** The CIO client has only `HttpTimeout` installed — no
     `HttpRequestRetry`. A transient connect failure becomes hours of frozen actuals because each
     ~10-min cycle makes one sparse attempt and gives up.
  2. **The failure is invisible.** The header keeps showing a climbing *interpolated* current temp,
     and the only staleness cue (fetch-dot age label) renders only when zoomed to a ≤12h span
     (`FetchDotLabel.AGE_LABEL_MAX_HOURS_SPAN`). The user had no signal the data was stuck.

**Intended outcome:** (A) transient connect failures recover automatically; (B) when the
**currently displayed** API's current-temp fetch is failing, the temperature graph shows a
**persistent, detail-rich label** (not a transient toast) so the user knows the on-screen actuals
are stale and why.

Scope: desktop only (`:desktop` + `:shared`). Android unchanged.

---

## Part A — Resilience: retry connect failures (self-heal)

Add Ktor's built-in `HttpRequestRetry` (available in Ktor 2.3.7) to the CIO client that backs all
API wrappers.

**File:** `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherService.kt:46-52`
(the `HttpClient(CIO)` whose `httpClient` is passed to `OpenMeteoApi`, `NwsApi`, etc. at lines 63-69).
Also mirror in `DesktopProcess.kt:103-110` for consistency.

```kotlin
install(HttpRequestRetry) {
    maxRetries = 2
    retryOnExceptionIf { _, cause ->
        cause is io.ktor.client.network.sockets.ConnectTimeoutException ||
        cause is io.ktor.client.network.sockets.SocketTimeoutException ||
        cause is java.io.IOException
    }
    exponentialDelay(base = 2.0, maxDelayMs = 2_000) // ~0.5s, 1s, 2s
}
```

Rationale: re-executing the request opens a fresh connection, papering over single-attempt connect
timeouts (the common case). Keep `maxRetries` modest — these run on background loops and each retry
sits behind the 10s connect timeout. Does **not** mask a sustained outage; Part B covers that.

---

## Part B — Persistent failure label on the temperature graph

### B1. Write a dedicated current-temp fetch **status message** to the DB (daemon side)

Rather than `LIKE`-scraping the free-text `REFRESH_FAIL` rows and diffing two queries, have the
daemon write **one authoritative status row per current-temp fetch outcome** that the UI reads
directly. Reuse the existing `app_logs` table + `log()` (`DesktopWeatherDao.kt:359`) with a
dedicated tag — **no schema migration** (important given this project's migration history).

The observation loop in `DaemonProcess.kt:255-270` (the `refreshObservations()` try/catch) writes on
**both** outcomes, keyed by the active display source:

```kotlin
// success branch (~:259)
weatherDao.log("CURRENT_TEMP_STATUS", "source=$src ok=true", "INFO")
// failure branch (~:266)
weatherDao.log("CURRENT_TEMP_STATUS",
    "source=$src ok=false class=${e::class.simpleName} detail=${e.message}", "WARN")
```

Keep the rich detail (error class, the `Connect timeout … [url=… connect_timeout=10000 ms]` text
already in `e.message`). One row, latest-wins → the UI knows current ok-vs-fail without comparing
success/failure timestamps.

Add a DAO read mirroring the existing `getLastSuccessfulObservationFetch(source)`
(`DesktopWeatherDao.kt:398`), reusing the same connection/query pattern:

```kotlin
data class CurrentTempStatus(val timestamp: Long, val ok: Boolean, val message: String)
fun getLatestCurrentTempStatus(source: String): CurrentTempStatus?
    // SELECT timestamp, message FROM app_logs WHERE tag='CURRENT_TEMP_STATUS'
    //   AND message LIKE 'source=<id>%' ORDER BY timestamp DESC LIMIT 1
```

(If a structured table is later preferred over an `app_logs` tag, it can be swapped behind this one
DAO method without touching the UI.)

### B2. Compute "current-temp fetch is failing for the displayed source" (UI side)

In `Main.kt`, alongside the existing 2-min cache loop (`Main.kt:332-341`) and on
source/view changes, derive a nullable `currentTempFetchError: String?`:

- Let `src = WeatherSource.fromDisplaySource(config.weatherSource).id`.
- `status = weatherDao.getLatestCurrentTempStatus(src)` — the single latest status row.
- **Failing** iff `status != null && !status.ok`.
- Only surface when the **temperature graph for that source is on screen**:
  `config.viewMode.isHourly` (the user said "on temperature graph").
- Build a detail-rich, multi-line message, e.g.:
  ```
  OPEN_METEO current temp not updating
  Connect timeout (10s) · api.open-meteo.com
  Last good obs: 9:00 AM (3h 40m ago)
  Last attempt: 12:32:30 · 2 retries failed
  ```
  Reuse `forecast.currentObservedAt` for the last-good obs age and `formatAge`
  (`LocalalocationResolver.kt:92`) for "3h 40m ago".

### B3. Render as a persistent label (not an auto-dismiss toast)

Reuse the overlay location of the existing toast (`Main.kt:802`, inside the graph `Box`), but as a
**separate, non-auto-dismissing** composable bound to `currentTempFetchError`:

- Visible whenever `currentTempFetchError != null` (i.e., persists while the failure condition
  holds). It clears automatically when a successful fetch lands (next poll flips the condition) or
  the user switches source/view — so it's "permanent" in spirit without going stale.
- Per the user's fallback preference, if we ever want a time-boxed variant instead of a sticky
  label, keep a minimum dwell of **30s**; default is the sticky label.
- Error styling (distinct from the neutral `historyFetchToast`): warning color, small mono detail
  lines, optional `×` dismiss that re-appears on the next *newer* failure timestamp.
- Do **not** reuse the `historyFetchToast` 3s auto-dismiss `LaunchedEffect` (`Main.kt:295`) for this
  label — that's the behavior the user explicitly rejected.

---

## Critical files

| File | Change |
|------|--------|
| `desktop/.../DesktopWeatherService.kt:46` | add `HttpRequestRetry` to CIO client |
| `desktop/.../DesktopProcess.kt:103` | mirror `HttpRequestRetry` |
| `desktop/.../DaemonProcess.kt:255-270` | write `CURRENT_TEMP_STATUS` status row (ok=true/false + detail) on both obs-fetch outcomes |
| `shared/.../data/local/desktop/DesktopWeatherDao.kt:398` | add `getLatestCurrentTempStatus(source)` next to the existing success query |
| `desktop/.../Main.kt:205-341, 802` | derive `currentTempFetchError`; render persistent label in graph overlay |

Reuse, don't reinvent: `getLastSuccessfulObservationFetch` (DAO), the toast overlay slot
(`Main.kt:802`), `DataStatus` state holder, `formatAge` (`LocationResolver.kt:92`),
`WeatherSource.fromDisplaySource`, `config.viewMode.isHourly`.

---

## Verification

1. **Unit (DAO):** extend `desktop/src/test/.../DesktopWeatherDaoTest.kt` — insert
   `CURRENT_TEMP_STATUS` rows (`source=OPEN_METEO ok=false …` then a later `ok=true`) and assert
   `getLatestCurrentTempStatus("OPEN_METEO")` returns the latest-wins row with the right `ok` flag,
   and is source-scoped (an `ok=false` row for NWS doesn't leak into OPEN_METEO).
2. **Retry:** unit-test the retry predicate selects `ConnectTimeoutException`; or run
   `./gradlew :desktop:test`.
3. **End-to-end (live repro available now):** Open-Meteo is *currently* connect-timing-out on this
   machine while reachable via curl. Build + restart via `scripts/buildStart.sh` (rebuild) then
   `scripts/fast-desktop-restart.sh` per the auto-restart convention. With meteo displayed on the
   hourly graph, confirm: (a) the persistent failure label appears with the real detail (timestamp,
   connect timeout, last-good age); (b) when Open-Meteo connectivity recovers (or after a forced
   success), the label clears and the pink actual line advances. Cross-check `app_logs` for the
   `source=OPEN_METEO` REFRESH_FAIL rows and confirm retries are firing.
4. **No regression on NWS:** switch to NWS (fetches succeed) → no label shown.

## Open item (not blocking)

The *why* of the connect-layer failure (transient route flap to Hetzner vs. a CIO endpoint-actor
wedge) is unproven — Part A makes it self-heal and Part B makes it visible regardless. If it recurs
chronically after this, capture an strace/tcpdump on the next live failure to settle the mechanism.

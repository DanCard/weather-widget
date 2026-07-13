# Fix plan: repair the interrupt-driven UI update implementation

**Date:** 2026-07-13
**Context:** Commits `e534715c`…`e8c54ab0` (Gemini) introduced `.data-updated` trigger-file
notifications so the popup UI reloads immediately after daemon fetches (fixing the
resume-from-suspend UI lag). Review found one real bug, a design confusion, a robustness
regression, and a cross-platform diagnosability side effect. This plan keeps the architecture
and repairs those four things.

## Problems being fixed

1. **Failure-status self-clobber (bug).** Every daemon fetch-failure path sets
   `dataStatusState = Stale(…, OFFLINE/SOURCE_ERROR)` then calls `notifyDataUpdated()`. The
   daemon watches its own trigger and its handler (`DaemonProcess.kt:590`) unconditionally sets
   `DataStatus.Live(lastFetch ?: now)` — erasing the Stale status milliseconds after it was set.
   The UI's `.data-updated` handler does the same to the popup's `dataStatus`. Net effect:
   offline/stale indicators are dead after any failed refresh.
2. **Daemon reacts to its own trigger.** The daemon is the producer of almost all
   `.data-updated` events; reacting to them re-runs a full `loadCached()` (DB + aggregation) on
   the blocking watcher thread after every fetch, for state it already holds. The only
   legitimate daemon-consumed event is the UI's manual-refresh notification.
3. **No eventual-consistency fallback.** All UI polling was removed. A missed watch event or a
   dead watcher loop (`if (!key.reset()) break` — no restart) leaves the popup stale forever.
   Project idiom: interrupt-driven as the fast path, slow best-effort poll as the net.
4. **`CURR_TEMP_RESULT` demoted to VERBOSE in :shared** (`41961680`), silently removing those
   diagnostic rows from **Android's** app_logs too. The demotion is only needed on the desktop
   high-frequency on-demand path (per genmon connect + per-minute UI ticker); the sparse
   fetch-cycle resolves should stay queryable (the resolver's own doc comment says appLog is
   for "sparse, queryable summaries").

## Changes

### 1. Split the trigger by direction (fixes #1 and #2)

- `DesktopProcess.kt`: add `const val REFRESH_REQUESTED_TRIGGER = ".refresh-requested"` and
  `fun notifyRefreshRequested()` (same write pattern as `notifyDataUpdated`).
  - `.data-updated` = daemon → UI ("cache changed, reload").
  - `.refresh-requested` = UI → daemon ("I refreshed the DB myself; pick it up").
- `DaemonProcess.kt`: **delete** the `DATA_UPDATED_TRIGGER` watcher branch. Add a
  `REFRESH_REQUESTED_TRIGGER` branch that reloads the cache into `forecastState` and sets
  status via `deriveDataStatus(cachePresent, lastFetch, refreshFailed = false, …)`. This is
  correct here because the UI only writes this trigger after a *successful* `refresh()`
  (exceptions skip the notify). Delete any stale `.refresh-requested` at watcher startup.
- `Main.kt` `onRefreshData`: call `notifyRefreshRequested()` instead of `notifyDataUpdated()`.

### 2. UI handler stops writing `dataStatus` (fixes #1 UI side)

- `Main.kt` `.data-updated` handler: reload `forecast` + `dataUpdateCount++` only; drop the
  `getLastSuccessfulFetch`/`DataStatus.Live` lines. The UI cannot know the daemon's fetch
  outcome from a bare trigger; error/warmup surfacing already flows through the
  `CURRENT_TEMP_STATUS` log contract, which `updateStatus()` re-reads when `dataUpdateCount`
  bumps. (This restores pre-`e534715c` semantics: UI `dataStatus` is set at launch and by its
  own refresh actions.)

### 3. Restore a slow fallback reload in the UI (fixes #3)

- `DesktopProcess.kt`: `const val UI_FALLBACK_RELOAD_MS = 10 * 60 * 1000L` — safety net for
  missed watch events; the `.data-updated` interrupt stays the fast path.
- `Main.kt` `LaunchedEffect(repository)`: after the initial cache load, loop
  `delay(UI_FALLBACK_RELOAD_MS)` → `loadCached()` → `forecast = it` + `dataUpdateCount++`
  (the bump also re-evaluates the status banner, a second safety net for banner escalation).
- Remove `CURRENT_TEMP_UI_INTERVAL_MS` if nothing references it anymore.

### 4. Make the `CURR_TEMP_RESULT` demotion path-scoped, not global (fixes #4)

- `CurrentTemperatureResolver.resolve(...)`: add trailing param
  `resultLogLevel: String = "DEBUG"` and use it for the `CURR_TEMP_RESULT` appLog. Android
  call sites are untouched (default restores their DEBUG persistence).
- `DesktopWeatherRepository`: thread `resultLogLevel` through `resolveForForecastResult`;
  `resolveCurrentTempInMemory` (per genmon connect + per-minute ticker) passes `"VERBOSE"`;
  the sparse `loadCached()`/`refresh()`/`refreshObservations()` paths keep the DEBUG default.

## Tests

- Migrate the `DesktopStartupTest` watcher test: write `.refresh-requested` (not
  `.data-updated`) and assert the daemon's new log line; the daemon must no longer react to
  `.data-updated`.
- Existing `deriveDataStatus` coverage already pins the status derivation used by the new
  daemon branch.
- Run `:shared:test`, `:desktop:test`; compile app unit tests (resolver change is
  default-param, behavior-neutral for Android).

## Rollout

- Rebuild + restart the daily desktop build via `scripts/buildStart-desktop.sh`.
- Write `summaries/260713-fix-interrupt-driven-ui-updates.md` after implementation.

## Explicitly out of scope

- Reworking the 60s panel trigger loop / per-connect markup interpolation (works; revisit only
  if idle CPU measurably regresses).
- UI-side derivation of Stale-vs-Live from `CURRENT_TEMP_STATUS` rows (possible follow-up).

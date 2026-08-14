# Code Review — Desktop daemon/UI architecture (state sharing, IPC, data layer)

Date: 2026-08-13
Scope: `desktop/src/main/kotlin/com/weatherwidget/desktop/` — the two-process split
(`Main.runDaemon` / `Main.runApp`), cross-process state sharing (`DesktopProcess.kt` triggers,
`UiNotifyChannel.kt`, `PanelIpcServer.kt`, `DesktopConfigStore`), and the data layer
(`DesktopWeatherRepository`, `DesktopWeatherService`, `ForecastResult`).

## 1. Overall Assessment

The architecture is **above average for a solo project**, and most of its hard decisions are right:

1. **The daemon/UI split is justified, not gratuitous.** A resident fetcher must outlive the popup,
   serve the XFCE panel when no window exists, and stay lightweight. The hard cases are handled:
   single-instance via `.quit-<launchId>`, deleted-distributable detection, and `XDG_*` env
   passthrough so the two processes rendezvous on the same DB/socket.
2. **The trigger-file + socket dual signal is well-reasoned.** File triggers are lossy across suspend
   and `WatchService` can die on a failed `key.reset()`, so `ui-notify.sock` is the reliable path and
   the file is the fallback. The directionality contract (`.data-updated` daemon→UI only,
   `.refresh-requested` UI→daemon only) prevents the self-echo that once flipped Stale back to Live.
3. **Suspend/resume + network-warmup handling is real-world-correct** (monotonic-vs-wall-clock gap
   detection, thundering-herd debounce, wake-anchored 90s grace window).
4. **Pure-function extraction is consistent and tested** (`determineLaunchRefreshAction`,
   `isResumeSignalLine`, `mergeNonSettingsSave`, `resnapNarrowZoomAfterSpanChange`,
   `repairStaleNarrowZoomFactor`), with a strong `@Category` test discipline.

The problems are **not in the ideas** — they are in **who owns the derived display state**, and in a
few structural seams that made the recent genmon-temperature drift possible.

## 2. Findings (by severity)

### HIGH

**H1 — Derived display state has no single owner (the split-brain that caused the panel bug).**

"Current temperature" and "delta from yesterday" are *re-derived independently in at least three
places*: the daemon's `forecastState` (panel markup, via `resolveCurrentTempInMemory`), the UI
process's `forecast` (popup header, same function), and `loadCached()` (persisted `ForecastResult`
fields). They are synchronized only by "they happen to read the same DB eventually."

The genmon-vs-popup temperature bug was a direct consequence: `refreshObservations()` returned a
`ForecastResult` whose `rawObservations` (network list) disagreed with its own `currentTemp`
(DB-resolved), so the panel's re-resolution drifted from the popup's. The fix patches that one field;
the class of bug remains representable because there is no single writer of the resolved value.

**Recommendation:** make the daemon the single resolver/publisher. After each fetch it resolves the
current status *once*, persists it (a small `current_status` table or published JSON), and pushes a
change signal. The panel and the UI both *consume* the published value instead of re-deriving it.
This makes the split-brain unrepresentable rather than guarded field-by-field.

**H2 — `ForecastResult` conflates raw fetch data with resolved display state.**

`ForecastResult` carries both fetched data (`hourly`, `daily`, `rawObservations`) and resolved
display state (`currentTemp`, `appliedDelta`, `deltaFromYesterday`, `currentCondition`,
`currentObservedAt`). Because they live in one type, the inconsistent object that caused the bug
(`rawObservations=network` + `currentTemp=DB`) was a legal value. Two types — `RawFetch` vs
`ResolvedView` — would have made that a compile error.

**Recommendation:** split the model. The repository returns `RawFetch` (what the API/DB produced);
the resolver produces `ResolvedView` (what to display). `ForecastResult` either disappears or becomes
an explicit join used only where both are genuinely needed.

### MEDIUM

**M1 — Three panel sources; one duplicates the resolver in Python.**

The XFCE panel can be fed by (a) `genmon-weather-bin` (C socket client → `weather.sock`), (b)
`genmon-weather.py` (Python → reads `weather.db` SQLite directly), and (c) the Kotlin resolver that
feeds (a). The Python script re-implements the current-temp precedence ("fresh obs → interpolate →
nearest") with a comment that says it *mirrors the app* — a standing invitation to drift, exactly like
this bug. `extractGenmonScript()` still ships it, and `findGenmonPluginId()` matches either command.

**Recommendation:** keep the socket path (a) as the single panel source, delete the SQLite-reading
Python path and its extraction, and have the C client read only from the daemon's published status
(see H1).

**M2 — `DesktopConfig` is written whole by ~5 windows, patched by a hand-maintained field list.**

`withSettingsFrom` / `mergeNonSettingsSave` / `SETTINGS_OWNED_FIELDS` exist because the settings
window, popup, observations/history windows, and location picker all write the whole `DesktopConfig`
object and used to clobber each other. The field-ownership list is a manual contract that must be
kept in sync with the data class by hand.

**Recommendation:** split config into per-owner stores (or one writer per concern), each with its own
file. This removes the rebase machinery and its test burden, or at minimum reduces it to a single
merge point instead of a documented string list.

**M3 — `DesktopWeatherService` is a concrete class; tests reach into it with reflection.**

`DesktopSynopticFallbackTest` uses `getDeclaredField("nwsApi")` / `"httpClient"` / `"synopticApi"`
to substitute mocks. Reflective field injection is brittle against renames and refactors.

**Recommendation:** extract an interface (or constructor-inject the API clients) so the mockk tests
are first-class rather than reflective.

**M4 — `DaemonProcess.kt` is a ~700-line orchestration inside one function.**

It threads `MutableStateFlow` + `var x: Job?` (fetchJob, catchUpRefreshJob, networkMonitor,
logindMonitor, uiProcess) through one body. It works, but the ownership rules from H1 are implicit
in the ordering rather than explicit in the structure.

**Recommendation:** extract `FetchScheduler`, `CurrentStatusResolver`, and `PanelPublisher` as
focused classes. This makes "daemon owns the resolved value" visible in the type layout.

### LOW

**L1 — The panel serves a *cached* markup string and re-renders on the tail of the accept path.**

`PanelIpcServer` caches the last markup and re-renders after serving because the ~350ms IDW blend is
too slow for the accept path (documented at length). H1 (resolve once, push) removes the need for
this timing dance entirely — the panel would serve a precomputed string.

**L2 — `Main.kt` `runApp` is ~1800 lines of top-level composable wiring.**

Idiomatic Compose, and not a correctness problem, but decomposing into per-window/per-service
composables would make the UI process's data flow easier to audit against the daemon's.

## 3. Target architecture (concrete)

```
                    ┌───────────────────────────────────────────────┐
                    │  daemon (resident)                             │
                    │   FetchScheduler ──> DesktopWeatherRepository  │
                    │        │              (RawFetch)               │
                    │        ▼                                      │
                    │   CurrentStatusResolver ──> current_status     │
                    │   (single writer)          (SQLite or JSON)    │
                    │        │                                      │
                    │        ├──> PanelPublisher ──> weather.sock    │──> genmon-weather-bin
                    │        └──> UiNotifyServer ──> ui-notify.sock  │──> UI process
                    └───────────────────────────────────────────────┘
                                                     │
                                                     ▼
                    ┌───────────────────────────────────────────────┐
                    │  UI process (popup/tray/windows)              │
                    │   reads current_status (published),           │
                    │   reads weather.db for graphs (raw data),     │
                    │   writes only its own config concern          │
                    └───────────────────────────────────────────────┘
```

Key properties this buys:

- **One writer of display state** (the daemon), N readers. The panel and popup can no longer disagree.
- **`RawFetch` vs `ResolvedView`** separated at the type level; inconsistent snapshots are
  unrepresentable.
- **One panel source** (socket → published status); the Python duplicate and its SQLite read go away.
- **Config split by owner**, eliminating `SETTINGS_OWNED_FIELDS` and the rebase dance.
- **Testable seams**: `WeatherApiClient` interface instead of reflective field injection.

## 4. Migration / verification plan

1. Introduce `current_status` persistence + `CurrentStatusResolver` in the daemon; keep the existing
   `resolveCurrentTempInMemory` as the resolver's internals. No UI change yet.
2. Repoint the panel markupProvider at the published status (drop the per-accept re-resolution);
   keep the socket protocol unchanged so `genmon-weather-bin` needs no changes.
3. Repoint the UI header at the published status; remove its per-minute `resolveCurrentTempInMemory`.
4. Delete the `genmon-weather.py` SQLite path + `extractGenmonScript`; verify `findGenmonPluginId`
   still finds the panel.
5. Split `ForecastResult` → `RawFetch` + `ResolvedView` as a mechanical refactor.
6. Split `DesktopConfig` storage by owner (or single writer), removing `withSettingsFrom` /
   `mergeNonSettingsSave` / `SETTINGS_OWNED_FIELDS`.
7. Introduce the `WeatherApiClient` interface; migrate the reflective tests.
8. Decompose `DaemonProcess` into `FetchScheduler` / `CurrentStatusResolver` / `PanelPublisher`.

Each step is independently shippable and testable; the existing `:shared:test` + `:desktop:test`
suites plus the panel-socket smoke check (`genmon-weather-bin`) are the safety net at every step.

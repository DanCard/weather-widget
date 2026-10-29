# Shared source-switch runner (2026-10-29)

## Problem

On desktop, a popup switch from Silurian to Google at 09:59:28 took **6.3 s**. Every other switch
today took 0.4–1.3 s.

```
09:59:28.047 W/Main: CONFIG_SAVE source=popup ... SILURIAN -> GOOGLE_WEATHER
   (5.28 s: no output at all; the UI render loop, normally one line per ~15 ms, stops)
09:59:33.323 I/DaemonProcess: WatchService: .config-changed trigger detected
09:59:34.199 graph repaints with Google data
```

Source: `~/.local/state/weather-widget/autostart-20261029-095207.log` and the `app_logs`
`CONFIG_SAVE` / `CURRENT_STATUS_PUBLISH` rows. Fetching was not involved: the cached data was
fresh, and the check logged `LAUNCH_REFRESH_CHECK action=NONE`.

Root cause: `saveConfigAndNotify` (`DesktopUiApplication.kt:302`) runs these steps in order on the
Compose UI thread:

1. Print the console `CONFIG_SAVE` line.
2. Call `weatherDao.log("CONFIG_SAVE", …)` (`:324`, `DesktopWeatherDao.log` `:1208`). This is a
   synchronous SQLite insert on a new connection with `setBusyTimeout(5000)`
   (`DesktopWeatherDatabase.kt:22`).
3. `configStore.save()` (`:338`), then write the `.config-changed` trigger (`:347`).

Step 2 waited about 5.27 s, which matches the busy timeout, because another connection held the
write lock. The UI froze, and the daemon could not see the switch until the trigger was written.
The lock holder is unknown, because long write transactions are not timed or logged.

Yesterday's Android tap-latency fixes (`f7b4b5d3`, `4f8a1f30`, `cbe467b9`) addressed the same kind
of failure: a user action waiting behind work it does not need. Their mechanisms are Android-only
(broadcast queue, RemoteViews paint preemption, Room query cache). Nothing shared states the rule,
so each platform gets it right only where it was last fixed:

| Step | Android `toggleApi` (`WidgetIntentActionHandler.kt:194`) | Desktop popup (`DesktopSourceViews.kt:97`, `saveConfigAndNotify`) |
|---|---|---|
| Persist choice | `toggleDisplaySource` (prefs). Fast. | `configStore.save` + trigger, **after** a blocking DB insert |
| Staleness check / refresh request | `selectedSourceNeedsRefresh` (Room queries) + `requestForced` (WorkManager + log) **before** the paint | Daemon reacts to the trigger (`launchForecastStaleAfterMs`, `SOURCE_CYCLE_REASON`) |
| Paint | `InteractionRenderDispatcher.render` | Compose recomposes from `config`, then `reloadCachedForecast` |
| View tally | `SourceViewRecorder.record` in `finally`, after the paint ✔ | `DesktopSourceViews.record` after `onUpdateConfig`, still on the UI thread |
| Breadcrumb | `INTERACTION_E2E`, `RefreshToast.logSilent` | `CONFIG_SAVE` `app_logs` row **ahead of** the save |
| E2E timing | `INTERACTION_E2E` | none. The desktop stall was inferred from render-loop gaps |

Only the staleness threshold is shared today (`SourceToggleRefreshPolicy.STALE_MS`).

## Goal

1. One `:shared` `SourceSwitchRunner` owns the source-switch rule: phase order, which phases are
   critical, failure handling, and timing logs. Android and desktop each supply a `Host` for prefs
   or config, painting, refresh requests and DB writes.
2. A source switch never waits on a DB write, log insert, tally or refresh request before the new
   source is persisted and painted.
3. Both platforms emit the same `SOURCE_SWITCH_E2E` line, so a slow switch shows which phase took
   the time.
4. Desktop slow SQLite writes name themselves (`DB_WRITE_SLOW`), so the next lock stall identifies
   its holder.

**Out of scope:**
- Unifying the two staleness probes (Android's window-state probe vs desktop fetch age). The runner
  calls the host's probe; the threshold stays `SourceToggleRefreshPolicy`.
- Yesterday's Android transport fixes (receiver split, paint prioritization, interaction cache).
  They stay in `:app` as `Host` internals.
- The Settings reorder path (`SourceSwitchFetch`, `newlyPrimary`). It benefits from the desktop
  `saveConfigAndNotify` reorder (step 3), but it does not move onto the runner in this plan.

## Design

`shared/src/main/kotlin/com/weatherwidget/shared/sourceview/SourceSwitchRunner.kt`

```kotlin
class SourceSwitchRunner(private val host: Host, private val clock: () -> Long) {
    interface Host {
        /** Critical. Make [to] the displayed source durably. Must not touch app_logs. */
        suspend fun persistSelection(to: WeatherSource)
        /** Critical. Show [to] from what is cached; no network. */
        suspend fun showFromCache(to: WeatherSource)
        /** Deferred. True when [to] is older than SourceToggleRefreshPolicy or has nothing to draw. */
        suspend fun needsRefresh(to: WeatherSource): Boolean
        /** Deferred. Queue a refresh (Android: WorkManager; desktop: no-op, the daemon owns it). */
        suspend fun requestRefresh(to: WeatherSource)
        /** Deferred. SourceViewTally record. */
        suspend fun recordView(to: WeatherSource, trigger: SourceViewTrigger)
        /** Deferred. Persist a breadcrumb row; console logging is the runner's job. */
        suspend fun persistLog(tag: String, message: String, level: String)
    }

    /** Returns after the critical phases. [deferredScope] runs the rest. */
    suspend fun run(from: WeatherSource, to: WeatherSource, trigger: SourceViewTrigger, deferredScope: CoroutineScope)
}
```

Rules the runner enforces:
1. The critical path is `persistSelection` then `showFromCache`, and nothing else. If either one
   throws, the runner logs the failure and rethrows it. Cancellation is preserved.
2. The deferred phase runs in this order: `needsRefresh`/`requestRefresh`, then `recordView`, then
   `persistLog`. It runs in `deferredScope`. A failure in any step is logged with context and does
   not stop the later steps. Nothing is swallowed silently, and cancellation propagates.
3. One console line per switch, written through shared `Log`:
   `SOURCE_SWITCH_E2E from= to= trigger= persistMs= paintMs= criticalMs= refresh=<queued|fresh|failed> deferredMs=`.
   Its `app_logs` copy goes through `persistLog`, which is deferred.
4. **Android ordering change:** the staleness probe and `requestForced` move after the paint. Before
   making that change, confirm the paint does not depend on the queued request (`deferActuals=true`
   and the silent notice suggest it does not). If it does, stop and report.
5. Android's deferred scope must complete before the receiver's `goAsync` result finishes.
   `launchForValidWidget` already owns that lifetime, so the deferred work joins the same job.
   Desktop uses an application IO scope.

### Desktop slow-write instrumentation (`:shared`, `DesktopWeatherDao`)

- Add a `timedWrite(tag) { conn -> … }` helper. It records two times separately: `waitMs`, the time
  to get the write lock, and `holdMs`, the time from getting the lock to commit. It logs
  `DB_WRITE_SLOW tag= waitMs= holdMs= thread= pid=` when **`waitMs ≥ 200` or `holdMs ≥ 500`**.
  Both thresholds can be injected for tests. (User, 2026-10-29: lower than the first draft's 1 s.)
  - `waitMs ≥ 200` catches the victim: about the point where a stalled click feels laggy.
  - `holdMs ≥ 500` catches the culprit: any transaction that holds the lock that long can push a
    waiter past 200 ms.
  - Splitting the two times means a long refresh transaction that blocked nobody logs as a hold
    only.
  - Step 1 checks for noise: if routine refresh transactions log `holdMs` lines every few minutes,
    raise the hold threshold to just above their normal duration and record the numbers here.
- Use it in `log()` and in every `autoCommit = false` write transaction (`DesktopWeatherDao.kt:77,
  125, 193, 341, 461, 579, 626, 733, 873, 900, 1055, 1150`) and in `vacuum()`.
- `Log.w` only. Writing this line to `app_logs` would re-enter the lock it is reporting on.

## Steps

1. **Baseline:** capture current timings before any change.
   - Android: run the emulator `TOGGLE_API` debug hook 10× and record the `INTERACTION_E2E`
     `handler=` timings. Do the same for the daily home button (`RESET_SOURCE`) from a
     non-preferred source.
   - Desktop: reproduce the stall. In `sqlite3`, run `BEGIN IMMEDIATE;` on the live DB and hold it
     for 8 s while cycling the popup. Expect a stall of about 5 s.
2. Add `SourceSwitchRunner` and its JVM tests in `:shared`.
3. **Desktop host.**
   - Popup cycle, `switchDisplaySource`, and the daily home button's source reset
     (`DesktopWidgetHeader.kt:341`, trigger `HOME`) run through the runner.
   - In `saveConfigAndNotify`, the `CONFIG_SAVE` / `merged-away` `app_logs` writes move after
     `configStore.save` and the trigger, onto IO. This covers every config save, Settings included.
   - `DesktopSourceViews.record` moves into `recordView`.
4. **Android host.** `toggleApi` and `resetSource` (the daily home button; user confirmed
   2026-10-29) run through the runner, with the probe and refresh request deferred (rule 4).
   `SourceViewRecorder` moves into `recordView`. The `INTERACTION_E2E` tail stays.
   - `resetSource`'s stale-button branch (the shown source is already preferred) stays outside the
     runner. That branch repaints the header only: there is no switch to persist, record or refresh.
   - Desktop's twin, the daily `HeaderButton.HOME` reset branch (`DesktopWidgetHeader.kt:341`,
     `SourceViewTrigger.HOME`), goes through the runner in step 3. Its `RECENTER_TODAY` branch and
     the hourly-to-daily branch are view changes, not source switches, so they stay as they are.
5. Add `timedWrite` and `DB_WRITE_SLOW` to `DesktopWeatherDao`.
6. **Docs:** add the runner to the shared-runner list in `arch/ARCHITECTURE.md`. Add
   `SOURCE_SWITCH_E2E` / `DB_WRITE_SLOW` diagnosis and the lock-hold repro to `arch/operations.md`.

## Tests

**`:shared` (pure JVM), `SourceSwitchRunnerTest`:**
- Phase order, recorded by a fake host.
- A `persistLog` and `recordView` that block on a latch that is never released: `run` still
  returns after `showFromCache`.
- A deferred failure in `needsRefresh` is logged, and `recordView` / `persistLog` still run.
- A critical failure in `persistSelection` rethrows and skips the paint.
- Cancellation of `deferredScope` propagates and is not logged as a failure.
- The `SOURCE_SWITCH_E2E` timings come from a fake clock.

**`:shared`, `DesktopWeatherDaoSlowWriteTest` (real SQLite, temp file):**
- A second connection holds `BEGIN IMMEDIATE` for longer than the injected wait threshold. `log()`
  emits `DB_WRITE_SLOW` with `waitMs` at or above the hold.
- A `timedWrite` that runs longer than the injected hold threshold, with no contention, logs
  `holdMs` only.
- Writes under both thresholds log nothing.

**`:desktop` integration (popup cycle + config store + real SQLite):**
- With the DB write-locked by another connection, a popup cycle writes `config.json` and the
  trigger in under 500 ms. The `CONFIG_SAVE` row lands after the lock is released.
- **This test must fail on current `main`.** It does: the current code blocks for the busy timeout.

**`:app` Robolectric:**
- `toggleApi` paints before `SourceViewRecorder.record` and before `requestForced`, using a fake
  recorder and refresh requester.
- A guard test that `toggleApi` and `resetSource` go through `SourceSwitchRunner`.
- `resetSource` from a non-preferred source paints the preferred source before `recordView`.
- `resetSource` from the already-preferred source (stale button) repaints and does not call the
  runner.
- Existing toggle tests updated for the new order.

**Gate:** `./scripts/unit-tests.sh`. No instrumented tests are needed; no rendering changes.

## Runtime verification

- **Desktop:** rebuild and restart (`scripts/buildStart-desktop.sh`). Repeat the step-1 lock-hold
  repro. Expect `SOURCE_SWITCH_E2E criticalMs` under 1 s while the lock is held, and
  `DB_WRITE_SLOW tag=app_log waitMs≈<hold>` once it is released. Leave the app running for an hour
  with no lock and count `DB_WRITE_SLOW` lines; if it is more than a few, apply the step-1 noise
  rule. Cycle all five sources with no lock and confirm
  `refresh=fresh` / `queued` match `LAUNCH_REFRESH_CHECK`.
- **Android (emulator, then phones via `installDebug`):** run the `TOGGLE_API` hook 10× and the
  home button from a non-preferred source a few times. Compare
  `SOURCE_SWITCH_E2E paintMs` with the step-1 `INTERACTION_E2E` baseline; it should be no worse,
  and better whenever a stale source queued a forced refresh. A stale source must still get a
  `toggle_api_stale` sync.
- Afterwards, watch `DB_WRITE_SLOW` in the desktop logs for a few days to identify today's lock
  holder. Fixing that holder is a follow-up.

## Implementation notes (2026-10-29)

Where the build differs from the design above:

1. **Deferred order is `record_view` → `refresh` → `persist_log`, not refresh first.** `recordView` runs
   `NonCancellable` and the deferred job starts `ATOMIC`, so an interaction cancelled mid-paint still
   counts its switch, as Android's old `finally` did. The steps after it stop on cancellation.
2. **Two console lines.** `phase=critical` is logged as soon as the paint returns, so a hung deferred
   phase cannot hide the switch latency. `phase=done` (with `refresh=` and `deferredMs=`) is logged and
   persisted after the deferred phase.
3. **`refreshIfStale` replaces `needsRefresh` + `requestRefresh`.** One host call returns a
   `RefreshOutcome`: `QUEUED`, `FRESH`, `DELEGATED` (desktop), `SKIPPED` (no location) or `FAILED`.
4. **A failed paint still runs the deferred phase.** On Android the failure is held until the
   `coroutineScope` has joined the deferred job, then rethrown; rethrowing inside the scope cancelled
   the refresh. The existing `DailyViewApiToggleIntegrationRoboTest` and
   `WidgetRefreshToastIntegrationRoboTest` cases paint an unbound widget, which throws under
   Robolectric, so they now cover this path.
5. **`DB_WRITE_SLOW` is a connection proxy (`DesktopWriteTiming`), not a `timedWrite` helper at each
   site.** `DesktopWeatherDatabase.getConnection()` wraps every connection, so the 12 transaction sites
   are unchanged, and autocommit writes, `VACUUM` and slow opens are covered too.
   - Checked against sqlite-jdbc 3.53.1.0: `setAutoCommit(false)` takes the write lock at once (IMMEDIATE),
     and `commit()` immediately begins the next IMMEDIATE transaction. So the lock is held until
     `close()`, and `holdMs` is measured to `close()` / `setAutoCommit(true)`.
   - Lines carry `kind=txn|autocommit|open`, `caller=<Class.method>` (the first stack frame outside
     JDBC and the proxy), `thread=` and `pid=`.
6. **Desktop config save order lives in `commitDesktopConfigSave`** (`DesktopConfig.kt`): save, then
   signal the daemon, then hand the `CONFIG_SAVE` rows to a single `config-save-log` thread. Extracting
   it from the composable made the order testable. A mutation that writes the log first fails
   `DesktopSourceSwitchLockIntegrationTest` (`expected [save, signal] but was [log, save, signal]`).

### Step 1 baseline

- **Android** (`emulator-5554`, widget 63), 10 `TOGGLE_API` taps, `INTERACTION_E2E handler=`:
  1453, 535, 268, 500, 366, 262, 399, 202, 646, 326 ms. Median about 380 ms.
- **Desktop:** the 09:59:28 incident above, 6.3 s click-to-publish; every other switch today took
  0.4–1.3 s. No scripted repro yet: the popup has no automation hook, so the lock-hold repro is part of
  runtime verification.

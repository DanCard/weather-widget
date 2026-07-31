# Desktop "Refresh Data" fetched, persisted, then threw the result away

**Date:** 2026-07-31
**Plan:** none (live debug from a cross-device discrepancy report)

## Outcome

Started as "Pixel 7 Pro says high of 88 for Sunday and Monday. Desktop says 86 for Sunday. Is the
Pixel forecast stale?" It was not — **the desktop was**, by ~1h50m. That answer held, but pressing
Refresh Data on the desktop did nothing, and *that* turned out to be a real bug: the button fetched
successfully, wrote fresh rows to the DB, and then discarded the result before the UI could use it.

Fixed, tested, rebuilt and restarted. Nothing committed — changes are in the working tree.

## Part 1 — the original question (no bug)

Both devices are on the same site (37.417, −122.089) and both display NWS, so it was apples-to-apples:

| | NWS Sun 08-02 high | last forecast fetch |
|---|---|---|
| Pixel 7 Pro | **88°** | 10:31:27 |
| Desktop | **86°** | 08:43:55 |

NWS revised Sunday 86° → 88° at ~10:31; the Pixel caught it, the desktop's newest fetch predated it.
The two devices *agreed* everywhere their timestamps lined up (both 86° at ~00:05, both 88° at
~22:47 on the 30th), and Monday matched at 88° on both. No divergence in the data, only in the clock.

NWS oscillates diurnally here by ~2° — daytime model runs read warmer than overnight ones for this
grid point — so a 2° cross-device gap is the expected magnitude of pure fetch skew. Same shape as
`android_future_day_integer_rounding_deliberate`.

## Part 2 — the actual bug

### The click left no trace at all

The first press (~10:30) produced **zero** evidence: newest `REFRESH` row was 08:43:59, no WARN/ERROR
anywhere near, and the only activity at 10:31 was the 10-minute observation poll (byte-identical in
shape to the 10:21 and 10:11 bursts). Log-reading alone could not distinguish "never clicked" from
"clicked but no-op" from "threw", so breadcrumbs went in first — deliberately, before any fix.

Note `Log.i` on desktop is **console-only** (`DesktopLogSink`); the sink at `Main.kt:186` is just the
`CurrentTemperatureResolver` hook. Persistent breadcrumbs must go through `weatherDao.log(...)`.

### What the breadcrumbs caught

```
11:17:20  REFRESH_CLICK  click received (isRefreshing=false)              ✓ click delivered
11:17:20  REFRESH_CLICK  coroutine entered — calling onRefreshData        ✓ scope alive
11:17:20  REFRESH_CLICK  onRefreshData repository=present  lat=37.416824  ✓ NOT null
11:17:20  REFRESH_ENTER  source=NWS                                       ✓ fetch started
11:17:25  REFRESH        source=NWS hourly=156 daily=8 obs=1729           ✓ fetch SUCCEEDED
11:17:25  REFRESH_CLICK  onRefreshData threw ForgottenCoroutineScopeException:
                         rememberCoroutineScope left the composition      ✗
```

The `"repository.refresh() completed"` breadcrumb never fired, pinning the throw to the exact instant
`refresh()` returns — `withContext(Dispatchers.IO)` resuming onto a Compose scope that is gone.

Consequences in order:
1. `forecast = repo.refresh()` — **the assignment never happens**; UI keeps rendering the old value.
2. `notifyRefreshRequested()` — **never runs**; the daemon is never told to reload either.
3. The DB *does* get the rows. Sunday NWS `88.0/58.0` was written at 11:17:25.

### Root cause

`SettingsWindow.kt:68` used `rememberCoroutineScope()` — bound to the Settings window's composition —
to run an app-level network fetch. Closing Settings during the ~5s fetch cancels it.

**One bug, two faces, decided by whether the scope outlives the network call:**

- Scope dies **mid-fetch** (the 10:30 case) → `CancellationException` → and
  `DesktopWeatherRepository.refresh()`'s catch read `if (e !is CancellationException)` before logging.
  The failure was **deliberately swallowed**. Total silence.
- Scope dies **as the fetch lands** (the 11:17 case) → `refresh()` completes and writes its `REFRESH`
  row, *then* the resume throws. Partial trace.

`finally { isRefreshing = false }` cleared the spinner on success, cancellation and failure alike, so
all three rendered identically. **The button's visual state was never evidence of anything** — which
is why five minutes of log-reading beat any amount of watching the UI.

## What changed

`desktop/.../SettingsWindow.kt`
- `onRefreshData` is now a plain `() -> Unit` (was `suspend () -> Unit`); the window just calls it.
- `isRefreshing` is now a **parameter**, not local `remember` state. Progress living with the caller
  is what lets the work outlive this window.
- Removed the `scope.launch` around the refresh. The synchronous `onRefreshBreadcrumb("click
  received")` fires *before* anything else, so even a dead scope leaves proof the click landed.

`desktop/.../Main.kt`
- New `refreshInFlight` state owned beside the other window-visibility flags.
- `onRefreshData` launches on **`uiScope`** (`Main.kt:244`) — the outer composable's scope that
  already owns `weatherDb`, `weatherDao` and `repository` and outlives every child window. Mirrors
  the existing `onNeedHistory` idiom at `Main.kt:320` (in-flight flag → `uiScope.launch` →
  try/catch/finally).
- `CancellationException` is caught, logged and **rethrown**; other exceptions are logged and
  **swallowed** — rethrowing from a `launch` on `uiScope` would take down the scope and every other
  feature hanging off it.
- `!refreshInFlight` guard so rapid clicks can't stack concurrent fetches.

`desktop/.../DesktopWeatherRepository.kt`
- `refresh()` now writes a `REFRESH_ENTER` row on entry. The terminal `REFRESH` row only lands on
  success, so a hang or cancellation previously left nothing.
- The cancellation branch now writes `REFRESH_CANCELLED` before rethrowing. It still stays out of
  `CURRENT_TEMP_STATUS` (which drives the staleness badge) — a cancelled refresh is not a pipeline
  failure — but it is no longer silent.

## Verification

- Full `:desktop:test` suite green (29 tests).
- `:desktop:compileKotlin` clean.
- Rebuilt and restarted via `scripts/buildStart-desktop.sh`; 2 processes (daemon + UI), healthy.
- End-to-end proof the pipeline itself was always fine: the restart's own launch refresh at 10:39:07
  pulled Sunday to 88°/58°, matching the Pixel exactly. The 86° was stale, not wrong.

## Regression coverage

Two new tests in `DesktopUiTest`:
- `testRefreshingStateIsDrivenByCaller` — `isRefreshing = true` renders "Refreshing…" and disables
  the button.
- `testRefreshClickSuppressedWhileRefreshInFlight` — no re-dispatch while a refresh is in flight.

**Verified they can genuinely fail:** flipping `enabled = !isRefreshing` to `enabled = true` failed
both; reverting restored green.

The pre-existing `testSettingsWindowRefreshAndLogsButtons` passed happily *with* the bug —
`waitForIdle()` obligingly ran the doomed coroutine — which is why the new tests assert the
caller-owned contract instead of just "callback fired".

## Notes

**`rememberCoroutineScope` is for work that is meaningless once the composable is gone** — an
animation, a snackbar — not for a network write whose result outlives the UI that triggered it. That
is the whole lesson; the exception name is just how it surfaced.

**"Not an error" got implemented as "not an event."** Filtering `CancellationException` out of
failure logging is correct — cancellation is not a fault. But it turned a real dropped fetch into no
evidence whatsoever, and cost a full extra debugging round-trip. Worth remembering anywhere else this
codebase filters cancellation.

**A hypothesis the breadcrumbs disproved.** The prime suspect was `repository?.let { }` silently
no-opping on a null repository — plausible given `desktop_config_write_races` and a `RESUME_DETECT`
warning at 09:41. The very first instrumented click printed `repository=present`. `?.let` as a
control-flow guard is still a real hazard (it swallows the negative case), which is why the
null-branch breadcrumb was kept — but it was not this bug. Measure, don't estimate.

New permanent tags: `REFRESH_CLICK`, `REFRESH_ENTER`, `REFRESH_CANCELLED`. One INFO row per click;
they would have caught this in seconds. Keep them (`feedback_permanent_debug_logging`).

## Watch for

- `refreshObservations()` (`DesktopWeatherRepository.kt:411`) has the identical
  `if (e !is CancellationException)` pattern and the same blind spot. Left alone deliberately —
  outside the requested scope — but it is the next place this hides.
- The new tests lock in that progress state is caller-owned, which is the *precondition* for the work
  outliving the window. They cannot prove the lifetime guarantee itself: that is enforced
  structurally by which scope `Main` launches on, and is not observable from inside `SettingsWindow`.
  A green suite here does not pin the scope choice.
- The first failure (10:30) was never reproduced under instrumentation — only the second. Both are
  explained by the same root cause and the fix addresses both paths, but the mid-fetch cancellation
  variant is inferred from the `CancellationException` filter, not directly observed.

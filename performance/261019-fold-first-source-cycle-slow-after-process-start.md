# Fold 4: the first cycle through the sources is slow — and why it was invisible

**Report (user, 2026-10-19):** switching API on the Fold 4 is sometimes slow, usually only on the
first cycle through the sources; later cycles are fast enough.

## What the logs show

Fold 4 (SM-F936U1), app_logs 2026-10-16 → 10-19 plus the 40 minutes logcat still held.

- **Not the launcher, not delivery, not the freezer.** Launcher touch-up → `onReceive` ≈ 20–25 ms,
  including when Samsung's Freecess had to thaw the process (`FreecessController UFZ … broadcast-…
  ACTION_TOGGLE_API`). The launcher lays the widget out 6–11 ms after each `WIDGET_PUSH`
  (`TraceUtils widget onLayout <widgetId>` — the number is a widget id, not ms).
- **Not lock contention, not the dispatcher.** `queue=` 2–10 ms; zero `INTERACTION_LOCK_PERF` lines.
- **The handler itself warms up per process.** `TOGGLE_API_TIMING`, grouped by process
  (`WIDGET_PUSH pid=`, 47 processes in 3 days): first 4 toggles in a process average **539 ms**
  (max 943), later ones **403 ms** (max 682). Toggles minutes after a process start run 550–710 ms;
  in a process hours old, 130–430 ms. Reproduced after install today: 786, 494, 748, 432, 807 ms.
  Each source's first render pays its own warm-up, which is why a whole *cycle* feels slow, not
  one tap. `DAILY_INTERACTION_PERF dailyDataMs` is bimodal (~6 ms vs ~210–245 ms) in the same
  process, so part of each tap is a cache miss too.
- **Why the process is so often fresh.** Debug build, `dexopt status=run-from-apk`: no AOT code,
  only in-memory JIT that dies with the process. No baseline profile in the project. Most
  `PROC_EXIT` rows are `PACKAGE_UPDATED` (our installs); also `LOW_MEMORY`, `SIGNALED`, task removal.

## Why we could not see a truly slow tap

`TOGGLE_API_TIMING` starts inside `toggleApi`, i.e. after process start, Hilt injection, the
dispatcher queue and the widget lock. The end-to-end figure (`INTERACTION_E2E`, broadcast receipt →
done) was logcat-only, and logcat on the Fold holds ~40 minutes — the 10:47–10:52 cycle, with taps
7–9 s apart, had already rolled off.

## Change (logging only)

- Persist `INTERACTION_E2E` to app_logs for the **first 8 widget taps of every process** and for
  **any tap ≥ 800 ms**: `e2e queue lockWait work tapInProcess procAge cold`
  (`InteractionLatency`, written in `WidgetActionReceiver.launchForWidget` after the paint is pushed,
  so not on the measured path). `cold=true` = process age at receipt < 10 s (the tap likely
  started the process).
- Lock wait is carried to the tap through an `InteractionTrace` coroutine-context element that
  `WidgetInteractionCoordinator.withWidgetLock` adds to; worker paints have no trace and are unchanged.
- Tests: `InteractionLatencyTest` (rule, format, lock wait reaches the trace).

Query:

```sql
select datetime(timestamp/1000,'unixepoch','localtime'), message from app_logs
where tag='INTERACTION_E2E' order by timestamp desc;
```

## Next, if it is still felt

- Next report: read the rows. `cold=true` with large `e2e − work` → process start; large `work` on
  `tapInProcess` 1–N only → warm-up; large `lockWait` → contention.
- Warm-up fix candidates (not done): a Baseline Profile (`profileinstaller`) for the release build;
  prewarm each visible source's daily render once after process start, off the tap path.

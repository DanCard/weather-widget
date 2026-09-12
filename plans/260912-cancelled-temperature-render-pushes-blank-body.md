# Cancelled temperature render pushes a blank widget body

**Bug report:** "Weather widget bug report" email, Sat 2026-09-12 12:14:55 — "widgit not displaying".
Pixel 7 Pro, Android 17, build 26091001, three widgets (#78 DAILY graph, #79 DAILY text,
#88 TEMPERATURE 7×4) at Santa Clara (37.3725, -121.9809). DB pulled to
`backups/20260912_133416_pixel_7_pro_2A191FDH300PPW/`.

## What the user saw

Widget #88 (temperature graph) with a header and an empty body for ~4 minutes,
12:10:20 → 12:14:23. The other two widgets were fine.

## Timeline (app_logs, local time)

| Time | Event |
|------|-------|
| 12:08:40 | Process cold start (`onUpdate_entry`), all three widgets paint OK from cache. |
| 12:09:34 | `GPS_RESAMPLE outcome=location_moved` Mountain View → Santa Clara (K1 Speed), applied immediately. Three syncs enqueued (`on_update_stale`, `location_changed`, forced `unspecified`). |
| 12:09:44 | Fourth sync: forced `hourly_gaps` (requestedAt=1789240184466). |
| 12:09:59 | Observation backfill worker starts (`temperature_graph_sparse_history widget=88`). |
| 12:10:17.845 | The `hourly_gaps` worker reaches `worker_paint_start`; #79 and #78 push fine at 12:10:18. |
| 12:10:20.134 | `OBS_BACKFILL_CANCELLED` — the backfill worker is stopped. |
| 12:10:20.317 / .380 | Both workers **re-run immediately**: `SYNC_START reason=hourly_gaps force=false` (same request — `FORCED_REFRESH_SATISFIED requestedAt=1789240184466` drops the force) and a new `obsBackfillOnly=true`. |
| 12:10:20.612 | `HOURLY_PAINT_TRACE phase=resolve_NULL_BITMAP widget=88 hours=130 renderMs=1` |
| 12:10:20.694 | `WIDGET_PAINT widget=88 origin=WORKER_FETCH state=data push=partial` — **the blank is pushed**. |
| 12:10:20.759 | `SYNC_CANCELLED Worker cancelled. stopReason=-256 msg=Job was cancelled` — the painting worker unwinds. |
| 12:10:21 → 12:14:23 | **Screen off.** Nothing at all is logged by any coroutine for four minutes; the next event is `GPS_RESAMPLE trigger=screen_on`. The two in-flight HTTP calls (Synoptic radius fetch, NWS `AW020`) both resume within 100 ms of the wake — `SYNC_PERF synoptic=242663ms` is wall-clock across the sleep (the same fetch measured 5.7 s on 2026-09-12 14:03), and the `AW020` 30 s timeout fires late for the same reason. |
| 12:14:23 | Screen on. The blank pushed at 12:10:20 is the first thing on screen; the re-run worker repaints #88 within a second (`worker_paint_done` 12:14:23, cache paint 12:14:27). Bug report filed 12:14:55. |

Two independent WorkManager workers (unique names `weather_widget_one_time` and
`weather_widget_observation_backfill`) were stopped within 600 ms of each other and both re-ran
at once. No app code calls `REPLACE`/`cancelUniqueWork` on either name, and both requests carry a
`NetworkType.CONNECTED` constraint; the user had just arrived at a venue and the very next NWS
request timed out. The most consistent reading is a connectivity flap: WorkManager stops
constrained workers when the constraint drops and reschedules them when it returns. Logcat no
longer covers 12:10 so this is not proven, but **the fix does not depend on the trigger** — any
cancellation of an in-flight temperature paint produces the blank.

`stopReason=-256` (`STOP_REASON_NOT_STOPPED`) is a race, not evidence against a WorkManager stop:
`WorkerWrapper.interrupt` cancels the result future (which cancels the coroutine job → "Job was
cancelled") *before* it calls `worker.stop(reason)`, so the `catch` on the worker thread can read
the field before it is set.

## Root cause

`TemperatureStateResolver.kt:384-409`:

```kotlin
bitmap = try {
    TemperatureGraphRenderer.renderGraph(..., job = coroutineContext[Job], ...)
} catch (e: Exception) {
    Log.e(TAG, "renderGraph failed", e)
    null
}
```

`renderGraph` calls `job?.ensureActive()` on entry (`TemperatureGraphRenderer.kt:134`). When the
paint coroutine is cancelled, that throws `CancellationException` — which **is** an `Exception`,
so the catch swallows it (`renderMs=1`) and the resolver keeps running inside a cancelled
coroutine as if the render had merely failed:

1. `GraphState(bitmap = null, showTextMode = true)` is built.
2. `TemperatureViewBinder.showTextMode()` (`TemperatureViewBinder.kt:219`) sets `graph_view`
   GONE and `text_container` VISIBLE.
3. `text_container` is the **daily** text layout (`widget_weather.xml:15`); its `dayN_container`
   children default to `visibility="gone"` and nothing in the temperature path populates them.
   The pushed body is therefore empty.
4. The push goes out (`push=partial`), and because RemoteViews visibility is sticky the widget
   stays blank until another paint replaces it.

Only the temperature path has this swallow. `PrecipViewHandler.kt:375` and
`CloudCoverViewHandler.kt:496` call their renderers uncaught, so a cancellation propagates and the
cancelled paint pushes nothing. `DailyViewHandler` does not pass a `Job` at all.

A genuine render exception (not cancellation) has the same visible outcome — an empty body — and is
logged only via `Log.e`, so it never reaches `app_logs` and the bug report would have carried no
trace of it.

## Fix

### 1. Let cancellation propagate (the bug)

In `TemperatureStateResolver`, rethrow `CancellationException` before the generic catch, matching
the pattern already used in `FullSyncPipeline.kt:328` and `WeatherWidgetWorker.kt:198`:

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
```

A cancelled paint then aborts before the push; the replacing paint (the reason it was cancelled)
draws the widget. Nothing blank is ever sent.

### 2. Never push an empty body for a real render failure

With cancellation excluded, `bitmap == null && useGraph` means `renderGraph` actually threw. Today
that falls into the same un-populated text container. Change the fallback to keep the last good
frame rather than substitute an empty one:

- Log the exception through `appLogDao.logException("HOURLY_RENDER_EXCEPTION", ...)` so it is
  visible in bug reports (keep the existing `resolve_NULL_BITMAP` trace line).
- In `TemperatureViewBinder`, when `useGraph && bitmap == null`, **do not** call `showTextMode()`
  — leave the graph views untouched so the previous bitmap stays on screen. `showTextMode()`
  remains for the legitimate `!useGraph` (1-row) case.

This follows the existing rule for the widget: recover or keep real data, don't substitute a
fallback that looks like "nothing".

### 3. Tests

- **`TemperatureStateResolverCancellationTest`** (Robolectric, SDK 35 — the resolver needs
  `Context`/`Bitmap`): resolve with a `Job` that is already cancelled; assert `CancellationException`
  is thrown and no `ResolutionResult` is returned. Also assert with a live job that
  `showTextMode` is false and a bitmap is produced, proving the test can fail.
- **`TemperatureViewBinder` test**: bind a state with `useGraph=true, bitmap=null`; assert no
  `setViewVisibility(R.id.text_container, VISIBLE)` / `graph_view GONE` calls are recorded
  (existing `reapply()`/shadow pattern from `reapply_test_pattern`).

### Out of scope (separate plans)

- ~~Paint sequenced behind a 4-minute synoptic backfill~~ — withdrawn. The 242 s was the phone
  asleep with the screen off (see timeline), not a slow fetch; the same synoptic call took 5.7 s
  in the 14:03 reproduction. What remains true is that the paint sits *after* the network stages
  in `FullSyncPipeline`, so a genuinely slow station request would delay the repaint; not
  observed here.
- **Four syncs in ten seconds on a location move** (`on_update_stale`, `location_changed`, forced
  `unspecified`, forced `hourly_gaps`, plus two backfills) — the fan-out that made an overlapping
  paint likely in the first place.
- Whether the 1-row temperature view (`!useGraph`) ever renders anything into `text_container`.

## Verification

1. `./gradlew testDebugUnitTest --tests "*TemperatureStateResolverCancellation*" --tests "*TemperatureViewBinder*"`
2. `./gradlew installDebug` on the Pixel; force a mid-paint cancellation by toggling WiFi off/on
   during a forced sync (`adb shell svc wifi disable` / `enable` ~1 s after `SYNC_START`), confirm
   no `resolve_NULL_BITMAP` followed by a `WIDGET_PAINT … push=partial` for the same widget, and
   the widget never shows an empty body.

## Summary (done 2026-09-12)

**Changed**
- `TemperatureStateResolver.kt`: extracted `renderOrNull(onFailure) { render() }` — rethrows
  `CancellationException`, reports every other exception through the callback and returns null.
  The render site now logs real failures as `HOURLY_RENDER_EXCEPTION` (via `appLogDao.logException`)
  so they reach bug reports; `GraphState.showTextMode` is now exactly `!useGraph`.
- `TemperatureViewBinder.kt`: the null-bitmap branch no longer falls through to `showTextMode()`;
  graph views are left untouched so the previous frame stands.
- `TemperatureWidgetState.kt`: documented `showTextMode` as the 1-row-layout flag.

**Tests** (both new, 5 cases, green; full `Temperature*` handler suite 46/46)
- `TemperatureStateResolverRenderGuardTest` (plain JUnit, ShortDuration): cancellation propagates
  unreported; other exceptions → null + one report; success passes through.
- `TemperatureViewBinderNullBitmapRoboTest` (Robolectric, LongDuration): `reapply()` onto a
  graph-showing view with `useGraph=true, bitmap=null` leaves `graph_view` VISIBLE /
  `text_container` GONE; the 1-row state still flips to text. Verified the first case fails when
  the old `|| bitmap == null` fallthrough is restored.

**On-device (Pixel 7 Pro, debug build 14:03)**
- Reproduced the trigger: `svc wifi disable` at `worker_paint_start` → the running `periodic_60m`
  worker logged `SYNC_CANCELLED stopReason=-256 msg=Job was cancelled` with no `worker_paint_done`,
  and WorkManager re-ran the same request 400 ms later — the bug report's signature exactly,
  `-256` included. This confirms a connectivity change stops a CONNECTED-constrained worker
  mid-paint (on 2026-09-12 the flap was almost certainly the screen going off and the venue WiFi
  dropping with it). The re-run painted all three widgets; none went blank.
- Caveat: the user has since toggled #88 to DAILY, so the cancellation went through the daily
  path on-device; the temperature `renderGraph` catch is covered by the unit tests.

**Not done** — see "Out of scope" above (sync fan-out on a location move).

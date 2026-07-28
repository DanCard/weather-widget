# Code Review: WidgetIntentRouter.kt (Priority 1, file 5)

Source: `plans/260725-code-review-queue.md` (score 10/12)
Reviewed: 2026-07-28
File: `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt` (1339 lines)

Distinct from `plans/260728-widget-intent-router-code-review-fixes.md`, which covered the three
findings closed by commit `9db80db6` (per-widget mutex, per-source fetch-success cooldown, isolated
batch repaint). This review is the full queue pass over the file as it stands after that commit;
two of the findings below are direct fallout from it.

## Overall Assessment

The interaction path is in good shape structurally: the per-widget `Mutex` serialization, the
cache-first repaint self-heal, and the `sourceNeedsRefresh` policy split (pure, DB-free, unit-tested)
are all sound. Inline rationale is unusually strong — nearly every non-obvious decision carries a
"why" comment with a concrete past-bug reference (the `handleToggleApi` enqueue-before-repaint
ordering comment at `:489-497` is a good example of reasoning that would otherwise be lost).

The problems cluster in two places:

1. **Failure observability is inconsistent.** `handleSetView` learned — from the 2026-07-08
   source-gap NPE — to persist an `app_logs` breadcrumb on both success and failure. The other six
   handlers still swallow to logcat only, despite mutating persisted state before rendering.
2. **The new mutex interacts badly with the pre-existing resize "debounce".** Serializing a path
   that sleeps under the lock converted overlapping delays into additive ones.

Test coverage is good: 16 test files reference the router, including a dedicated pure-coroutine
`WidgetIntentRouterExecutionTest` for the lock and batch-loop semantics.

## Findings

### F1 — Six handlers persist state, then swallow render failures with no `app_logs` row [HIGH]

`handleNavigation` (`:197-205`), `handleCycleZoom` (`:379-387`), `handleToggleApi` (`:431-439`),
`handleToggleView` (`:528-536`), `handleTogglePrecip` (`:739-747`) and `handleResize` (`:875-883`)
each catch `Exception` and only `Log.e` to logcat.

Every corresponding `*Internal` mutates persisted state *before* rendering:

| Handler | Mutation | Line |
|---------|----------|------|
| `handleToggleApiInternal` | `toggleDisplaySource()` | `:449` |
| `handleToggleViewInternal` | `toggleViewMode()` | `:546` |
| `handleTogglePrecipInternal` | `togglePrecipitationMode()` | `:757` |
| `handleCycleZoomInternal` | `cycleZoomLevel()` / `setHourlyOffset()` | `:405`, `:410` |
| `handleDailyNavigation` | `navigateLeft()` / `navigateRight()` | `:318-320` |

If the render then throws, the pref is flipped but the surface still shows the old content. The
user's next tap toggles *back* — the button reads as dead — and nothing reaches `app_logs`, so a
sweep sees nothing. `handleSetView` (`:778-808`) is the only handler that does this right, and its
own comment states the lesson:

> the day-tap path bypasses refreshWidget's breadcrumb, which let the 2026-07-08 source-gap NPE
> hide from app_logs sweeps entirely

That reasoning applies verbatim to the five tap handlers users hit most.

**Fix:** Extract the `handleSetView` try/catch into a shared `runInteraction(tag, metadata)` wrapper
that takes the lock, emits `${tag}_RENDER_OK` on success and `${tag}_FAIL` on failure, and re-throws
`CancellationException`. Collapses six near-identical 10-line blocks into one.

### F2 — Resize debounce sleeps inside the per-widget mutex, amplifying instead of debouncing [HIGH]

`handleResize:876` takes the lock; `handleResizeInternal:891` opens with
`delay(RESIZE_DEBOUNCE_MS)`. Two problems compound:

- **It was never a debounce.** Each resize broadcast gets its own coroutine that sleeps 250 ms then
  renders unconditionally. Nothing coalesces or cancels superseded events, so a drag-resize emitting
  10 `OPTION_APPWIDGET_*` updates does 10 full renders.
- **The mutex made it additive.** Those sleeps used to overlap; now they serialize into
  10 × (250 ms + render) ≈ 3 s of held lock. Every tap on that widget stalls behind it, and
  `renderAllWidgetsFromCache` (`:163-185`) — a *sequential* `forEachWidgetIsolated` loop — blocks
  too, so one mid-resize widget delays the blank-widget self-heal repaint of all the others.

Sleeping under a lock is the root mistake.

> **Correction after on-device measurement (2026-07-28).** The second bullet above overstates the
> case, and the first is inert on this transport. Measured on an API 36 emulator, the platform
> delivers `ACTION_APPWIDGET_UPDATE_OPTIONS` to a manifest receiver **330-400 ms apart even when the
> sender issues them in a tight loop with no sleep** — the broadcast queue serializes them. Since
> that spacing exceeds `RESIZE_DEBOUNCE_MS` (250 ms):
>
> - Resize events essentially never arrive inside the debounce window, so the coalescing branch does
>   not fire for launcher-driven resizes, before or after this change. There is no "10 renders per
>   drag" to collapse.
> - The sleeps therefore never piled up into the ~3 s figure either. The real cost was a flat 250 ms
>   of needlessly-held mutex per event. That still matters when renders are slow — a 983 ms
>   `RESIZE_SLOW` was observed in this very run, so at 1233 ms of held lock against ~350 ms arrivals
>   the queue does back up and taps do stall — but the mechanism is slow renders, not additive
>   sleeps.
>
> The fix stands on the narrower ground: **the wait no longer happens under the lock**, which is an
> unconditional win, and the debounce still guards hosts that batch faster than the AOSP queue
> (fold/unfold and orientation changes emit several option updates). Severity is closer to MED than
> HIGH.

**Fix:** Move the delay outside the lock and make it a real trailing-edge debounce: stamp a
per-widget request token before sleeping, and drop the event after waking if a newer request
arrived. Ten events become one render, and the lock is held only for that render. Clear the entry in
`forgetWidget`.

### F3 — `toggle_api` staleness probe inspects a different window than the render [MED]

`handleToggleApiInternal:463-469` builds the probe window as `now.plusHours(hourlyOffset)`, but the
render resolves it via `stateManager.resolveHourlyCenterTime(appWidgetId, now, zoom)`
(`refreshGraphView:1123`), which returns the **pinned anchor** whenever the window excludes `now`
(`WidgetStateManager.kt:706-711`).

So on an anchored past/future window the probe examines a live window that drifts away from the
anchor by however long ago it was pinned — while `sourceWindowState`'s own doc comment (`:636-638`)
claims it describes "the currently-displayed window". Result is a wrong `hasHourly` feeding
`sourceNeedsRefresh`: either a spurious forced fetch, or a needed one skipped while the pinned view
stays empty.

Fallout from `resolveHourlyCenterTime` being introduced on the render path only.

**Fix:** Reuse the same resolver for the probe.

### F4 — `STALE_REFRESH_SKIP` diagnostics are dead on every interaction path [MED]

`resolveRefreshContext:132-143` accepts `appLogDao: AppLogDao? = null` and forwards it to
`RefreshScheduler.refreshIfStale`. All eight callsites (`:241, 362, 415, 453, 549, 760, 849, 923`)
omit it, and `refreshIfStale` only emits the row when it is non-null
(`RefreshScheduler.kt:158-164`). The "why didn't this refresh?" breadcrumb therefore never fires
from a tap, and the parameter is dead weight.

**Fix:** `resolveRefreshContext` already holds `database`; pass `database.appLogDao()`
unconditionally and drop the parameter.

### F5 — `refreshDailyView` renders with two different `now` instants [LOW]

`now` is captured at `:1021` and drives the hourly window (`:1023-1024`),
`currentTempHourlyForecasts` (`:1044`) and current-temp resolution (`:1056`). Then `:1083` passes a
freshly computed `LocalDateTime.now()` into `DailyViewHandler.updateWidget`. The render is told a
different "now" than its data was selected for — an off-by-one-tick hazard across an hour or
midnight boundary.

**Fix:** Pass the existing `now`.

### F6 — Cleanups [LOW]

- **Duplicated timing/slow block** — `:1091-1096` and `:1156-1161` are identical modulo variables.
  Extract `logTiming(...)`.
- **Fully-qualified names despite imports** —
  `com.weatherwidget.widget.WidgetPushDispatcher.Origin` appears 8× (`:182, 905, 921, 981, 1114,
  1175, …`); `androidx.annotation.VisibleForTesting` at `:1102` (imported at `:8`);
  `android.util.Log.d` at `:1124` (imported at `:7`); `kotlinx.coroutines.delay` at `:891`;
  `com.weatherwidget.widget.CurrentTemperatureResolver` at `:1062`; `HourlyForecastHistoryDao`
  param type at `:672`.
- **`const val TOGGLE_REFRESH_STALE_MS` at `:106`** sits between function declarations; belongs with
  the constants at `:51-55`.
- **`cachedData!!` at `:1004` and `:1038`** — safe by construction via the `:998` null-check, but
  fragile. Resolving both values inside the branch removes the bangs.
- **Import order** — `ActiveLocationResolver` (`:26`) and `ActualsAggregator` (`:27`) break the
  alphabetical grouping.
- **Trailing whitespace** at `:1210`.
- **`String.format("%.1f", …)`** at `:1298-1299` has no `Locale`, yielding comma decimals under a
  comma-decimal locale — awkward inside `key=value` log parsing. Use `Locale.US`.

## Verified Non-Issues

Checked and sound, recorded so a later pass does not re-litigate them:

- `forgetWidget` is correctly wired from `WeatherWidgetProvider.onDeleted:536`, so
  `interactionMutexes` cannot leak.
- `WidgetInteractionCache`'s 2 s TTL makes `personalStationWeight`'s absence from `Key.of`
  immaterial.
- No reentrancy hazard: no lock-holding handler calls another lock-taking entry point
  (`kotlinx` `Mutex` is not reentrant, so this was worth confirming).
- `renderAllWidgetsFromCache` resolving location per widget is redundant but bounded — the 30 s
  `STALE_REFRESH_DEBOUNCE_MS` in `RefreshScheduler` collapses the repeated enqueues.

## Verification

1. `:app:compileDebugKotlin` + `:app:compileDebugUnitTestKotlin`.
2. `WidgetIntentRouterExecutionTest` (lock + batch-loop semantics) must stay green unchanged.
3. New pure test: resize debounce drops superseded events and does not hold the lock while sleeping.
4. New pure test: `runInteraction` persists `_RENDER_OK` on success and `_FAIL` on throw, and
   re-throws `CancellationException`.
5. `WeatherWidgetProviderDayTapSourceGapRoboTest` pins the `SET_VIEW_RENDER_OK` / `SET_VIEW_FAIL`
   message shape (`widget=<id>` + `mode=<NAME>`); the shared wrapper must preserve it.
6. Focused Robolectric router lane: `WidgetIntentRouterRobolectricTest`,
   `WidgetIntentRouterCrashSafetyRoboTest`, `DailyViewApiToggleIntegrationRoboTest`,
   `CloudCoverViewModeRoboTest`, `ZoomCycleRoboTest`, `NavigationPersistenceRoboTest`.

## Results

Implemented: 2026-07-28. All six findings fixed.

1. **F1** — Added `runInteraction(context, appWidgetId, tag, metadata) { }`, which takes the lock,
   emits `<tag>_RENDER_OK` / `<tag>_FAIL` and re-throws `CancellationException`. All seven public
   handlers now route through it (`NAV`, `CYCLE_ZOOM`, `TOGGLE_API`, `TOGGLE_VIEW`,
   `TOGGLE_PRECIP`, `SET_VIEW`, `RESIZE`), replacing six bespoke try/catch blocks.
   `handleSetView`'s message shape is preserved so the existing breadcrumb assertions still bind.
2. **F2** — `handleResize` now debounces via `awaitLatestResizeRequest` *before* taking the lock, and
   only the newest request per widget survives. Switched the request token from
   `SystemClock.elapsedRealtime()` to a monotonic `AtomicLong` mid-implementation: two resize
   broadcasts inside the same millisecond would have compared equal and both rendered.
   `forgetWidget` clears the entry.
3. **F3** — The `toggle_api` probe now calls `stateManager.resolveHourlyCenterTime(...)`, the same
   resolver `refreshGraphView` renders with.
4. **F4** — `resolveRefreshContext` passes `database.appLogDao()` unconditionally; the dead
   `appLogDao` parameter is gone, so `STALE_REFRESH_SKIP` now fires from interaction paths.
5. **F5** — `DailyViewHandler.updateWidget` receives the same `now` its data was selected with.
6. **F6** — Extracted `logTiming(...)` (removes the duplicated block); moved
   `TOGGLE_REFRESH_STALE_MS` up with the other constants; replaced all 8 fully-qualified
   `WidgetPushDispatcher.Origin` references and the 5 other stragglers with imports; removed both
   `cachedData!!` bangs by resolving the pair inside the branch; fixed import ordering; stripped
   trailing whitespace; added `Locale.US` to the two `String.format` calls.

File grew 1339 → 1389 lines: the wrapper and debounce helper carry substantial "why" comments, and
the net of the mechanical dedup (six try/catch blocks, one timing block) was smaller than the added
rationale.

### Verification

1. `:app:compileDebugKotlin` and `:app:compileDebugUnitTestKotlin` passed.
2. `WidgetIntentRouterExecutionTest` — 7 tests pass, including 3 new ones: newest-request-wins,
   per-widget independence, and a direct regression test proving a tap acquires the lock while a
   resize debounce is still sleeping.
3. `SourceNeedsRefreshTest` — 6 tests pass unchanged (F3 did not perturb the policy).
4. Robolectric router lane passed: `WidgetIntentRouterRobolectricTest`,
   `WidgetIntentRouterCrashSafetyRoboTest`, `DailyViewApiToggleIntegrationRoboTest`,
   `CloudCoverViewModeRoboTest`, `ZoomCycleRoboTest`, `NavigationPersistenceRoboTest`,
   `WeatherWidgetProviderDayTapSourceGapRoboTest`, `DailyCloudCoverSiteParityRoboTest`.
5. Full `:app:testDebugUnitTest` suite passed.

F1 demonstrated itself during the Robolectric run: `DailyViewApiToggleIntegrationRoboTest` emitted

```
E/TOGGLE_API_FAIL: widget=42 NullPointerException: Cannot read field "layoutId" because "widgetInfo" is null
```

That render failure was always happening in that environment; before this change it existed only as
a logcat line and left no `app_logs` row — exactly the blind spot F1 describes.

### On-device verification (emulator-5554, API 36)

Installed the debug APK on the running emulator (physical devices deliberately untouched) and drove
widget 52 by broadcast.

**F1 — every handler now leaves a breadcrumb.** All seven tags appeared, none of which existed
before:

```
TOGGLE_API_RENDER_OK    | widget=52
TOGGLE_VIEW_RENDER_OK   | widget=52
NAV_RENDER_OK           | widget=52 dir=LEFT      (and dir=RIGHT)
TOGGLE_PRECIP_RENDER_OK | widget=52
CYCLE_ZOOM_RENDER_OK    | widget=52
SET_VIEW_RENDER_OK      | widget=52 mode=TEMPERATURE offset=-2147483648
RESIZE_RENDER_OK        | widget=52
```

`SET_VIEW_RENDER_OK` retains the `widget=<id>` + `mode=<NAME>` shape the existing tests bind to.

**F3 — verified by direct observation.** Navigated widget 52 six steps into the past to pin an
anchor, then sent one `ACTION_TOGGLE_API`. It produced **two** `HOURLY_CENTER_TRACE` lines, both
`branch=anchor(fixed)`:

```
widget=52 offset=-6 zoom=NARROW back=2 fwd=2 includesNow=false hasAnchor=true branch=anchor(fixed)
widget=52 offset=-6 zoom=NARROW back=2 fwd=2 includesNow=false hasAnchor=true branch=anchor(fixed)
```

One is the render, one the staleness probe. Before the fix only the render emitted a trace and the
probe silently used the drifted `now.plusHours(-6)` — a different window.

**F4 — `STALE_REFRESH_SKIP` now fires from taps**, with per-path reasons that were previously
unreachable: `stale_on_toggle_api`, `stale_on_toggle_view`, `stale_on_daily_nav`,
`stale_on_toggle_precip`, `stale_on_cycle_zoom`, `stale_on_set_view`.

**F6 — `logTiming` works on every path**: `TOGGLE_API_TIMING`, `DAILY_NAV_TIMING`,
`CYCLE_ZOOM_TIMING`, `RESIZE_TIMING`, plus a `RESIZE_SLOW` row when one render hit 983 ms.

**F2 — reachable only by instrumentation.** `onAppWidgetOptionsChanged` needs an options Bundle
`am broadcast` cannot construct, and synthetic drags (`input swipe`, `input motionevent`) do not
grab the launcher's resize handle. Added `ResizeDebounceInstrumentedTest`, run via
`am instrument` directly against the emulator so Gradle never installs to the attached physical
phones. It confirms the real chain reaches the router, renders, and emits `RESIZE_RENDER_OK` with no
`RESIZE_FAIL`. Measuring delivery timing during that run is what produced the F2 correction above.

### Not done

- The debounce's *coalescing* branch is proven only by the JVM tests, which control arrival timing.
  It cannot be exercised through the platform broadcast queue (see the F2 correction).
- Not exercised on the two attached physical devices (Pixel 7 Pro, SM-F936U1) — emulator only. The
  foldable is the interesting case for F2, since fold/unfold may deliver option changes faster than
  the AOSP queue does here.

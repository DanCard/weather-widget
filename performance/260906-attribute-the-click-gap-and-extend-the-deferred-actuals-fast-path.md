# Attribute the click gap, then extend the deferred-actuals fast path to interactions

**Date:** 2026-09-06
**Status:** Done. Tier 1 measured (see Tier 1 Outcome); Tier 2 built, tested and verified on
device — see Tier 2 Outcome.

Tiers 1 and 2 of
[260906-click-latency-where-the-time-goes-next.md](260906-click-latency-where-the-time-goes-next.md),
which measured a click at **~1,224 ms mean end to end** while the paint it contains costs ~400 ms
warm, leaving **~800 ms unattributed**.

Tier 1 attributes that gap. Tier 2 makes the click *feel* fast regardless of where it went.

---

## Tier 1 — attribute the 800 ms

### Why it must come first

Two causes fit the evidence and they have **opposite fixes**:

| if the 800 ms is… | the fix is… |
|---|---|
| `WidgetRefreshContextResolver.resolve()` — a DB load | caching, projection, sharing the resolve across the fan-out |
| dispatcher scheduling or mutex wait | concurrency shape, `WidgetInteractionDispatcher.MAX_PARALLELISM` |

Guessing picks wrong half the time, and `MAX_PARALLELISM` was set to 2 only hours ago on a burst test
that showed `CLICK_WATCHDOG` at zero — changing it on instinct would be unfounded.

### The path a click takes, and what is timed

```
WidgetActionReceiver.onReceive          ← receivedAtElapsedMs (exists, SET_VIEW only)
  launchForWidget → launchAsync         ← UNTIMED: dispatcher queue wait
    WidgetIntentRouter.runInteraction
      WidgetInteractionCoordinator      ← UNTIMED: per-widget mutex wait
        WidgetIntentActionHandler.setView
          renderAfterTransition
            prepareContext              ← UNTIMED: contextResolver.resolve()
                                          (ActiveLocationResolver, getLatestForecastBySource, …)
            InteractionRenderDispatcher.render
              handlerStartMs            ← TEMP_PIPELINE_PERF starts HERE
```

Three untimed spans, and the timer that does exist starts after all of them. This is the session's
opening trap one layer out: `resolveMs=4 / totalMs=7819` read like a fast render for the same reason.

### Change

**Three independent log lines rather than one threaded timing object.** Threading a timing context
through `launchForWidget → runInteraction → setView → renderAfterTransition → prepareContext` would
touch every action signature for a diagnostic; three correlated lines carrying `widget=` and the
action tag are enough to subtract the spans, and each stands alone if the others are removed.

1. **`INTERACTION_QUEUE_PERF`** in `WidgetActionReceiver.launchForWidget` — elapsed from
   `receivedAtElapsedMs` to the coroutine body actually starting. That is dispatcher wait, isolated.
   (`launchForWidget` already uses `CoroutineStart.LAZY` then `job.start()`, so the seam exists.)
2. **`INTERACTION_LOCK_PERF`** in `WidgetInteractionCoordinator.withWidgetLock` — time spent waiting
   on the per-widget `Mutex`, logged only past a threshold.
3. **`INTERACTION_PREPARE_PERF`** in `WidgetIntentActionHandler.prepareContext` — `contextResolver.resolve()`
   split from `refreshRequester.requestIfStale()`, which is a separate suspect inside the same call.

**And item 2: give every action the end-to-end number.** `SET_VIEW_E2E_TIMING` is currently the only
true click-to-complete measurement in the app, so `TOGGLE_API`, `CYCLE_ZOOM`, `DAILY_NAV` and
`RESIZE` — the taps actually reported as slow — report nothing, and the 13 `REFRESH_SLOW` per hour
have no user-perceived figure attached. Move that block into `launchForWidget`, keyed on the intent's
action, so every widget action emits `INTERACTION_E2E` automatically and no future action can be
added without one.

Thresholds: log past a floor (~150 ms for the spans, all E2E rows kept) and **logcat for anything
high-frequency**, per the `OBS_RANGE_READ` precedent — a diagnostic's own `app_logs` write must never
land on the path it measures.

### Verification

- Fire each action on the Samsung; confirm every one emits `INTERACTION_E2E`.
- The three spans plus `TEMP_PIPELINE_PERF totalMs` should approximately reconstruct `INTERACTION_E2E`.
  **A large residual is itself the finding** — it means a fourth untimed span, which is exactly how
  each previous round of this thread progressed.

---

## Tier 2 — extend the deferred-actuals fast path to interactions

### The mechanism already exists

`TemperatureStateResolver` has `deferStartupGraphActuals = startupToken != null && useGraph`. When
set, `loadGraphHours` **skips the observation read entirely** (`val observations = if
(deferStartupGraphActuals) emptyList() else …`) and the graph paints without actuals; the full paint
follows. `WidgetStartupCoordinator` drives that sequence, tracing
`phase=startup_launch → startup_done`, and `TEMP_PIPELINE_PERF` already reports a `startupFastPath`
flag.

**So the two-phase paint is built, shipped and traced — it is simply gated on `startupToken != null`,
which is never true for an interaction.** Tier 2 is therefore not "build two-phase paint"; it is
"let the interaction path use the one that exists". Much lower risk than the brainstorm implied.

### Change

1. **Generalise the gate.** Replace `startupToken != null` with an explicit `deferActuals: Boolean`
   carried on `InteractionRenderDispatcher.Request`, defaulting false so nothing changes until a
   caller opts in. `startupToken` keeps setting it, so the startup path is untouched.
2. **Two-phase interaction render.** For the graph-mode actions where the wait is felt —
   `SET_VIEW`, `TOGGLE_API`, `CYCLE_ZOOM`, `GRAPH_NAV` — render and push once with `deferActuals =
   true` (skipping `obsQueryMs`, 103–138 ms warm and ~605 ms cold), then immediately render and push
   again with full data under the same per-widget lock.
3. **Item 4, optimistic toggle feedback**, falls out of the same change rather than needing its own:
   the API label and zoom text are resolved before the observation read, so phase 1 already carries
   them.

### Risks

- **`RemoteViews` visibility is sticky.** A phase-1 push that hides or shows a view leaves that state
  until something sets it back. Phase 1 must set the *same* view set as phase 2, differing only in
  data — which is precisely what the startup path already does, so following it exactly is the
  mitigation.
- **Two pushes per tap doubles launcher IPC.** Acceptable for an interaction; must not be extended to
  the worker's fan-out, where it would triple.
- **A blank first frame is worse than a slow one.** The startup path's own comment warns an empty
  hour list yields a blank graph. Phase 1 must draw the forecast curve (which needs no observations)
  and omit only the actual overlay — never paint an empty graph.
- **Cancellation between phases** would strand the widget on the actuals-free frame. Phase 2 must run
  under the same lock and the same job, so a cancelled interaction leaves no partial state visible
  longer than the next paint.

### Verification

- `INTERACTION_E2E` from Tier 1 is the measure of success, but **the phase-1 push time is the number
  that matters** — target well under 100 ms — since the complaint is feedback latency, not throughput.
- On device: toggle API and cycle zoom repeatedly and confirm no frame ever shows a graph without its
  forecast curve, and that the actual overlay always arrives.
- Screenshot phase 1 and phase 2 for one interaction.
- Existing tests around `startupFastPath` must still pass unchanged — the startup path is the control.

---

## Order

Tier 1 first and **measured before Tier 2 is written**: if the 800 ms turns out to be dispatcher or
mutex wait rather than the data load, Tier 2 still helps perceived latency but stops being the
biggest win, and the ordering of everything after it changes.


---

# Tier 1 Outcome

## First, a correction that invalidates an earlier test

**`adb shell am broadcast` has never reached this app.** `WidgetActionReceiver` is
`android:exported="false"` (AndroidManifest line 118), and `am broadcast` runs as the shell uid, so
every broadcast in this thread was *enqueued by ActivityManager and never delivered* —
`onReceive`'s unconditional `Log.d` never appeared once. Naming the component with `-n` does not
bypass it.

Consequences, stated plainly:

- **The burst test in
  [260906-scope-observation-read-by-api-and-bound-paint-concurrency.md](260906-scope-observation-read-by-api-and-bound-paint-concurrency.md)
  proved nothing.** "9 broadcasts, `CLICK_WATCHDOG` fired 0 times" was reported as evidence that the
  bounded dispatcher held up. `CLICK_WATCHDOG` was zero because no click ever happened.
  `WidgetInteractionDispatcher` remains untested under real burst load.
- Effects that appeared to follow those broadcasts — paints, source cycling, `OBS_RANGE_READ` rows —
  came from the periodic worker running concurrently, not from the broadcast.
- **The performance measurements themselves stand.** `SYNC_PERF`, `TEMP_PIPELINE_PERF`,
  `OBS_RANGE_READ`, `DAILY_RECOMPUTE_*` all timed real work the worker did, and the
  before/after improvements are real. What was wrong was *attributing that work to taps*.
- `SET_VIEW_E2E_TIMING`'s 1,224 ms mean also stands — those were genuine user taps over three days.

**The correct way to exercise a click is `adb shell input tap <x> <y>` on the home screen**, which
goes through the launcher's own PendingIntent. Verified: it produces
`D WidgetActionReceiver: onReceive action=…` where a broadcast produces silence.

## The measurement

Real taps on the Samsung, thresholds temporarily dropped to 0 so nothing could hide:

```
INTERACTION_E2E   phase=start queue=2ms
INTERACTION_LOCK_PERF     waited=0ms contended=false
INTERACTION_PREPARE_PERF  total=11-15ms resolve=9-10ms staleCheck=2-5ms
INTERACTION_E2E   phase=tail  handler=594-1371ms heartbeats=30-33ms
INTERACTION_E2E   phase=done  e2e=629-1581ms queue=2ms work=627-1579ms
```

| span | measured | verdict |
|---|---:|---|
| dispatcher queue | **2 ms** | negligible |
| per-widget mutex wait | **0 ms**, uncontended | negligible |
| `contextResolver.resolve` | **9–10 ms** | negligible |
| `requestIfStale` | 2–5 ms | negligible |
| `restartHeartbeats` (WorkManager) | 30–33 ms | small |
| **the handler — i.e. the render** | **594–1,371 ms** | **everything** |

**All four hypothesised culprits are wrong.** The plan's central question — data load or dispatcher —
had a third answer: neither. The click *is* the render.

`TEMP_PIPELINE_PERF` covers only part of that handler (144–923 ms of a 594–1,371 ms span), so render
work still sits outside its timer — the same pattern one level deeper. That residual is the next
thing to instrument, and the likely cause is that one interaction repaints more than one widget while
the pipeline timer measures a single paint.

The brainstorm's "~800 ms unattributed" was partly an artefact of comparing a three-day
`SET_VIEW_E2E_TIMING` mean (1,224 ms, including pre-fix taps) against paint numbers measured today,
after the fixes. Comparing eras, not spans.

## What this means for Tier 2

**It strengthens it.** If the click is the render, then painting a first frame that skips the
observation read is attacking the thing that actually costs. `deferStartupGraphActuals` skips
`obsQueryMs` outright — 71–461 ms in these very samples — and the rest of phase 1 is forecast data
already in hand.

It also removes an argument for touching `WidgetInteractionDispatcher.MAX_PARALLELISM`: with an
uncontended lock and a 2 ms queue, concurrency is not the constraint on a single tap. Whether it is
under a *real* burst is now an open question again, because the burst test has to be redone with
`input tap`.

## Instrumentation left in place

- `INTERACTION_E2E` (logcat) — every widget action, `phase=start|tail|done`, from
  `WidgetActionReceiver.launchForWidget`, so no future action can ship without a number.
- `INTERACTION_LOCK_PERF` (logcat, ≥50 ms) — mutex wait, in `WidgetInteractionCoordinator`.
- `INTERACTION_PREPARE_PERF` (logcat, ≥50 ms) — `resolve` split from `staleCheck`.

Thresholds are 50 ms against a measured normal of 0–15 ms, so a line appearing now means something
changed. All logcat, never `app_logs`: these fire on every tap, and a diagnostic's own write must not
land on the path it measures.

---

# Tier 2 Outcome

## What shipped

Exactly the plan's three changes, no more:

1. **`deferActuals: Boolean`** on `InteractionRenderDispatcher.Request` (default false) →
   `GraphRenderRequest` → `TemperatureViewHandler.updateWidget(deferGraphActuals)` →
   `TemperatureStateResolver.shouldDeferGraphActuals(startupToken, requested, useGraph)` — a pure
   generalisation of the old `startupToken != null && useGraph` gate. The startup path is untouched
   (its token still sets the flag; its `startupFastPath` rows and tests are the control).
2. **`GraphInteractionRenderer.paintPlan(deferActuals, viewMode, requestPartialPush)`** — a pure,
   tested decision returning the paints one render performs. Two phases only for an opted-in
   temperature render: phase 1 keeps the caller's delivery mode and skips the observation read;
   phase 2 always pushes partially (same view set, complete body — the sticky-visibility trap).
   Precip, cloud and daily paint once whatever the flag says: their handlers have no deferral to
   use, and `dailyRequest` drops the flag entirely.
3. **Opt-ins:** `SET_VIEW`, `TOGGLE_API`, `CYCLE_ZOOM` and `GRAPH_NAV` (nav left/right) pass
   `deferActuals = true`. `TOGGLE_VIEW`, `RESET_SOURCE`, `RESIZE` and the refresh paths do not.
   `INTERACTION_PHASE1` (logcat only, same rule as `INTERACTION_E2E`) times the first push of every
   two-phase render; `TEMP_PIPELINE_PERF` gains a `deferActuals` field.

`DeferredInteractionActualsTest` (8 tests, Short bucket) pins both pure decisions — startup-token
behaviour, text-mode never deferring, per-view-mode plan shape, delivery-mode preservation, and the
no-opt-in default. Full suite: **4,017 tests pass** (`:app` 2,139 · `:shared` 1,505 · `:desktop` 373).

## Verified on device (emulator, real `input tap`s through the launcher)

1. **Every opted-in action emits `INTERACTION_PHASE1`.** Warm process: `TOGGLE_API` **phase1=121 ms,
   e2e=502 ms**; `GRAPH_NAV` **phase1=221 ms, e2e=366 ms** — phase 1 lands near the <100 ms target on
   hardware far slower than the Samsung (the same taps' full paints cost 275–372 ms there). Cold
   process: `SET_VIEW` 692/1,108 ms, `CYCLE_ZOOM` 791/1,141 ms.
2. **Negative controls hold.** `TOGGLE_VIEW` (not opted in) and `TOGGLE_API` while in daily view
   emit no phase line and paint once — the flag reaches nothing that cannot use it.
3. **The observation read is really skipped.** `app_logs` shows the pairs:
   `deferActuals=true obsQueryMs=0 … hours=19` then, ~280 ms later,
   `deferActuals=false obsQueryMs=51`. `hours>0` in every phase-1 row — the forecast curve is drawn,
   never a blank graph. (Phase-1 `TEMP_PIPELINE_PERF` rows appear only when the paint exceeds the
   120 ms `logIfSlow` threshold — fast phase-1 paints are invisible to the DB log by design;
   `INTERACTION_PHASE1` is the per-tap number.)
4. **A 30 fps screenrecord caught the phase-1 frame itself.** For one `GRAPH_NAV` tap: frames show
   the forecast curve throughout; for ~1.1 s the overlay is reduced (pink pixels 5,225 → 1,874 —
   the residual is the history-fed overlay portion, which is not deferred), then phase 2 restores it
   (4,795 px). The frame timeline matches the two push timestamps. Evidence frames:
   `/tmp/phase1_frame_evidence.png`, `/tmp/phase2_frame_evidence.png`.
5. **The startup control still works:** `token=startup-… startupFastPath=true` rows keep appearing
   after the change, and their tests pass unchanged.

## Samsung numbers — pending, and why

An active Signal call (translucent `WebRtcCallActivity` holding window focus) absorbs `input tap`s
on that phone, so warm Samsung numbers could not be gathered this session; tapping during a call
risks hitting call controls. This also retroactively explains the session's "silent" missed taps.
The two taps that did land pre-call behaved correctly (`TOGGLE_VIEW` e2e 732 ms, no phase line).
`INTERACTION_PHASE1` now ships in every build, so the next tap on the Samsung produces the number.

## Notes for whoever continues this thread

1. Tap targets on the fold cover display (uiautomator-derived): header-left block is `TOGGLE_VIEW`,
   the top-right label block is `TOGGLE_API`, daily columns fire `ACTION_DAY_CLICK` (which routes to
   `SET_VIEW` — opted in), graph strips fire `CYCLE_ZOOM`, edge arrows `GRAPH_NAV`.
2. Phase-1's 121–221 ms emulator figures include queue + prepare + the render itself; on the Samsung
   the same shape should land well under 100 ms warm, since Tier 1 measured prepare at 9–10 ms and
   the skipped `obsQueryMs` at 71–461 ms there.

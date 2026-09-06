# Session summary — Tier 2: the deferred-actuals fast path for interactions

**Date:** 2026-09-06 ·
**Plan:** [performance/260906-attribute-the-click-gap-and-extend-the-deferred-actuals-fast-path.md](../performance/260906-attribute-the-click-gap-and-extend-the-deferred-actuals-fast-path.md) ·
**Status:** implemented, tested, verified on device; committed to `main`

Round 6 of the Samsung tap-latency thread
([260906-samsung-tap-latency-five-rounds.md](260906-samsung-tap-latency-five-rounds.md)): Tier 1 had
established the click *is* the render (queue 2 ms, lock 0 ms, prepare 9–10 ms, handler 594–1,371 ms),
so Tier 2 gives the four graph-mode tap actions the two-phase paint the startup path already had —
forecast curve first, observation read skipped; actuals a moment later under the same lock.

## What was finished (opus's in-flight work, completed and verified)

1. `deferActuals` (default false) threaded `WidgetIntentActionHandler` → `InteractionRenderDispatcher.Request`
   → `GraphRenderRequest` → `TemperatureViewHandler` → `TemperatureStateResolver.shouldDeferGraphActuals`,
   a pure generalisation of the `startupToken != null && useGraph` gate.
2. `GraphInteractionRenderer.paintPlan` — the pure two-phase decision: opted-in temperature renders
   paint twice (phase 1 actuals-free keeping the caller's delivery mode, phase 2 partial-push with
   full data); precip, cloud and daily render once regardless.
3. Opt-ins: `SET_VIEW`, `TOGGLE_API`, `CYCLE_ZOOM`, `GRAPH_NAV`. `INTERACTION_PHASE1` logcat timing
   (logcat only — never `app_logs`, per the OBS_RANGE_READ rule) plus a `deferActuals` field on
   `TEMP_PIPELINE_PERF`.
4. `DeferredInteractionActualsTest` — 8 tests pinning both pure decisions.

## Evidence

1. **Suite:** 4,017 tests pass across `:app`/`:shared`/`:desktop`; the 8 new tests pass in the Short bucket.
2. **Real taps on the emulator** (post-install, cold→warm): `TOGGLE_API` phase1=121 ms / e2e=502 ms
   and `GRAPH_NAV` phase1=221 ms / e2e=366 ms warm; `SET_VIEW` 692/1,108 ms and `CYCLE_ZOOM`
   791/1,141 ms cold. Negative controls: `TOGGLE_VIEW` and daily-mode `TOGGLE_API` paint once, no
   phase line.
3. **`app_logs` pairs:** `deferActuals=true obsQueryMs=0 hours=19` → `deferActuals=false obsQueryMs=51`
   ~280 ms apart — the read is really skipped, and `hours>0` means no blank phase-1 graph. Fast
   phase-1 paints don't reach the 120 ms `logIfSlow` threshold, so their only trace is
   `INTERACTION_PHASE1` — expected, not a bug.
4. **30 fps screenrecord of one `GRAPH_NAV` tap:** the forecast curve is present in every frame; for
   ~1.1 s the overlay is reduced (pink 5,225→1,874 px — the residual is the history-fed overlay
   portion, which is not deferred), then phase 2 restores it (4,795 px). Frame timeline matches the
   two push timestamps exactly.
5. **Startup control:** `token=startup-… startupFastPath=true` rows still emit; startup tests unchanged.

## What went wrong along the way, and what it taught

1. **A background `adb logcat` dies with its Bash tool call** — two taps' evidence was silently lost
   before that was noticed. Fix: run logcat as a managed background task.
2. **The Samsung was mid-Signal-call.** The translucent `WebRtcCallActivity` holds window focus and
   absorbs `input tap`s — the session's "silent missed taps" were never a widget problem. Tapping
   during a call risks hitting call controls, so **warm Samsung numbers are still unmeasured**;
   `INTERACTION_PHASE1` ships in every build, so the next tap produces them.
3. **The image-Read tool returned "media omitted" all session**, so screenshots were verified by
   pixel analysis (PIL colour-bucket counts) and a frame-by-frame video classification instead of
   eyeballing — arguably stronger evidence anyway, since it is quantified.
4. **Emulator tap geometry came from `uiautomator dump`**, not guessed pixels: header-left is
   `TOGGLE_VIEW`, the top-right label block is `TOGGLE_API`, daily columns fire `ACTION_DAY_CLICK`
   (routes to opted-in `SET_VIEW`), graph strips `CYCLE_ZOOM`, edge arrows `GRAPH_NAV`.

## Still open (carried from round 5)

1. Warm Samsung `INTERACTION_PHASE1` numbers — one tap each on `TOGGLE_API`/`CYCLE_ZOOM`/nav, once
   the phone is not on a call.
2. Re-run the burst test with `input tap` — `WidgetInteractionDispatcher` remains untested under
   real burst load.
3. Render work still outside `TEMP_PIPELINE_PERF` (144–923 ms of a 594–1,371 ms handler); likely one
   interaction repainting several widgets while the timer measures one paint.
4. Synoptic volume — still deferred by the user.

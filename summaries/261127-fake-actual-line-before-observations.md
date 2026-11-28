# Session summary: fake actual line drawn before observations load (2026-11-27)

Commit: `c9d201831` Stop painting the fake actual line before observations load (not pushed)

## User prompts

1. "Fold-4 : seems some double drawing of hourly actual temperature graph is appearing. First is a fake,
   then is the real. Can we stop drawing the fake line?"
2. "add logging if this isn't easy to figure out"
3. "I'm talking about actual temperature line, not forecsast"
4. Note from a side agent: the startup path still drew the fake line. User: "I don't see point of drawing
   fake temperature line at all."
5. "I tested on fold-4 , looks great, thanks. commit"
6. "Two step paint is o.k., but don't show fake data."
7. "write a session summary to summaries/ dir"

## Evidence

1. Fold 4 (SM-F936U1) logcat `TEMP_PIPELINE_PERF`: every graph tap painted twice, about 250 ms apart.
   The first paint had `deferActuals=true obsQueryMs=0 hours=19` and the second had
   `deferActuals=false obsQueryMs=50-90 hours=92`. The startup paint (`startupFastPath=true obsQueryMs=0`,
   16:50:16) was not followed by a full paint until 16:50:46.
2. Renderer traces for the first paint showed `ACTUAL_EXTREMA ... actualIndicesRange=null..null`, and its
   labels came from `SMOOTHED_FORECAST`. The second paint labelled `75.1` with `OBSERVED`.
3. `screenrecord --display-id 4630946474867211650` during ‹/› taps. Plain `screencap` fails because the
   Fold has two displays. Frame f095, at the first paint's timestamp, showed a smooth pink line over the
   past hours: the forecast shape, peaking at 74.9°. It drew no white dashed forecast there. Frame f102
   showed the real jagged NWS observed line, peaking at 75.1°. The user's "fake" was this first frame.
   The existing logs were enough, so no new logging was added.

## Root cause

`TemperatureGraphHoursLoader` skipped the observation and archive read when `deferGraphActuals` was set.
Two callers set it:
- the startup fast path (`startupToken`);
- since `7b78a43ed` (2026-09-06), taps: NAV, RECENTER_TODAY, CYCLE_ZOOM, SET_VIEW, and the
  toggle/comparison/observations source switches. Their first paint came from
  `GraphInteractionRenderer.paintPlan`.

With no actuals, that frame drew the past hours of the forecast where the actual line goes, styled as the
pink actual line.

## Change

1. Interactions paint once. Removed `deferActuals` from `InteractionRenderDispatcher.Request`,
   `GraphRenderRequest`, `WidgetIntentActionHandler` (and the `SwitchKind` field), `paintPlan`/`PaintPhase`,
   and the `INTERACTION_PHASE1` log.
2. Startup also reads observations. Removed `deferGraphActuals` from `TemperatureViewHandler`,
   `TemperatureStateResolver` (`shouldDeferGraphActuals` deleted) and `TemperatureGraphHoursLoader`
   (`ObsRead.DEFERRED` deleted).
3. Tests:
   - deleted `DeferredInteractionActualsTest`;
   - added a Robolectric integration test to `TemperatureFetchDotUpdateRoboTest`:
     `startup paint reads observations and graphs the actual line`. It runs through `TemperatureViewHandler`,
     `TemperatureStateResolver` and `TemperatureGraphHoursLoader`. It was confirmed to fail on the old code.
4. Docs: `arch/forecast-and-observation-cadence.md` now states that the hourly graph always paints with its
   observation read.

The desktop app is unaffected: its `deferActuals` defers backfills, not paints.

## Verification

1. `./scripts/staggered-tests.sh --install` passed: 7,390 JVM tests and 130 emulator tests. The build was
   installed on 2 devices.
2. Fold 4 after install:
   - the startup paint had `obsQueryMs=111 hours=96`;
   - each tap had one `TEMP_PIPELINE_PERF` line and no `INTERACTION_PHASE1`;
   - end-to-end tap times were 676/620 ms, against 609/872 ms before;
   - a re-recording showed one frame change per tap and no intermediate frame.
3. The user confirmed on the Fold 4: "looks great".

## Follow-up / open question

The user clarified that a two-step paint is acceptable as long as no frame shows fake data. One option
is to restore the fast first paint with no actual line (forecast only), and the observed line arrives in
step 2. That is still undecided. The recommendation is to keep the single paint: on the Fold the
observation read is about 55–110 ms, the old first paint saved about 170 ms, and an empty-actuals first
frame would still look like a redraw.

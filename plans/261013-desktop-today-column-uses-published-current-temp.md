# Desktop: today column and header show one current temperature

## Problem

The desktop daily view showed **73.4°** in the header and **~72°** (71.76) at the top of today's
actual bar, at the same moment (2026-10-13 14:15–14:22, NWS, Mountain View).

User requirement: the header and the today column use **the same math, at the same time, on the
same data**.

## Root cause

The two numbers come from different processes, computed at different times:

| | Header | Today column |
|---|---|---|
| Value | `current_status.displayTempF` | `forecast.resolved.currentTemp` |
| Computed by | The daemon (`PanelPublisher.refreshCurrentStatus`), re-resolved every `STATUS_TICK_MS` (2 min) from its own in-memory snapshot | The `ui` process, once per `loadCached()` call in `DesktopUiApplication` |
| Read at | `DesktopUiApplication.kt:796` (`resolvedCurrentTemp`) | `DesktopDailyForecastModel.kt:199`, which feeds `resolveTodayLineValues` (`solidHigh = currentTemp`) |

"Phase 3" (the comment above `DesktopUiApplication.kt:790`) made the daemon-published `current_status` the single value for the panel and
the popup header. The today column was never switched over, so it still uses the UI's own resolve
from whenever the snapshot last loaded.

Evidence from the DB:
- `current_status` NWS row: `displayTempF=73.39999`, `appliedDeltaF=null`, updated 14:18 and again
  at 14:22. That is exactly KSJC's 14:00 reading.
- The UI's last resolve (`CURR_TEMP_RESULT`, 14:15:47): `display=71.76 obs=71.50 delta=1.50`.

## Fix

Resolve the current temperature once and pass that value to both places.

1. `DesktopDailyForecastModel.build` takes a `currentTemp: Float?` parameter and uses it in place
   of `forecast.resolved.currentTemp`. No default value, so no caller can silently keep the old
   path.
2. `DesktopWidgetPopup` passes the `resolvedCurrentTemp` it already receives (the header's value)
   into `build`. That value is `publishedStatus?.displayTempF ?: forecast?.resolved?.currentTemp`,
   so startup and preview fall back exactly as the header does.
3. The UI already re-reads `current_status` on every `nowMs` tick, and that tick is phase-locked
   to the daemon's `STATUS_TICK_MS`. The column then recomposes with the header, so both show the
   same value at the same time.

Result: the panel, the popup header and the today column all show one value, from one resolve, on
one snapshot (the daemon's).

Out of scope:
- The unused `currentTemp` parameter on `TemperatureGraph`.
- Android.

## Open question (separate from this fix)

Is 73.4 itself right? The daemon's resolve returns a lone station with no forecast-trend
correction (`appliedDeltaF=null`). The UI's resolve on nominally the same DB rows returned the
blend plus a correction. Once this fix lands, every surface shows whatever the daemon computes, so
the daemon's answer matters more.

Diagnostic step:
- Add a `CURRENT_STATUS_PUBLISH` DEBUG log whenever the published value changes, recording
  display / obs / observedAt / appliedDelta / obs count / hourly count.
- Replay the daemon's next publish against `loadCached()` at the same `now`.

Report the findings before changing any blend math.

## Tests

- `DesktopDailyForecastModelTest`: build with `currentTemp` different from the snapshot's
  `resolved.currentTemp` (e.g. 73.4 vs 71.76). Assert that today's `solidHigh` / bar top equals
  the passed value. Assert that a null `currentTemp` falls back to the actual high, which is the
  existing `resolveTodayLineValues` behaviour.
- Integration (popup + model): render `DesktopWidgetPopup` in daily mode with a `resolvedCurrentTemp`
  that differs from `snapshot.resolved.currentTemp`. Assert the header text and today's model
  value agree. This needs the Compose test harness that `NowDotStationsPopupTest` already uses.
- Run `./gradlew :desktop:test`.
- Rebuild and restart with `scripts/buildStart-desktop.sh`. Confirm on screen that the header and
  the top of today's bar match.

## Verification

Implemented 2026-10-13.

- `DesktopDailyForecastModel.build(..., currentTemp: Float?)` is required. The test file routes its
  calls through a `buildModel` helper that passes the snapshot's own value, so existing cases keep
  their meaning.
- `WidgetPopup` computes `displayCurrentTemp = resolvedCurrentTemp ?: snapshot.resolved.currentTemp`
  once and hands it to both `WidgetHeader` and `build`.
- Test seam: the `TodayColumnSolidHigh` semantics property on the daily graph and the text-mode
  surface.
- New tests:
  - `DesktopDailyForecastModelTest` "today column uses the passed current temp, not the snapshot's"
  - `DesktopUiTest.headerAndTodayColumnShareTheCurrentTemperature`: published 73.4, then null, so
    the fallback is 72.
- Mutation check: restoring `snapshot.resolved.currentTemp` in the popup fails the integration test
  with "could not find any node that satisfies: (TodayColumnSolidHigh = '73.4')".
- `:desktop:testByDurationDesktop`: Short 459, Medium 39, Long 81 tests, 0 failures.
- On screen after `scripts/buildStart-desktop.sh` (14:28): header 73.0°, today column 73°.
- After the restart, `current_status` NWS = 73.01 with `appliedDeltaF=2.54`: a blend plus a
  correction, no longer the lone KSJC 73.4 with a null delta. The earlier 73.4 was specific to that
  daemon's in-memory snapshot. The open question above stays open; it needs a reproduction to
  diagnose.

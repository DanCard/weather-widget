# Last column's night rain label slides to the canvas edge

No plan file: single-purpose layout change, approved in conversation ("implement").

## User prompts

1. "Emulator: Far right: 60% night time label. What do you think of sliding that all the way right
   as much as possible?"
2. "implement"
3. "I ran: time scripts/buildStart-desktop.sh && time ./gradlew installDebug"
4. "Should there be an integration test for this? I feel I've requested this more than once."
5. "write session summary to summaries/ dir"

## Evidence

- Emulator screenshot: Wed (last column) "60%" sat right beside Tue's "85%" ("85% 60%").
- Logcat `nightRainLabel position`: `date=2026-11-25 text="60%" fit=NIGHT_CENTERED target=555.5`,
  which is Wed's column centre. Tue's "85%" ink ended at 540.5 and Wed's began at 545.5, so about 1.9 dp
  apart.
- Cause: both hosts pass the last column's right edge as `gapCenterX`. That point fails `fitsAt`, so
  `layoutNight` fell back to centring the label under the column's own low.

## What changed

Shared code, so Android and desktop behave the same.

- `DailyRainLabelLayout.layoutNight` has a new fallback placement, `NIGHT_AT_EDGE` (replaces
  `NIGHT_CENTERED`):
  - The label targets `gapCenterX`, clamped inside `RAIN_LABEL_EDGE_MARGIN_DP`.
  - The old target was the column centre.
  - `NightRainLabelPlacer` still scores every spot, so clearance from the low and icon holds. It can
    still slide left by up to `NIGHT_NUDGE_MAX_SHIFT_FRACTION` if the edge spot is blocked.
- Result on the emulator: `fit=NIGHT_AT_EDGE target=563.5`, label against the edge margin, gap to
  "85%" roughly tripled.
- `arch/ARCHITECTURE.md` (night rain labels paragraph) documents the last-column rule.

## Tests

- Unit: `DailyRainLabelLayoutTest` "the last column's night label slides right to the canvas edge
  margin, not centred under its low".
- Integration (Robolectric, real renderer + ink host + shared layout):
  `DailyForecastGraphRendererRoboTest.renderGraph_lastColumnNightRainLabelSlidesToTheCanvasEdge`.
  It checks three things:
  - placement is `NIGHT_AT_EDGE`;
  - the label's right edge sits at width − edge margin;
  - it is right of Wed's column centre and clear of Tue's label.
- Both tests fail on the old code: with only the production file reverted, the renderer put the
  label `NIGHT_CENTERED` at x=600.
- Fixture rename: `NightRainGridMapperTest` placement string updated to `NIGHT_AT_EDGE`.
- The integration test was added only after prompt 4. It should have shipped with the change.
  Saved as feedback memory `feedback_always_include_integration_test.md`.

## Unrelated fix found by the gate

- `OpenMeteoHttpFallbackIntegrationTest` (added 2026-11-17 in e6a60fff9) hardcoded `2026-11-17`
  fixture dates. On 2026-11-18 the snapshot excluded that past day, so the test failed every run with
  "forecast rows persisted".
- Fix: the fixture now uses today's date in `America/Los_Angeles`. It passed 2 of 2 reruns.

## Verification

- `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh` (exit 0):
  - 7146 JVM tests passed;
  - emulator-5554: 128 of 128 instrumented tests passed;
  - installDebug on 2 devices;
  - desktop distributable restarted.
- Emulator screenshot after install shows "60%" at the right edge, separated from "85%".
- The desktop rendering was not checked visually.
- The first gate run failed only on the Open-Meteo time-bomb above, before the emulator stage. You
  ran your own `buildStart-desktop.sh` and `installDebug` at the same time.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/DailyRainLabelLayout.kt`
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/DailyRainLabelLayoutTest.kt`
- `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/NightRainGridMapperTest.kt`
- `desktop/src/test/kotlin/com/weatherwidget/desktop/OpenMeteoHttpFallbackIntegrationTest.kt`
- `arch/ARCHITECTURE.md`

Not committed.

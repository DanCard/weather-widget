# Hourly Rain Actuals Graph + Staggered Test Reliability

## Summary

This session added observed hourly rainfall amounts to the hourly rain chance graph for past windows while preserving forecast precipitation amounts as a separate series. The graph now displays predicted rainfall with `Pred ...` labels and actual rainfall with `Act ...` labels.

The session also fixed reliability problems in `scripts/staggered-tests.sh` when two emulators are connected. Emulator tests now run sequentially after JVM tests, retry once on transient device/test-runner disconnects, and report incomplete UTP/device failures distinctly.

Final code changes were committed and pushed:

1. Commit: `addb625 Show rain actuals on hourly precip graph`
2. Branch: `main`
3. Remote: `origin/main`

## User Prompts

1. "Start in plan-first mode: inspect first, propose a short plan, and wait for approval before making changes. Do not edit files or run mutating commands until I confirm."
2. "In a session with opus: session-logs/260528-rain-actuals-pipeline-and-staggered-fix.md Added rainfail actuals history. I'd like to display this in history rain chance graph."
3. "I don't understand the plan. Let me restate the goal. There is an hourly rain chance graph. When displaying this graph for past days, I'd like to add actual rainfall amounts."
4. "Approved"
5. "When I run staggered tests, with two emulators it says failed. When I run scripts/emulator-tests.sh directory it passes. Can you fix? [included staggered-tests output]"
6. "For rain history, on hourly rain chance graph, I'm hoping to see both predicted rain fall amounts and actual rainfall amounts."
7. "Selected: Generic_Foldable_API36 Targeting (override): emulator-5556 APK install finished in 0s Running tests... ... 1 passed in 11s ✗ com.weatherwidget.widget.TemperatureGhostLineTest > hottestPoint_rendersHighEnoughToOverlapHeaderRegion ..."
8. Provided AGENTS.md instructions for `/home/dcar/projects/weather-widget`.
9. Repeated the failing `TemperatureGhostLineTest` output and log path.
10. "for staggered tests, the --install flag no longer seems to work"
11. "Oops, my bad, device I was concerned about is not connected."
12. "commit all and push"
13. "write a session log in markdown format to session-logs2/ dir"

## Rain Graph Implementation

1. Inspected the existing precipitation graph pipeline:
   - `PrecipitationGraphRenderer.PrecipHourData` already carried `precipAmountMm` for forecast rainfall amount.
   - `PrecipViewHandler.buildPrecipHourDataList` populated that field from `HourlyForecastEntity.precipAmountMm`.
   - Observation history was available through `ObservationDao.getObservationsInRange`.

2. Added actual rainfall data without overwriting forecast rainfall:
   - Added `actualPrecipAmountMm` to `PrecipHourData`.
   - Kept `precipAmountMm` as the predicted/forecast amount.
   - Populated `actualPrecipAmountMm` only for hours before `now`.

3. Loaded observed rainfall for visible graph windows:
   - Added `computePrecipGraphWindow` to share the graph window calculation.
   - Added `loadActualPrecipByHourForGraph`.
   - Queried observation history for the visible time range when it overlaps the past.
   - Bucketed observation rainfall by truncated local hour.

4. Source filtering:
   - For NWS, used real NWS observation rows and excluded `NWS_BLEND`.
   - For non-NWS sources, used source-specific `_MAIN` pseudo-actual rows.
   - Ignored null rainfall amounts and summed non-null hourly amounts.

5. Renderer changes:
   - Predicted rainfall labels use the existing rain amount paint and `Pred ` prefix.
   - Actual rainfall labels use a new orange actual-rain paint and `Act ` prefix.
   - Predicted and actual labels both participate in collision bounds.
   - Actual label placements are included in NOW/day-label collision avoidance.

6. Styling:
   - Added `COLOR_ACTUAL_RAIN_AMOUNT = "#FF9F0A"`.
   - Added `actualRainAmountPaint`.

## Rain Graph Tests

Updated and added tests covering:

1. Predicted and actual rainfall labels can both be placed by the renderer.
2. Actual rainfall buckets are source-filtered correctly.
3. NWS actual rainfall excludes `NWS_BLEND`.
4. Non-NWS actual rainfall uses only `_MAIN` rows.
5. Past hours receive actual rainfall amounts alongside forecast amounts.
6. Future hours do not receive actual rainfall amounts.
7. Test mocks for `TextMeasurer` include actual-rain measurement methods.

Focused JVM verification passed:

```bash
./gradlew testDebugUnitTest \
  --tests com.weatherwidget.widget.handlers.PrecipViewHandlerTest \
  --tests com.weatherwidget.widget.PrecipitationGraphRendererTest \
  --tests com.weatherwidget.widget.PrecipitationGraphRendererRobolectricTest \
  --tests com.weatherwidget.widget.PrecipitationGraphWatermarkTest
```

## Staggered Test Investigation

The initial staggered failure had two overlapping issues:

1. `scripts/staggered-tests.sh --install` was previously allowing installs to happen during emulator instrumentation, which can force-stop the app/test package and truncate emulator test output.
2. Staggered script status handling around piped `tee` output could make failures misleading.

Evidence collected:

1. Standalone `scripts/emulator-tests.sh` passed.
2. Staggered logs showed one emulator producing truncated or incomplete output.
3. UTP/Gradle reports sometimes turned a device/test-runner disconnect into a fake-looking failed test with an empty failure body.
4. A focused rerun of `TemperatureGhostLineTest` on `emulator-5556` passed both tests, proving the reported failure was not deterministic renderer behavior.

## Staggered Test Fixes

1. `scripts/staggered-tests.sh` now waits for unit tests to finish before starting emulator tests.
2. With multiple connected emulators, staggered tests now run emulator tests sequentially per emulator.
3. The per-emulator command is targeted with `EMULATOR_TESTS_TARGET_SERIAL`.
4. The script captures child exit status through `PIPESTATUS[0]`.
5. On transient device/test-runner disconnect output, staggered tests retry that emulator once.
6. `scripts/emulator-tests.sh` now labels incomplete device/UTP runs as:

```text
Device/test runner disconnected before completing
```

instead of reporting them as ordinary assertion failures.

7. Deferred `installDebug` still runs at the end when `--install` is passed and both test phases pass.

## Staggered Test Verification

Ran:

```bash
scripts/staggered-tests.sh --install
```

Final verified output:

1. Unit tests: 1300 passed.
2. `emulator-5554`: 56/56 instrumented tests passed.
3. `emulator-5556`: 56/56 instrumented tests passed.
4. Deferred `installDebug`: installed on 3 devices.
5. Final status: both unit tests and emulator tests passed.

Also verified the user concern about `--install`: the device that seemed missing was not connected. The latest install log showed Gradle installing `app-debug.apk` on `emulator-5556`, `emulator-5554`, and `Pixel 7 Pro`.

## Commit And Push

Ran pre-commit checks:

```bash
git diff --check
git status --short
git diff --stat
```

Committed all pending changes, including the earlier session log artifact:

```text
addb625 Show rain actuals on hourly precip graph
```

Pushed successfully:

```text
3290b83..addb625  main -> main
```

Final repository state after push was clean and `main` was aligned with `origin/main`.

## Files Changed In Commit

1. `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`
2. `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphStyle.kt`
3. `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`
4. `app/src/test/java/com/weatherwidget/widget/PrecipitationGraphRendererTest.kt`
5. `app/src/test/java/com/weatherwidget/widget/PrecipitationGraphWatermarkTest.kt`
6. `app/src/test/java/com/weatherwidget/widget/handlers/PrecipViewHandlerTest.kt`
7. `scripts/emulator-tests.sh`
8. `scripts/staggered-tests.sh`
9. `session-logs/260528-rain-actuals-pipeline-and-staggered-fix.md`

## New Artifact From This Request

This file was created after the commit and push:

```text
session-logs2/260528-hourly-rain-actuals-and-staggered-tests.md
```

# Daily forecast header: current temperature 20% larger

**Date:** 2026-07-31
**Plan:** none (direct request: "Android: daily forecast view: header row: Make the current
temperature font size 20% bigger.")

## Outcome

The daily forecast view's header current temperature is now 20% larger (18dp → 21.6dp). All other
views (hourly/temperature, precip, cloud) keep 18dp. Unit tests green, verified on the emulator.
Nothing committed — changes are in the working tree.

## Why this touched six files instead of one

Two structural facts drove the scope:

1. **The daily view's header temp is not a `TextView`.** `DailyGraphRenderer.kt:267` sets
   `R.id.current_temp` to `INVISIBLE` and the header is painted straight into the graph bitmap by
   `DailyForecastHeaderRenderer`. The other views keep using the RemoteViews `current_temp`
   (`HeaderRemoteViewsBinder.bindCurrentTemp`). So a daily-only bump lands on the canvas paint, not
   on layout XML — and the shared `HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP` could not simply be
   raised without dragging every other view along with it.

2. **The font size is simultaneously a drawing input and a layout input.** `HeaderWidthChecker` uses
   the current-temp text width to decide:
   - the **disclosure level** (whether the weather icon / delta / precip get dropped as width tightens),
   - the **Samsung 1.35× wide-header scale** (occupancy < 50% at ≥450dp — see
     `samsung_header_scale_450dp_cliff`),
   - **header date placement** (center vs right vs suppressed), via `DailyHeaderBinder`.

   Changing only the paint would have left the fit math measuring a 20%-narrower string than what
   gets drawn: invisible on wide widgets, a collision with the API label on narrow ones.

## Changes

| File | Change |
|---|---|
| `HeaderConstants.kt:11` | new `DAILY_CURRENT_TEMP_TEXT_SIZE_DP = CURRENT_TEMP_TEXT_SIZE_DP * 1.2f` |
| `DailyForecastHeaderRenderer.kt:246` | canvas `tempPaint` uses the new constant |
| `DailyHeaderResolver.kt` | passes it to `resolveHeaderDisclosure`, `computeHeaderScale`, and `bindCurrentTemp` (the last covers daily **text** mode, which does use the RemoteViews TextView) |
| `DailyHeaderBinder.kt` | date/precip placement math + `currentTempTextSizePx` use the daily size |
| `HeaderWidthChecker.kt` | added `currentTempSizeDp: Float = HeaderConstants.CURRENT_TEMP_TEXT_SIZE_DP` to `currentTempTextWidthPx`, `resolveLeftClusterRightPx`, `resolveHeaderDisclosure`, `computeHeaderScale` |
| `DailyViewHeaderDatePlacementTest.kt:207` | expectation updated to the daily constant |

The default-parameter approach keeps every non-daily caller byte-identical while making the daily
divergence explicit at the call site — cheaper and safer than branching on view type inside the
checker.

**Not touched, deliberately:** `TemperatureViewHandler`'s two partial current-temp pushes
(`updateHeaderCurrentTemp`, `scheduleCurrentTempRefinement`) still use the shared 18dp. Both are
private to that handler and belong to the hourly view, so the daily view never sees them.

## Verification

- `./gradlew :app:testDebugUnitTest` — full suite BUILD SUCCESSFUL (Header*, Daily* run explicitly first).
- `./gradlew installDebug` on Pixel 7 Pro / SM-F936U1 / emulator; visual check on the emulator:
  the same `75.5°` reading measures ~130px wide in the hourly view (unchanged) and ~160px in the
  daily view — the expected 1.2×. Centered date "Fri 31", the `-5.8` delta, and the `NWS` label all
  still lay out without collision.
- The Pixel could not be screenshotted at the home screen (locked behind fingerprint).

## Open item

The desktop app renders its own header and does **not** share `HeaderConstants` (it lives in `:app`,
not `:shared`), so Android daily and desktop daily header temps now differ by 20%. Left as-is — the
request was scoped to Android. Same for applying the bump to the hourly/precip/cloud views.

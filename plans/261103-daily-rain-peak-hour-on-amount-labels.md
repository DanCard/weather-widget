# Daily view: show peak rain hour when rainfall amount is shown and at 100%

Date: 2026-11-03. Status: implemented.

## Goal

In daily forecast view, when showing the expected amount of rainfall during the day (or 100% chance), show when the peak chance occurs during the day (e.g. "11am"), rendered on top of the rainfall amount (or chance).

User request:
> "daily forecast view: When we are showing the expected amount of rain fall during the day, in a forecast, lets show when peak chance occurs during the day.  For example if it is 100% chance peak forecast at 11 am, then on top or below of rainfall amount lets show 11 am.  We already do this when rain chance is below 95%."

## Root cause & current behavior

1. In `DailyRainLabels.kt` (`buildDailyRainPeakLabel`):
   - `if (dailyRainLabelText == null || !dailyRainLabelText.endsWith("%")) return null` explicitly rejected any label that was formatted as an amount (`.25in`, `6mm`, etc.).
   - `if (probability <= PEAK_HOUR_MIN_EXCLUSIVE_PERCENT || probability >= 100) return null` explicitly rejected probabilities $\ge 100\%$, inherited from the original Oct 18 prompt ("when > 30% and < 100%").
2. When the daily forecast displays expected rainfall amount (today when day-prob $\ge 95\%$ and amount known; future days when day-prob $\ge 99\%$ and amount known), `dailyRainLabelText` is an amount string and does not end in `"%"`.
3. Consequently, whenever rainfall amount was shown, or whenever peak chance reached $100\%$, the peak hour label was suppressed.

## Architecture & rendering

The shared layout engine (`DailyRainLabelLayout.layoutDay`) already supports placing a `Kind.PEAK` label above a `Kind.DAY` label (`RAIN_PEAK_GAP_DP = -2dp`, `RAIN_PEAK_FONT_SCALE = 0.75x`), clamped to the ceiling so it never overflows.
Both Android (`DailyForecastRainLabelRenderer.kt`) and Desktop (`DailyForecastGraph.kt`) draw `labels.peak` on top of `labels.rain` in the same blue text paint.
No layout or rendering changes are required on Android or Desktop; only the shared peak label generation rules in `:shared` need adjustment.

## Plan

1. **`:shared` (`DailyRainLabels.kt`)**:
   - In `buildDailyRainPeakLabel`:
     - Change `if (dailyRainLabelText == null || !dailyRainLabelText.endsWith("%")) return null` to `if (dailyRainLabelText == null) return null`.
     - Change `if (probability <= PEAK_HOUR_MIN_EXCLUSIVE_PERCENT || probability >= 100) return null` to `if (probability <= PEAK_HOUR_MIN_EXCLUSIVE_PERCENT) return null`.
     - Update KDoc to reflect that peak hour appears for amount labels as well as percent labels, including at $100\%$ probability.
2. **Unit tests (`:shared`)**:
   - Update `DailyRainPeakLabelTest`:
     - Replace `an amount label or 100 percent shows none` with tests verifying that amount labels (`.25in`, `6mm`, etc.) and $100\%$ probability correctly return the peak hour string (e.g. `"11am"`, `"1pm"`).
     - Test future days and today before/during/after the peak hour.
3. **Renderer tests (`:app`)**:
   - In `DailyForecastGraphRendererRoboTest`:
     - Add test verifying peak hour renders above an amount rain label.
4. **Verification**:
   - Run `./scripts/unit-tests.sh` to ensure all JVM/Robolectric tests pass across `:shared`, `:desktop`, and `:app`.

# Plan: Shrink the hourly "from forecast" caption by 30%

## Goal

On the hourly temperature graph, `-0.2 from forecast` draws the caption at the same size as the
value. The caption should be 30% smaller while the value keeps its current size; both stay on one
baseline and the placement box stays honest.

## Evidence (Pixel 7 Pro, `2A191FDH300PPW`)

1. `widget_state_prefs.xml`: `widget_view_mode_86=TEMPERATURE`, `widget_zoom_level_86=WIDE`,
   `widget_display_source_86=NWS`, `widget_current_temp_delta_86_NWS=-0.23420715` → `-0.2`.
2. The full suffix `from forecast` (not the daily overlay's `fcst`) means the hourly graph label.
3. Root cause: Android drew `ForecastDeltaLabel.format(...)` as one run with
   `stalenessTextPaint` (`STALENESS_LABEL_SIZE_DP=18`); desktop drew one run at `11.25sp`. Nothing
   split value from caption.

## Changes

1. `:shared` `ForecastDeltaLabel.kt`
   - `SUFFIX_FONT_SCALE = 0.7f` (single source of truth for both platforms).
   - `Segments(value, suffix)` + `segments(delta, useCelsius, suffix)` shared splitter.
2. `:app` `TemperatureGraphAnnotationRenderer.placeForecastDeltaLabel`
   - Value on the existing staleness paint; caption on a copy at `textSize * SUFFIX_FONT_SCALE`.
   - Placement metrics use the combined ink width with the value's ascent/descent, so the
     empty-space finder and the recorded obstacle box match what is drawn.
   - Two draws on one baseline: value `Align.RIGHT`, caption `Align.LEFT` at the split x, both
     recolored to `placement.colorArgb`.
   - Added `ForecastDeltaDebug` (`onForecastDeltaPlaced`) reporting reason, both run texts/sizes,
     and the box, with a precise `zero_delta` gate.
3. `:desktop` `TemperatureGraph.kt`
   - `buildDeltaLabelAnnotatedString(value, suffix, scale)`: two `SpanStyle` runs, caption at
     `DELTA_CAPTION_SP = 11.25sp * SUFFIX_FONT_SCALE` (7.875sp), one baseline.

## Tests

1. `shared/ForecastDeltaLabelTest`: pins `SUFFIX_FONT_SCALE == 0.7f` and `segments()`.
2. `app/TemperatureGraphRendererForecastDeltaTest`: positive cases now expect two draws
   (`+2.3`, ` from forecast`); negative gates unchanged.
3. `app/ForecastDeltaLabelChineseIntegrationRoboTest` (zh-rCN, Localization bucket): full
   `TemperatureGraphRenderer` path asserts `-0.2 较预报`, caption size == value size × 0.7, and
   reserved box width == combined run widths.
4. `desktop/ForecastDeltaCaptionScaleTest`: the drawn `AnnotatedString` spans are value then
   caption, with the 30% ratio, scale-invariant.
5. `AGENTS.md`: integration test is defined by connecting 2+ modules/components, not by scaffolding
   (Robolectric, instrumented, or plain JVM).

## Verification

1. `:shared:test`, `:desktop:test`, `:app:testByDurationDebugUnitTest` all pass.
2. On-device render log (`ForecastDeltaDiag`) reports `suffixSizePx == valueSizePx ×
   SUFFIX_FONT_SCALE` — first captured at 0.8 (`26.65856 → 21.326849`), same path now applies 0.7.
3. Screenshot pixel measurement (`/tmp/wwshots/label_crop.png`): caption glyph ink is ~0.7–0.8×
   the value's, visibly smaller on screen.
4. Desktop distributable rebuilt and the running tray app restarted on the new binary.

# 260909 — Hourly "from forecast" caption drawn smaller than the value

**Date:** 2026-09-09 · **Plan:** `plans/260909-forecast-delta-caption-30-percent.md` ·
**Status:** implemented, tested, verified on the Pixel 7 Pro; **not committed**

## User prompts

> On pixel 7 pro: it says : "-0.2 from forecast". I'd like to reduct the font size of "from
> forecast" by 20%, by leave the "-0.2" the current size. Feel free to take a screenshot and or
> look at logs.

> Add an integration test in chinese for this. proceed

> Consider updating agents.md : An integration test can be any test that connects two or more
> modules, using roboelectric , instrumented test, or no scaffolding.

> I changed it from 0.8 to 0.7. Write above "summary of the work" to summaries/ dir

## Evidence and root cause

1. **Which label.** Pixel 7 Pro (`2A191FDH300PPW`) `widget_state_prefs.xml`:
   `widget_view_mode_86=TEMPERATURE`, `widget_zoom_level_86=WIDE`, `widget_display_source_86=NWS`,
   `widget_current_temp_delta_86_NWS=-0.23420715` → formats to `-0.2`. The full suffix
   `from forecast` (not the daily overlay's `fcst`) identifies the hourly graph label; the daily
   Today overlay uses a separate `fcst` caption and was not involved.
2. **No split existed.** Android drew `ForecastDeltaLabel.format(...)` as a single run with
   `stalenessTextPaint` (`STALENESS_LABEL_SIZE_DP = 18dp`); desktop drew a single run at `11.25sp`.
   Nothing in the pipeline distinguished the number from the caption, so the caption inherited the
   number's size by construction.
3. **Placement coupling.** The empty-space finder reserves a box from the measured metrics, so the
   split also had to report the *combined* ink width or the reserved box would no longer match what
   was drawn.

## Change

**`:shared` — one source of truth (`shared/.../graph/ForecastDeltaLabel.kt`)**

1. `SUFFIX_FONT_SCALE` — caption size relative to the value; **0.7f** (was 0.8f).
2. `Segments(value, suffix)` + `segments(delta, useCelsius, suffix = SUFFIX)` — shared splitter.
3. KDoc updated: two runs on one baseline, caption smaller than the number.

**`:app` — mixed-size draw (`TemperatureGraphAnnotationRenderer.placeForecastDeltaLabel`)**

1. Value stays on the cached `stalenessTextPaint`; caption uses a copy at
   `basePaint.textSize * SUFFIX_FONT_SCALE` (shadow/typeface carry over).
2. `ForecastDeltaLabel.place` now receives the **combined** ink width with the value's ascent/
   descent, so the reserved box matches the drawn ink (height unchanged; caption fits inside).
3. Two draws on one baseline: value `Paint.Align.RIGHT`, caption `Paint.Align.LEFT` at the split x
   (`centerX - combined/2 + valueWidth`), both recolored to `placement.colorArgb`.
4. Added `ForecastDeltaDebug` + `onForecastDeltaPlaced` reporting reason, both run texts/sizes, and
   the box; added a precise `zero_delta` gate so the diagnostic distinguishes it from
   `no_empty_band`.

**`:desktop` — mixed-size draw (`desktop/.../TemperatureGraph.kt`)**

1. `DELTA_LABEL_SP = 11.25f`, `DELTA_CAPTION_SP = DELTA_LABEL_SP * SUFFIX_FONT_SCALE`.
2. `buildDeltaLabelAnnotatedString(value, suffix, scale)` builds the two `SpanStyle` runs on one
   baseline; the graph measures/draws that `AnnotatedString` instead of a plain string.

**`AGENTS.md` — integration-test definition**

> An integration test is defined by what it connects, not by its scaffolding: any test that
> connects two or more modules/components is an integration test — whether it uses Robolectric, an
> instrumented (`androidTest`) test, or no scaffolding at all (plain JVM).

## Tests

1. `shared/ForecastDeltaLabelTest` — pins `SUFFIX_FONT_SCALE` and `segments()` (value/suffix split,
   localized suffix passthrough, Celsius scale-only).
2. `app/TemperatureGraphRendererForecastDeltaTest` — positive cases now expect two draws
   (`+2.3`, ` from forecast`); the null/zero/3-day/fetch-dot-offscreen gates unchanged.
3. `app/ForecastDeltaLabelChineseIntegrationRoboTest` (**new**, `zh-rCN`, `@Category(Localization)`)
   — full `TemperatureGraphRenderer` path asserts `-0.2 较预报`, caption size == value size ×
   `SUFFIX_FONT_SCALE`, and reserved box width == combined run widths.
4. `desktop/ForecastDeltaCaptionScaleTest` (**new**) — the drawn `AnnotatedString` spans are
   value→caption with the correct ratio, scale-invariant.

## Verification

1. `:shared:test`, `:desktop:test`, `:app:testByDurationDebugUnitTest` all pass (at 0.7f). The only
   red in a full `:shared:test` run is the network-dependent `AviationWeatherLivenessTest`
   (real `aviationweather.gov`), unrelated to this change.
2. On-device render log (Pixel 7 Pro), captured via a screen-on `USER_PRESENT` re-render:
   `ForecastDeltaDiag: reason=drawn value=-0.1 suffix= from forecast valueSizePx=26.65856
   suffixSizePx=18.660992` — the renderer applied 0.7 exactly (26.65856 × 0.7 = 18.660992). The
   same path was first captured at 0.8 (`26.65856 → 21.326849`).
3. Screenshot pixel measurement at 0.8 (`/tmp/wwshots/label_crop.png`): value digit `0` ink **37 px**;
   caption glyphs **21–29 px** (≈0.78–0.8) — the caption is visibly smaller on screen.
4. Desktop distributable rebuilt and the running tray app restarted on the new binary (PID 2059855).

## Scale tuning

The user tuned `SUFFIX_FONT_SCALE` from **0.8f** to **0.7f** after the implementation, i.e. the
caption is 30% smaller rather than 20%. The mechanism is unchanged (both platforms read the shared
constant); the constant pin, comments/KDoc, test names, and plan doc were updated to match, and the
ratio-based tests/verification hold proportionally.

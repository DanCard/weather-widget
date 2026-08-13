# Dominant-station label: larger temperature, drop diagnostics, paint rename

**Date:** 2026-08-12 · **Plan:** [plans/260812-dominant-station-mixed-font-size.md](../plans/260812-dominant-station-mixed-font-size.md)

The hourly-graph dominant-station annotation (`knuq 66.2° @ 8:10 pm`) now renders the temperature
segment larger than the station id and reading time, on both Android and desktop. In the same change
set the small annotation text got a size bump, both platforms gained a `DominantStationDiag` log line
for the label's drop reason, and the ambiguous fetch-dot `VALUE_LABEL_SIZE_DP` / `valueTextPaint`
names were renamed.

---

## What changed

### Mixed-size dominant-station label
- **Shared** `DominantStationLabel`: new `Part` (`STATION`/`TEMPERATURE`/`TIME`), `Segment`, and
  `LabelText` (`fullText` + `segments`), plus `formatLabelText(...)` overloads. `format(...)` now
  delegates to `formatLabelText(...)?.fullText`, so existing callers and tests are unchanged.
- **Android**: new `DOMINANT_TEMP_LABEL_SIZE_DP = 25f` and a `dominantTempTextPaint` in `PaintSet`.
  `placeDominantStationLabel` measures each segment, sizes the reserved box from the temperature
  paint's ascent/descent, and draws station/time at `STALENESS_LABEL_SIZE_DP` and the temperature at
  25f on a shared baseline.
- **Desktop**: builds an `AnnotatedString` with a larger `SpanStyle` on the temperature span
  (`TEMP_VALUE_LABEL_SP`); station/time inherit the small annotation size and Compose lays the spans
  out on a shared baseline.

### Annotation font sizing
- Android `STALENESS_LABEL_SIZE_DP`: 12f → 18f; desktop inline 9sp → 11.25sp. These drive the age,
  forecast-delta, and dominant-station annotations.

### DominantStationDiag logging
- Desktop `TemperatureGraph.kt` and Android (`TemperatureStateResolver` upstream gate +
  `TemperatureGraphAnnotationRenderer` placement gate) now log why the label is dropped
  (`no_contribution | synthetic | format_null | span_too_wide | no_empty_band | drawn`), deduped once
  per change. This diagnosed a "missing on desktop" report as `reason=synthetic` on the Silurian
  source — correct suppression, not a bug.

### Rename
- `VALUE_LABEL_SIZE_DP` → `FETCH_DOT_VALUE_LABEL_SIZE_DP`, and `valueTextPaint` →
  `fetchDotValueTextPaint` (the fetch-dot observed-value label), removing the ambiguous "value label"
  name.

## Verified
- `:shared:test` `DominantStationLabelTest` (incl. new `formatLabelText` segmentation tests) pass.
- `:desktop:test` temperature-graph smoke test (renders the NWS dominant label) passes.
- `:app` Robolectric suites (fetch dot, staleness, label placement) pass.
- `:app:compileDebugKotlin` and `:desktop:compileKotlin` clean.

## Notes
- Desktop's temperature span uses `TEMP_VALUE_LABEL_SP` (15.4sp) rather than a literal "25": desktop
  `sp` and Android `dp` are not 1:1, and this keeps it at the platform's graph temp-label tier.
- The Android emulator screenshot was not visually verified — this session cannot receive images.

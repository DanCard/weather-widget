# Dominant-station label: mixed font sizes (temperature enlarged)

Date: 2026-08-12

## Goal

In the hourly-graph dominant-station label (`knuq 66.2° @ 8:10 pm`), render only the
**temperature** segment at a larger size (~25 dp Android / desktop temp-label size), while the
station id and the `@ time` clause stay at the small annotation size.

## Approach

- **Shared `DominantStationLabel`**: add `Part` (`STATION`/`TEMPERATURE`/`TIME`), `Segment`, and
  `LabelText` (`fullText` + `segments`). Add `formatLabelText(...)` overloads; keep `format(...)` as
  a thin wrapper returning `LabelText.fullText` (existing callers/tests unchanged). Segmentation:
  `knuq ` + `66.2°` + ` @ 8:10 pm`.
- **Placement unchanged**: `GraphEmptySpaceFinder`/`DominantStationLabel.place` keep taking a single
  `Metrics`. Each renderer supplies the combined width (sum of segment widths) and the largest-font
  ascent/descent (the temperature segment), so the reserved box encloses all segments on one baseline.
- **Android**: new `DOMINANT_TEMP_LABEL_SIZE_DP = 25f` and a `dominantTempTextPaint` in `PaintSet`.
  `placeDominantStationLabel` accepts `LabelText?`, measures per-segment widths, then draws each
  segment left-to-right at the shared baseline (temp paint for the temperature, staleness paint
  otherwise).
- **Desktop**: build an `AnnotatedString` with a `SpanStyle(fontSize = TEMP_VALUE_LABEL_SP * scale)`
  on the temperature span; measure/draw once. Compose lays the spans out on a common baseline.

## Files

- `shared/.../graph/DominantStationLabel.kt`
- `app/.../widget/TemperatureGraphStyle.kt`, `TemperatureGraphModels.kt`,
  `TemperatureGraphAnnotationRenderer.kt`, `TemperatureGraphRenderer.kt`
- `app/.../widget/handlers/TemperatureStateResolver.kt`
- `desktop/.../TemperatureGraph.kt`
- tests: `shared/.../graph/DominantStationLabelTest.kt` (segment shape)

## Verification

- `:shared:test`, `:app:compileDebugKotlin` + relevant Robolectric suites, `:desktop:compileKotlin`
  + desktop smoke test.

# Session summary — dominant-station label 20% font fallback

**Date:** 2026-09-09 · **Status:** implemented, tested, verified live on Pixel 7 Pro; committed with this summary

## User prompts

> Why is dominate station not being reported on pixel 7 pro? If it is because there is no space,
> then lets implement a fallback where font size shrinks by 20% if it doesn't fit.

> write above recap to summaries/ dir , then commit

## Evidence and root cause

On the Pixel 7 Pro (`2A191FDH300PPW`, Google Pixel 7 Pro, SDK 37) the label's **text was being
computed**. `app_logs` `DOMINANT_STATION` rows read:

```
reason=text_ok station=KNUQ rawTemp=87.8 text=knuq 87.8° @ 12:55 pm
```

The label was dropped by the **renderer**, not the resolver. With `log.tag.TempGraphRenderer VERBOSE`:

```
# WIDE view (4a–8p)     DominantStationDiag: reason=no_empty_band spanH=18 text=knuq 87.8° @ 1:15 pm
# NARROW view (11a–3p)  DominantStationDiag: reason=drawn         spanH=4  text=knuq 87.8° @ 12:55 pm
```

So the cause was exactly "no space": `GraphEmptySpaceFinder` could not fit the full-size label
(measured 244 px wide on a 517 px plot) among the wide view's 11–15 drawn labels/curves. The
pre-fix screenshot confirmed the label absent in the wide view and present in the narrow one.

## Change

1. **Shared `DominantStationLabel`** — new `placeWithFontFallback(...)` tries
   `FALLBACK_FONT_SCALES = [1.0f, 0.8f]` in order and returns the first that fits as a
   `ScaledPlacement(placement, fontScale)`. The platform supplies `metricsForScale` (re-measured
   text for that scale) and draws with the winning scale.
2. **Android** `TemperatureGraphAnnotationRenderer` — re-measures each segment with paints scaled by
   the candidate factor (`scaledPaint`) and draws at the winning scale; `DominantStationDiag` now
   logs `fontScale=`.
3. **Desktop** `TemperatureGraph` — same fallback via `measureDominant(fontScale)` (re-measures the
   annotated string), keeping dual-platform parity per AGENTS.md.
4. **3 new shared tests** — keeps full size when it fits, drops to 0.8 when full size does not,
   returns null when no scale fits.

## Verification

- **Live Pixel 7 Pro** (debug build installed over the existing debuggable package):
  ```
  DominantStationScale: scale=1.0 width=244.0   → failed
  DominantStationScale: scale=0.8 width=195.0   → reason=drawn fontScale=0.8
                                                   boxLeft=5 boxRight=200 centerX=103 baselineY=137
  ```
  Screenshot confirms `knuq 87.8° @ 1:15 pm` now renders in the wide view at the left edge, 80% size.
- `./scripts/unit-tests.sh`: **4068 tests passed** (1545 shared, incl. 3 new).
- `ktlintCheck`, `:app:assembleDebug`, `:desktop:createDistributable` pass.

## Follow-up — now implemented

The borrowed-actuals source label (`placeActualsSourceLabel`) and the forecast-delta label share the
same `no_empty_band` gate, so the same fallback was added to both, on both platforms:

- Shared `ForecastDeltaLabel.placeWithFontFallback` (mirrors `DominantStationLabel`) with
  `FALLBACK_FONT_SCALES = [1.0f, 0.8f]`; 3 new tests in `ForecastDeltaLabelTest`.
- Android `placeActualsSourceLabel` now uses `DominantStationLabel.placeWithFontFallback`
  (re-measured per scale); `placeForecastDeltaLabel` uses `ForecastDeltaLabel.placeWithFontFallback`
  and re-measures value/caption at the winning scale.
- Desktop `TemperatureGraph`: both labels use their `placeWithFontFallback` variant
  (`buildDeltaLabelAnnotatedString` gained a `fontScale` parameter; the actuals-source label
  re-measures the annotated string per scale).
- Both Android diagnostics now log `fontScale=`. Live check on the Pixel: the delta label still
  draws at full size (`ForecastDeltaDiag reason=drawn fontScale=1.0`) — no regression; the
  actuals-source label was `no_text` because NWS is the display source.
- `./scripts/unit-tests.sh`: **4071 tests passed** (1548 shared); `ktlintCheck` and
  `:desktop:createDistributable` pass.

## Cloud graph follow-up — same fallback, extended ladder

The cloud-cover graph's borrowed-actuals label ("Actual cloud cover data from METAR") still used the
single-scale `place`, and on the emulator's 2×2 Silurian cloud widget it was dropped
(`ActualsSourceDiag: reason=no_empty_band … text=Actual cloud cover data from METAR`). Two things
were needed:

1. **Extended ladder.** 20% (0.8) still did not fit — the label measured 248 px wide × 31.5 px tall
   against a ~25 px band. `FALLBACK_FONT_SCALES` is now `[1.0f, 0.8f, 0.6f]` in both
   `DominantStationLabel` and `ForecastDeltaLabel`; the cloud label draws at `fontScale=0.6`
   (184 px × 23.6 px).
2. **Day labels are now obstacles.** `HourlyIndicatorRenderer.drawDayLabels` drew the `Wed` day
   labels but never added their bounds to `drawnLabelBounds`, so the newly-visible label drew
   through `Wed`. Its `drawnLabelBounds` param is now `MutableList<RectF>` and each placed day label
   is registered.
3. **Cloud diagnostic.** `CloudCoverGraphAnnotations.drawDominantStationLabel` now uses
   `DominantStationLabel.placeWithFontFallback` and logs
   `ActualsSourceDiag: reason=… fontScale=… text=…` (VERBOSE), so this path is diagnosable.
   Desktop `CloudCoverGraph` got the same fallback (`measureDominant(fontScale)`).

Live emulator verification: `ActualsSourceDiag: reason=drawn spanH=18 fontScale=0.6
text=Actual cloud cover data from METAR`, and the screenshot shows the label at the top-right,
clear of `Wed` and unclipped. `./scripts/unit-tests.sh`: **4071 tests passed**; new
"shrinks to 60 percent" tests in both shared label test classes.

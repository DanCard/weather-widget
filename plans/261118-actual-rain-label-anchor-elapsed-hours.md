# Anchor the "Act" rain label on elapsed hours only

## Evidence

Desktop precip view, 2026-11-18 12:09 PST, Silurian, viewMode=PRECIPITATION: `Act .28in` drawn to the
RIGHT of the NOW line (centred ~2p) under `Pred 1.5in`. The total is correct (only elapsed hours
carry an actual), but the label reads as future rain.

## Root cause

`RainPeriodSelection.selectPeriods(Mode.DAY_NIGHT)` builds both series from the same
`DayNightSegment`s, and `toRainPeriod` anchors each at `segment.centerX()`. For the day segment
that contains NOW (8a-8p), the actual period is centred on the whole 12 h run, 8 h of which are
future. `RainAmountLabelPlacer` pins x to `anchorX`, so the label always lands there. Shared code, so
Android draws the same.

## Fix (`:shared`)

- In `DAY_NIGHT`, the actual series' period is trimmed to `segment.start .. last index with a
  non-null actual` before totalling/anchoring. `anchorX`, `endIndex`, `endLabel` follow the trimmed
  range. Callers already null every non-elapsed hour, so this is the elapsed part of the segment.
- Forecast periods, totals, and segment ranking are unchanged.

## Tests

- `RainPeriodSelectionTest`: a day segment crossing NOW → actual anchor = midpoint of elapsed hours,
  < NOW x; forecast anchor unchanged. Fails on the old code.
- `PrecipitationGraphRendererTest` (Android renderer through `calculateLayout`): placed `Act` label
  centre is left of the NOW x for a segment crossing NOW.
- `DesktopRainHourNowGateTest`: desktop `buildRainHours` + shared selection end-to-end, actual
  anchor left of NOW.

## Verification

Desktop screenshot of the same view after `buildStart-desktop.sh`; emulator precip-view screenshot.

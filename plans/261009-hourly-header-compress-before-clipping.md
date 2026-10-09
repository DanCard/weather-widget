# Hourly header: compress before dropping; "from yest" first to go

## Report

Emulator (Generic_Foldable, 360 dp widget): a red "8" after the current temperature.

## What it actually was

- The "8" was the delta "-4.8" (later "-6.2") with its "-" and most of its "." not drawn. The view
  was its natural 60 px; layout bounds showed empty room where the minus belonged. Restarting the
  emulator's launcher drew "-6.2" correctly with the same build — a stale glyph cache in the
  emulator's launcher, not our layout. The Pixel showed "-5.0 from yest" correctly throughout.
- A real gap found on the way: `resolveHeaderDisclosure` never counted the inline nav row
  (graph selector · stations · home · history) that hourly views add below 420 dp. At 360 dp the
  header still fit; below ~345 dp the delta would have been squeezed.

## Change (user, 2026-10-09)

"There is space to shift the icons right … the weather icon and current temperature could shrink
if info doesn't fit." Then: "from yest should be dropped if there isn't room."

- `HourlyHeaderFit` (pure): at each disclosure level, first narrow the inline zones toward 28 dp
  (icons move right into their own padding), then shrink weather icon + temperature together
  toward 80 %; only then drop the next thing (icon, delta, rain %, as before).
- `HeaderWidthChecker.fitHourlyHeader` measures the real text and drives it; temperature, cloud
  and rain views use it (`TemperatureViewBinder`, `HourlyHeaderBinder`), and `positionCenterIcons`
  takes the fitted zone width. Wide widgets (≥ 420 dp) unchanged.
- "from yest" is shown only where it fits as laid out: it never narrows the zones or shrinks the
  temperature, and never sits beside a rain chance.
- `current_temp_delta` XML: `maxLines="1"`, `textSize="14dp"` (matches `DELTA_TEXT_SIZE_DP`; was
  12sp) — a delta that ever overflows clips rather than wrapping into an invisible second line.
- `HEADER_FIT` debug log per render.

## Tests

`HourlyHeaderFitTest` (pure) and `HeaderWidthCheckerHourlyFitTest` (Robolectric, NATIVE graphics —
the legacy shadow's `measureText` is not font-accurate): order of compression, floors, never
overflowing at 240–419 dp, caption never squeezing anything. Full unit suite 4925 passed.
Emulator: "61.1° -6.2", nominal zones, caption dropped at 360 dp.

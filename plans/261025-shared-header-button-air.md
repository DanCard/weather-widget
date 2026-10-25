# Header centre buttons: one shared spacing rule, airiest that fits

## Symptom
Pixel 7 Pro (373dp widget), hourly header: home / graph selector / history sit almost touching.

## Root cause
Android sizes each centred button's zone (icon ≈ 20dp centred in it) from two separate tables:

| widthDp | daily (`dailyCenterIconZoneWidthDp`) | hourly (`hourlyCenterIconZoneWidthDp`) |
|---|---|---|
| < 360 | 24 | 24 |
| 360–419 | **32** | **24** |
| 420–449 | 40 | 40 |
| ≥ 450 | 52 | 56 |

At 373dp the hourly row gets 24dp zones → 4dp of air between icons (the daily table's own comment
says that "reads as one control"). Log: `HEADER_FIT view=hourly widthDp=373 … centreButton=63.0`
(= 24dp × 2.625). Desktop has a third rule again: 15·scale icons with 14·scale (daily) /
12·scale (hourly) between them.

A fixed per-width zone is also brittle: it is chosen before `HeaderFit` knows what else is on the
row, so a wider zone can push the buttons off a rung (or drop home) when a narrower one would fit.

## Fix
1. `:shared` `HeaderButtonZone` — the air around each centred button, by header width (dp,
   unscaled): < 360 → 6, 360–419 → 12, 420–449 → 20, ≥ 450 → 32 (today's daily table expressed as
   air around a 20dp icon). `MIN_AIR_DP = 6` (user, 2026-10-25: never back to the clumped 4).
   `zoneDp(icon, width)` = icon + air; `minZoneDp(icon)` = icon + 6.
2. `HeaderFit` narrows centred zones like it squeezes inline ones: `Measured.centreButton` is the
   preferred zone, new `minCentreButton` the floor; `Plan.centreZone` is the widest zone at which the
   plan fits (bisected). Buttons only step down a rung (middle → inline) or lose home once the floor
   does not fit. Priority order is unchanged.
3. Android, daily and hourly: `HeaderWidthChecker.centerIconZoneWidthDp(widthDp)` (20dp icon +
   shared air; the 24dp XML zone below API 31, no squeeze) feeds the fit; `positionDailyIcons` /
   `positionCenterIcons` lay out `plan.centreZone`. `dailyCenterIconZoneWidthDp`,
   `hourlyCenterIconZoneWidthDp` and the `DAILY_CENTER_ICON_ZONE_*` tables are gone.
4. Desktop: zone = `HeaderButtonZone.zoneDp(15, widthDp / scale) · scale`, floor
   `minZoneDp(15) · scale`; each button drawn centred in a `plan.centreZone`-wide box (the graph
   selector's emoji too). The hard-coded 14·scale / 12·scale gaps are gone. Desktop's ≥ 450dp row
   gets more air than before (32 vs 14 at scale 1) — same look as the Android widget.

Pixel 7 Pro hourly after: 3 buttons at 12dp air fit on the middle rung (≈ 3px spare); with home as a
4th they narrow toward 6dp rather than leaving the middle; the date and "from yest" never narrow
them (lower priority than the buttons' air), the weather icon may.

## Tests
- `:shared` `HeaderFitTest`: the Pixel 7 Pro hourly measurement (from the log above) → MIDDLE,
  32dp zone; crowded buttons narrow toward the floor before leaving the middle; the date never
  narrows them.
- `HeaderButtonZoneTest`: the four rungs.
- Android `PositionDailyIconsRoboTest` / `DailyIconZoneWidthTest`: the rungs (26/32/40/52), and the
  laid-out zone is the one the fit chose.
- Desktop `DesktopHeaderFitTest`: the last zone ends on the line (icon half the air short of it).
- On device: Pixel 7 Pro and Fold screenshots, daily + hourly; desktop restart + screenshot.

## Done (2026-10-25)
- Squeeze is on the zone (`Measured.minCentreButton`, `Plan.centreZone`), not the spacing: desktop now
  draws each button centred in a zone too, so both platforms lay out the same way.
- Pixel 7 Pro hourly, 4 buttons: `centreZone=84.0` (32dp, 12dp air), on the line right of middle.
  Fold daily 662dp unchanged (52dp). Desktop hourly row evenly spaced.

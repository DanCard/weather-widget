# Hourly footer labels count from the window start

## Request

Emulator, hourly temperature graph (2026-11-07 18:32): the footer read `12p 4p 8p 12a 4a` with an
empty bottom-left corner. User: "Start with the correct time label. In this case 10 am. Then do a
cadence after that. Start time + 4h or 3h. Make sure there is an integrated test for this."

## Evidence

- Render log: `buildHourDataList: source=NWS ... visualWindow=10:00:00 to 04:00:00` — the 18 h window
  starts at 10:00, interval 4 (wide widget).
- `HourlyFooterLabels.select` (`shared/.../graph/HourlyFooterLabels.kt:37-39`) labels only
  `localHour % interval == 0`, so 10a is skipped and the first label is 12p, two hours in.
- This rule ("tidy clock hours", plans/261024-shared-hourly-footer-labels.md) replaced Android's
  left-edge counting. The user now asks for left-edge counting back, in `:shared` so every graph and
  both platforms agree.

## Change

1. `HourlyFooterLabels.select` / `labelledTimes` take an explicit `windowStartMs`. The anchor is the
   first whole hour at or after it; a point is labelled when it is on the hour and its elapsed hours
   from the anchor are a multiple of `interval`. Elapsed (not wall-clock) hours keep the spacing even
   across a DST change.
   - The anchor is the **window start**, not the first point: the precip and cloud graphs pass only
     hours that have a forecast, and a missing first hour must not shift every label (the existing
     "missing hour" test keeps guarding this).
   - Date mode is unchanged.
2. Callers pass the window start:
   - Android `TemperatureHourDataBuilder`, `PrecipViewHandler`, `CloudCoverViewHandler`: `startHour`.
   - Desktop `DesktopGraphUtils.footerLabels`: gains a `windowStartMs` parameter supplied by
     `TemperatureGraph` and the cloud/precip graphs from their window start (verify each caller's
     window origin while implementing; fall back to the first point only where it *is* the start).
3. Interval rules (`HourlyZoomRules.footerLabelInterval`, desktop `labelIntervalForWidth`) stay as is;
   they need not divide 24 any more but no value changes.
4. Update the KDoc in `HourlyFooterLabels` and the "tidy clock hours" comments at each caller.
   Update `arch/` if a living doc describes the footer rule (grep while implementing).

Consequence the user accepted: labels follow the window, so they change hour to hour as the window
moves (10a 2p 6p… at 6:30 pm, 11a 3p 7p… at 7:30 pm) and when panning.

## Tests

| Test | Kind | What it asserts |
|---|---|---|
| `HourlyFooterLabelsTest` — 10:00-start 18 h window at interval 4 | unit | `10a 2p 6p 10p 2a`; first label at index 0 |
| `HourlyFooterLabelsTest` — interval 3 | unit | `10a 1p 4p 7p 10p 1a 4a` |
| `HourlyFooterLabelsTest` — window start off the hour (10:20) | unit | anchor 11a |
| `HourlyFooterLabelsTest` — first hour missing from the points | unit | labels unchanged vs. full list (anchor = window start) |
| `HourlyFooterLabelsTest` — DST fall-back day | unit | labels every 4 elapsed hours |
| `HourlyZoomSpanSettingRoboTest` — flip "all three graphs label the same tidy clock hours" to a 10a-start window | integration (Settings → zoom resolution → temperature, precip, cloud builders → `HourlyFooterLabels`) | all three graphs label `10a …` at the same cadence, starting at the first point |
| `DesktopGraphZoomTest` — `footerLabels` with a 10:00 window | integration (`DesktopGraphUtils` → `HourlyFooterLabels`) | `10a 2p …` |

Existing tests that encode clock-hour phase (`clock labels land on tidy clock hours…`, desktop
`footerLabels uses clock-hour cadence…` (midnight start, so unchanged), the narrow-widget test) get
their expectations updated; each new expectation must fail on the old code.

## Verification

1. `./scripts/unit-tests.sh`.
2. `./gradlew installDebug`; emulator screenshot of the temperature, precip and cloud graphs: first
   footer label sits at the left edge (10a-style) and is not clipped.
3. Desktop: rebuild and restart; same check on its three graphs.

## Implementation notes (2026-11-07)

- Desktop's last footer label was drawn centred on its point with no clamp. With labels counted from
  the window start, an 18 h window at 3 h ends exactly on `4a`, which hung half off the canvas. It is
  now clamped inside the strip, as Android's `HourlyFooterLayoutPlanner` already clamps clock labels.
- Verified: emulator temperature, cloud and rain graphs read `10a 2p 6p 10p 2a`; desktop reads
  `10a 1p 4p 7p 10p 1a 4a` with both edge labels unclipped. `./scripts/unit-tests.sh`: 6696 passed.

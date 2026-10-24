# Hourly footer labels: one shared selection (audit item G, the rest)

User, 2026-10-24: "commit and proceed" → G from `261021-android-desktop-shared-code-audit.md`.

## Where G stands
Mostly done already. The span is the shared input (user, 2026-10-21) and `:shared`
`HourlyZoomRules` owns the tables: `labelIntervalForSpan`, `smoothIterationsForSpan`, `isDateMode`
(`DATE_FOOTER_MIN_SPAN_HOURS`), `navJumpHours`, `footerLabelInterval` (narrow widget), and
`tapOffsetHours`. The window itself is `HourSpan` (`091830b7`). The audit's "awaiting the zoom-model
decision" note is stale. Desktop's continuous zoom (`backHoursFor` / `forwardHoursFor` /
`panDelta*` / `ZOOM_SENSITIVITY`) is pointer input with no Android counterpart; it stays put.

## What is still written twice: which points get a footer label
| | Android (`TemperatureHourDataBuilder`, `HourlyGraphViewCommon` for precip/cloud) | Desktop (`DesktopGraphUtils.footerLabels`) |
|---|---|---|
| Clock labels | `hourIndex % interval == 0`, `hourIndex` counted from the **window's left edge** (precip/cloud only advance it on hours with a forecast) | `localHour % interval == 0`: **tidy clock hours** |
| "Now" hour | precip/cloud always label the hour nearest now (`isClosest`); temperature doesn't | never |
| Date mode | per local date, noon clamped to the window (`dateLabelMillis`) | per local date, the point nearest local noon (`representativeIndicesByDay`) |
| Interval | stage + narrow widget (`footerLabelInterval`) | pixel budget (`labelIntervalForWidth`, finer steps 3 h / 8 h) |

**Divergence:** at a 4 h interval with the window starting at 1 pm, Android labels 1p 5p 9p and
desktop 12p 4p 8p. `labelIntervalForSpan`'s own KDoc promises "every value a divisor of 24 so
`hour % interval == 0` lands on tidy clock hours", which only desktop does. On Android, panning one
hour moves every label, and a missing forecast hour shifts the rest (precip/cloud).

The two date-mode rules agree on hourly points (nearest available hour to noon); one copy is enough.

## Plan
1. `:shared` `HourlyFooterLabels.select(pointTimesMs, spanHours, interval, zone)` → indices to label
   and which are dates: date mode = the point nearest local noon per local date; otherwise
   `localHour % interval == 0` on the hour. Desktop's `footerLabels` / `representativeIndicesByDay`
   and Android's `dateLabelMillis` / `hourIndex % interval` call it.
2. `labelIntervalForWidth` (pure) moves to `HourlyZoomRules`. Choosing the interval stays a platform
   input: Android keeps the stage/column rule (`footerLabelInterval`), desktop the pixel budget. Both
   are divisors of 24, so `select` lands on clock hours either way.
3. The "now" label: per the decision below.

## Decisions (user, 2026-10-24: tidy clock hours; drop the "now" label)
1. **Android clock labels on tidy clock hours** (12a 4a 8a…, as desktop) instead of counting from the
   window's left edge. *Recommended*: labels stay put while panning, and a gap no longer shifts
   them.
2. **The always-labelled "now" hour** on Android's precip and cloud graphs. Options: drop it (as the
   temperature graph and desktop; the now line already marks the time, and the extra label can
   crowd its neighbour), or add it everywhere. *Recommended: drop.*

## Tests
- `:shared` `HourlyFooterLabelsTest`: a 1 pm-start window at interval 4 labels 12/4/8 (fails on
  Android's index rule); a missing hour does not shift later labels; date mode = nearest-noon per
  date including partial first/last days; interval 1 labels every hour.
- Android precip/cloud/temperature hour builders: same labelled hours for the same window.
- Desktop: `footerLabels` tests stay green.
- Full suite; screenshot an hourly widget on the Pixel; restart desktop.

## Done (2026-10-24)
- `:shared` `HourlyFooterLabels.select` / `labelledTimes`: date mode = the point nearest local noon
  per local date; otherwise `localHour % interval == 0` on the hour. No "now" exception.
- Desktop `footerLabels` calls it (interval still `labelIntervalForWidth`, desktop's pixel budget —
  left in `DesktopGraphUtils`: only desktop chooses by pixels, no twin); `representativeIndicesByDay`
  removed.
- Android: temperature (`TemperatureHourDataBuilder`, over the window's hours), precip and cloud
  (over the hours they draw) pass the shared set to `HourlyGraphViewCommon.resolveHourLabel`;
  `dateLabelMillis`, the `hourIndex % interval` cadence and precip/cloud's always-labelled now hour
  are gone. The interval is still `HourlyZoomRules.footerLabelInterval` (stage + narrow widget).
- Tests: `:shared` `HourlyFooterLabelsTest` (tidy hours on a 1 pm-start window; a gap does not shift
  labels; no "now" label; date mode incl. partial days — took over `DateLabelMillisTest` and desktop's
  `representativeIndicesByDay` cases); `HourlyZoomSpanSettingRoboTest` (all three Android graphs label
  10a 12p 2p 4p on a 9a–5p window — 9a 11a 1p 3p 5p before). Full suite green. Not checked on a
  screen: no hourly widget was visible (Samsung screen off).

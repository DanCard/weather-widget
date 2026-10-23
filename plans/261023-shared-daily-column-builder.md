# One shared daily column builder (audit item F)

User, 2026-10-23: approved the duplicate-logic audit order (`261023-duplicate-logic-audit-different-names.md`).
Step 3a (done, same day) moved the drifted bar inputs to `:shared`: `DailyNoonCloudCover.forColumn`,
`WeatherColors.dailyForecastArgb` / `snapshotBarArgb` / `SNAPSHOT_YELLOW`,
`TodayThermostat.BULB_*`. This plan is 3b: the whole column.

## Today
| | Android | Desktop |
|---|---|---|
| Code | `DailyViewLogic.prepareGraphDayInputs` (~340 lines) + `DailyTodayResolver` (179), `DailyPastDayResolver` (124), `DailyFutureDayResolver` (70) | `DesktopDailyForecastModel.buildDay` (~335 lines) |
| Input | Room entities: `ForecastEntity`, `DailyHistory` map, `HourlyForecastEntity`, `ObservationEntity` | `:shared` model: `DailyForecast`, `DailyHistory`, `DailyForecastSnapshot`, `HourlyForecast` |
| Output | `DayData` (renderer) via `PreparedGraphDay` | `DesktopDailyDay` |

Both call the same shared pieces (`DailyDayValueResolver`, `TodayLowSettle`, `PartialForecastDays`,
`PriorDayForecast`, `DailyRainLabels`, `DailyIconName`, `PastDayForecastOverlay`) but combine them in
their own today / past / future branches. Every daily-view parity bug lives in that combining.

## Design
1. **`:shared` `DailyColumnBuilder.build(input): List<DailyColumn>`**, starting from desktop's
   `buildDay` — it already runs on `:shared` types.
   - `DailyColumnInput`: dates to show, now, zone, display source + site, per-date `DailyForecast`
     rows (all fetches, for the today / prior pick), `DailyHistory`, hourly, current temp, climate
     normals, locale flags.
   - `DailyColumn`: the union of `DesktopDailyDay` and `DayData` *values* — highs / lows per line,
     line kinds and stand-in flags, icon name, snapshot icon, noon cloud, day / night rain %,
     `priorBarRainPercent`, rain label texts, overlay flags, lowSettled, nowHour. No paint, no
     drawable ids.
2. **Android** maps entities → `:shared` types (`toHourlyForecast` exists; add `ForecastEntity` →
   `DailyForecast` / `DailyForecastSnapshot`), calls the builder, maps `DailyColumn` → `DayData`
   (icon name → drawable id is the only platform step). The three resolvers and the column half of
   `DailyViewLogic` go.
3. **Desktop** `DesktopDailyDay` becomes `DailyColumn` (or a thin wrapper for popup-only fields);
   `buildDay` goes. Navigation / column count / overlay decisions stay where they are (separate
   items: `columnsForWidthDp` is already PENDING).

## Finding the drift first — a parity harness
Before switching anything, a test feeds **the same fixtures** to Android's current
`prepareGraphDayInputs` and desktop's current `buildDay` (Android side mapped to `:shared` types the
other way) and diffs every value field per column. Fixtures: today with / without actuals, a past
day with frozen history, a past day promoted from forecast, future near-term and far, climate gap,
partial NWS evening batch, Silurian today-row missing, GENERIC_GAP, another-source row.
- Each diff is a divergence like 3a's. It gets a decision (Android's rule, desktop's, or a new one)
  recorded in this plan, and a shared test.
- The harness then becomes the builder's regression test: both old paths are deleted only after
  the builder reproduces the decided value for every fixture.

## Order
1. Parity harness + decisions table (no behaviour change). **Review point** — the decisions are yours.
2. `DailyColumnBuilder` in `:shared` from `buildDay`, with the decided rules; harness green.
3. Desktop onto it; then Android (mapping both ways); delete the old paths.
4. Remove the "matches Android" comments the builder made true; shrink both duplicate-guard
   allowlists where entries go away.

## Verification
Harness + existing `DailyViewLogicTest`, `DesktopDailyForecastModelTest`, renderer Robo tests;
`./gradlew testDebugUnitTest :shared:test :desktop:test`; `./scripts/emulator-tests.sh` for the
widget paths; desktop restart and an emulator widget screenshot of the same day side by side.

## Step 1 result (2026-10-23)
Harness built: `:shared` testFixtures `DailyParity` (8 fixtures, one `Column` vocabulary),
`AndroidDailyParityTest` (Robolectric, `prepareGraphDays` as `DailyGraphRenderer` calls it: header
rain %, today's label allowed), `DesktopDailyParityTest` (`DesktopDailyForecastModel.build`; today's
row as `DesktopWeatherDao.getDailyForecasts` picks it), `scripts/daily_parity_diff.py`.

Both platforms now **assert** the agreed columns (`testFixtures/resources/daily-parity/<fixture>.txt`),
so drift on either side fails its test. Mutation-checked: restoring desktop's old prior-icon rule
fails `stale-prior`.

Divergences found, after the 3a fixes:
| Field | Android | Desktop | Decision |
|---|---|---|---|
| Past day's prior icon with no prior bar | none | the day's icon | Android's (no bar, no icon) — applied to desktop; not visible either way |

Everything else — bar values, forecast overlay, prior bar, icons, noon cloud, day/night rain and
labels, flags — matches on all 8 fixtures. Four first-run "divergences" were harness setup (desktop's
default visible sources excluded Google; today's row picked in desktop's DAO, not its model; the gap
source id is `"Generic"`; the window ran past desktop's last column).

Consequence for steps 2–3: the two builders already agree on these cases, so the shared builder can
be extracted from desktop's `buildDay` with the golden files as its regression test, rather than as
a series of behaviour decisions. Fixture coverage is the limit: add one per future parity bug.

## Steps 2–3 result (2026-10-23)
- `:shared` `DailyColumnBuilder.build` (from desktop's `buildDay`) + `DailyColumn`; desktop's
  `DesktopDailyDay` is a typealias. `PartialForecastDays.fillPartialFutureDays` (was desktop's
  repository + Android's `DailyFutureDayResolver`) runs on both platforms before building.
- Android's graph path (`DailyViewLogic.prepareGraphDayInputs`) maps entities → `:shared` rows
  (`ForecastEntity.toDailyForecast` / `toDailyForecastSnapshot`), calls the builder, maps the column
  → `DayData`; Android-only extras stay (day-tap rain summary, today text fallbacks, label low).
  `DailyPastDayResolver` and `DailyTodayResolver.resolveTodayValues` deleted; their tests now run
  through `prepareGraphDays` (`PastDayOverlayColumnTest`, `TodayLowSettleColumnTest`).
- Found while moving, not by the fixtures: "yesterday's forecast" staleness is judged by when a row
  was **last confirmed** — Android's `batchFetchedAt` (dedup re-stamps it), desktop's `fetchedAt`.
  `DailyForecastSnapshot.lastConfirmedAt` + `PriorDayForecast.isPickStale`; mutation-checked.
- Parity goldens, 2,585 app / 659 desktop / 2,538 shared unit tests, 105 emulator tests (2 skipped)
  pass; emulator widget and desktop popup draw the same columns.

Left: Android's text mode (`prepareTextDays`, 1-row widget) still builds its own columns; desktop
has a text mode too — next candidate for the same builder.

## Text mode (2026-10-23)
Android's 1-row widget (`prepareTextDays`) now builds its columns with the same shared builder as
its graph (helpers `shownDailyRows` / `sharedColumn`); the printed high / low is `:shared`
`DailyColumnBuilder.textHigh` / `textLow`, also used by desktop's text popup. Changes: desktop's
text high/low no longer folds in "yesterday's forecast" (it took max / min over it); Android's text
icon now takes noon cloud like the graph's. Dead: `DailyFutureDayResolver.resolveFutureDayValues`,
`DailyViewLogic`'s private rain-label / noon-cloud helpers.

# All-sources day comparison on the Forecast History screen

## Request

User (2026-11-05): "I'm interested in seeing all the active APIs forecast for tomorrow, sorted by
high temp." Follow-up: "write the plan, skip the whisker, don't bother with fetched column, include
rain info, and cloud cover %." Then: "Include in the plan, example of what the output would look
like. Changed my mind, include column for age of forecast retrieval." Then: "Keep desktop and
android the same, with short names. Proceed with plan."

Decision: app screen only, no widget change. The widget shows one source at a time and its daily
header is already full. A sorted multi-source list belongs on a screen.

## Where it goes

The Forecast History screen, for both platforms:

- Android: `ForecastHistoryActivity`. A widget day tap already opens it for that date
  (`WidgetDayClickCoordinator.navigateToHistory`), and it has prev/next day navigation.
- Desktop: `ForecastHistoryWindow`, the same screen reached from desktop day clicks.

A new card, **"All sources for <day>"**, goes between the evolution graphs and the
"Accuracy (last 30 days)" summary. It works for any date. It is most useful for future days, and the
table is the same for past days, where it shows each source's final forecast.

## Table

| Column | Content | Data |
|---|---|---|
| Source | short name on both platforms | `WeatherSource.shortDisplayName` |
| High | forecast high | newest row's `highTemp` |
| Low | forecast low | newest row's `lowTemp` |
| Rain day | day chance %, plus the peak hour when shown on the widget (">10%") | `DailyRainLabels.resolveDailyLabelPrecipAtSite` → `dayPrecip`; `hourlyDayPrecipPeakAt` |
| Rain night | night chance % | same resolver → `nightPrecip` |
| Amount | daily forecast amount, in/mm per locale; blank if null | `precipAmountMm` via `DailyRainLabels.formatPrecipAmount` |
| Cloud | noon cloud % | `noonCloudPercent` / `hourlySummary` (same value the daily bar cap uses) |
| Bias | 30-day high bias, signed (e.g. `+2.1°`); blank with no history | `AccuracyPure` stats already computed for the accuracy card |
| Age | time since this row was last retrieved, compact (`12m`, `3h`, `1d`) | `now − lastConfirmedAt` of the newest row |

Age uses `lastConfirmedAt`, not `fetchedAt`. A re-fetch that returns the same values keeps the
row's original `fetchedAt` but bumps `lastConfirmedAt`. "When did we last hear from this source" is
the question the column answers. The compact format is a shared helper (`DaySourceComparison.formatAge`)
so both platforms print the same text; it is computed at render time from `nowMs`, not stored.
Ages over 24 h are drawn in the dimmed secondary color. That is the background fetch interval on
battery below 70%, so a row older than that missed at least one scheduled fetch.

Sorting: high, descending. Sources with no high go last, and ties keep `WeatherSourceOrdering`. The
row for the source currently being viewed is emphasized (bold) so it reads as "you are here".

Sources included: the user's visible/enabled sources (`getVisibleSourcesOrder()` on Android, the
desktop visible-sources pref), never `GENERIC_GAP` or climate normals. A source with no row for the
date is listed last as "—", not hidden. That way a missing source is visible, which follows the
no-silent-fallback rule.

## Example output

Illustrative values (not from a DB pull), °F, US locale, opened for tomorrow while viewing
Open-Meteo.

Android (phone portrait, Amount column dropped by the width rule):

```
┌ All sources for Fri Nov 6 ─────────────────────────────────┐
│ Source        High  Low   Rain      Night  Cloud  Bias  Age │
│ Google         68°  51°   20% 2pm    10%    35%  −1.2°  40m │
│ Meteo          67°  52°   15%         5%    40%  +0.4°   2h │  ← bold (viewed)
│ WAPI           67°  50°    8%         —     30%  +0.9°   3h │
│ NWS            66°  50°   30% 1pm    20%    45%  −0.3°   1h │
│ Silur          65°  49°   10%         5%    50%         7h │
│ OWM            64°  49°   40% 3pm    25%    60%  +2.1°  26h │  ← age dimmed (>24 h)
│ Tmrw            —    —     —          —      —     —     — │  ← no row for this date
└─────────────────────────────────────────────────────────────┘
```

Desktop (wider window keeps Amount):

```
All sources for Fri Nov 6
Source        High   Low   Rain day   Night   Amount   Cloud   Bias    Age
Google         68°   51°   20% 2pm     10%    0.02in    35%    −1.2°   40m
Meteo          67°   52°   15%          5%    0.01in    40%    +0.4°    2h
WAPI           67°   50°    8%          —        —      30%    +0.9°    3h
NWS            66°   50°   30% 1pm     20%       —      45%    −0.3°    1h
Silur          65°   49°   10%          5%       —      50%             7h
OWM            64°   49°   40% 3pm     25%    0.05in    60%    +2.1°   26h
Tmrw            —     —     —           —        —       —       —       —
```

How to read the examples:
- Both platforms show `shortDisplayName` (user, 2026-11-05).
- Order is by High; WeatherAPI and Open-Meteo tie at 67°, so `WeatherSourceOrdering` decides.
- The peak hour (`2pm`) appears only when the day chance is over 10%, the same gate as the widget.
- Silurian has no bias yet (no 30-day accuracy history), so that cell is blank.
- Tomorrow.io is enabled but has no row for the date, so it is listed last instead of hidden.
- Night `—` means the source provides no night chance for that day.

Logcat for the same render:

```
D DAY_SOURCE_COMPARE: date=2026-11-06 site=37.42,-122.08 rows=7 order=GOOGLE_WEATHER,OPEN_METEO,WEATHER_API,NWS,SILURIAN,OPEN_WEATHER_MAP,TOMORROW_IO missing=TOMORROW_IO
```

## Shared design (`:shared`)

New `shared/graph/DaySourceComparison.kt` (next to `ForecastHistoryViewLogic`):

```kotlin
object DaySourceComparison {
    data class Row(
        val source: WeatherSource,
        val high: Float?, val low: Float?,
        val dayRainPct: Int?, val nightRainPct: Int?, val dayPeakAt: Long?,
        val amountMm: Float?, val noonCloudPct: Int?,
        val highBias: Double?,
        val lastConfirmedAt: Long?,
    )
    interface Host {
        suspend fun visibleSources(): List<WeatherSource>
        suspend fun newestDailyRows(date: LocalDate, lat: Double, lon: Double, sources: List<String>): List<DailyForecast>
        suspend fun hourlyForDate(date: LocalDate, lat: Double, lon: Double, sources: List<String>): List<HourlyForecast>
        suspend fun highBias(source: WeatherSource, lat: Double, lon: Double): Double?
    }
    suspend fun build(host: Host, date: LocalDate, lat: Double, lon: Double, today: LocalDate, zone: ZoneId): List<Row>
}
```

The shared `build` owns the whole rule: source scope, site matching (collapse to the nearest site via
the existing `LocationMatch` / `selectNearestSite` helpers so a GPS-excursion fragment cannot
contribute a row), rain resolution through `DailyRainLabels` (so the % matches what the widget draws
for that source), peak-hour gate, cloud value, sort order, and one `Log.d` summary line
(`DAY_SOURCE_COMPARE date=… rows=… order=…`). Hosts only supply rows.

- Android host: `ForecastDao.getLatestForecastsInRangeForSourcesAllSites` (the newest row per
  source/day read) and the hourly DAO read the screen already uses; bias from
  `AccuracyCalculator.calculateComparison`.
- Desktop host: the `DesktopWeatherDao` newest-row-per-day read and `DesktopAccuracyCalculator`.

The bias is already computed on both platforms for the accuracy card. The host reuses that result
rather than calculating it twice.

## Rendering

- Android: a `TableLayout` (or a vertical `LinearLayout` of rows) inside a new card in
  `activity_forecast_history.xml`, styled like the existing accuracy card
  (`applyDataScreenStyle`). Rain chance uses the rain-blue text color; cloud stays secondary text.
  Strings go through resources with all 19 locale translations (locale parity test).
- Desktop: a Compose `GraphCard("All sources for …")` in `ForecastHistoryWindow.Content` with the
  same columns and order.
- Narrow phone width: Source / High / Low / Rain day / Rain night / Cloud / Bias / Age fit at ~12sp.
  Amount is dropped below a width threshold rather than wrapping, and the same rule is applied on
  desktop.

## Tests

1. `:shared` JVM `DaySourceComparisonTest` with a fake `Host`:
   - sorts by high desc, nulls last, ties by `WeatherSourceOrdering`;
   - a source with no row appears last as empty;
   - GENERIC_GAP / climate-normal rows excluded;
   - a second-site fragment row does not displace the nearest site's row;
   - NWS row uses its native period chance (matches `resolveDailyLabelPrecip`), so the table can't
     disagree with the widget's own rain %;
   - peak hour present only when day % > 10;
   - age comes from `lastConfirmedAt` (a re-confirmed row with an old `fetchedAt` reads as fresh);
     `formatAge` boundaries (59m → `59m`, 60m → `1h`, 24h → `1d`); the >24 h dim flag.
2. Android Robolectric: the activity renders N rows in the sorted order for a seeded Room DB.
3. Desktop Compose test: the card renders the same order for the same seed.
4. Host contract test in `shared/testFixtures` run by both the Room and desktop SQLite hosts (same
   pattern as `StitchedHourlyReadContract`): identical seeded rows give identical `Row` lists.

## Runtime verification

- Emulator: tap tomorrow on the widget, then screenshot the Forecast History screen. Compare the rows
  to `forecasts` in a `backup_databases.py` copy, and check the `DAY_SOURCE_COMPARE` logcat line.
- Desktop: open the same day from the desktop daily graph and confirm the order and values match
  Android for the same location.

## Docs

Update the Forecast History section of `arch/ARCHITECTURE.md` (screen contents) and add the new
shared runner to the structural map.

## Out of scope

- Widget whisker / spread indicator (user: skip).
- Bias-corrected ("adjusted") highs. The raw bias is shown; adjusting is a later decision.

## Implementation notes (2026-11-05)

1. Rows come from `DailyColumnBuilder` run with each source as the display source, so high, rain %,
   peak hour and noon cloud are the widget's own values for that source.
2. Ties keep the platform's visible-source order (the user's order), not `WeatherSourceOrdering`.
3. Bias sign: `AccuracyStatistics.highBias` is stored as actual − forecast; the runner flips it so the
   column reads forecast − actual ("+" = runs warm), matching the accuracy card's "(1.0° high)". The
   first emulator run showed the unflipped sign; `DaySourceComparisonTest` now pins it.
4. Bias is per platform: each uses its own accuracy history, so the column can differ between them.
5. `RoomDaySourceComparisonHost` reads raw hourly box rows (allowlisted in
   `HourlyProximityQueryAllowlistTest`); the shared runner picks the nearest site per source.
6. Amount column: shown when the table is at least 380 dp wide and some row has an amount, on both
   platforms (`DaySourceComparison.showAmountColumn`).

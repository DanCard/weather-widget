# Per-source rain score (Android + desktop)

## Goal

Answer "which API best predicted yesterday's rainfall amount?" without making the user compare rows by
eye, and give the same answer over 30 days. It should appear on both Android and desktop.

## Evidence (current state, 2026-11-13)

1. Android Statistics (History of Forecasts → Detailed stats) lists "Day/Night Rain Accuracy" rows:
   one per date per enabled source, each `pred X / act Y` for 8a–8p and night
   (`StatisticsActivity.kt:134`, `DailyRainAccuracyAdapter.kt`).
2. `app/.../stats/RainAccuracyCalculator.kt` builds those rows. Predicted = newest snapshot captured
   on the previous local calendar day, summed per bucket. Actual = `ObservedPrecip` (nearest
   gauge per hour) merged with archived `hourly_actuals` rain.
3. There is **no per-source rain score**. The summary at the top ranks temperature only
   (`AccuracyPure.computeStatistics`).
4. There is **no daily total**, only day and night buckets.
5. **Day and night buckets sum whatever hours exist.** When a source has 10 forecast hours but
   the gauges have 24, its bucket is compared against a larger actual. Coverage gaps then look like
   forecast error.
6. **Desktop has none of this.** `StatisticsWindow.kt` shows temperature for the displayed source
   only. Its database already holds everything needed: `hourly_forecast_history`, `observations`,
   and `hourly_actuals`.
7. `HistorySnapshotRetention` rule 4 already keeps the prior-day snapshot this calculator reads.
   Pruning is unchanged.

## Design

### Truth: one measured series for every source (decided 2026-11-13)

The existing calculator graded each source against **its own** rain history
(`ObservedPrecip.rowsFor(rows, source)`): NWS against gauges, Open-Meteo/Tomorrow.io against their own
model analysis (Open-Meteo logged 660 hours of `0` this week while NWS gauges recorded rain on
Nov 8), and WeatherAPI against its archive. Google, Silurian and OWM had none. The user chose
**measured gauges, shared**:

- `ObservedPrecip.measuredRows(rows)` keeps rows from `STATION_OBSERVATION` feeds (NWS, METAR,
  Synoptic), excluding `NWS_BLEND` and synthetic `<SOURCE>_MAIN` series. `ObservedPrecip.byHour`
  then applies the existing rule: nearest gauge per hour within 25 km, a report period takes its
  max, AO2 with no `P` group = 0.
- Older days behind raw-observation retention (~13 days) fall back to the NWS `hourly_actuals`
  archive, which the archiver builds from the same NWS gauge rows. Live hours always win
  (`HourlyActualsHistory.merge`). Outside NWS coverage, older days have no truth and aren't scored.
- The Statistics day/night list switches to this truth as well, so every rain-accuracy surface
  grades against the same numbers.

### Scoring rule (`:shared`, pure)

New `shared/.../shared/stats/RainScore.kt`:

- **Paired hours.** A source's daily total sums only hours that have both a predicted amount and
  an actual amount. Predicted and actual totals then always cover the same hours, which fixes
  evidence item 5.
- **Zero is data; null is not.** A forecast of `0` or a measured-dry gauge hour is a real paired
  value. Only a *missing* value (no snapshot for that hour, or no working gauge) leaves an hour
  unpaired. A null forecast is never treated as 0, and neither is a null gauge reading.
  Evidence from the Pixel backup (2026-11-13 10:26), covering the newest prior-day snapshot for
  hours in the last 7 days: every source stores dry hours as `0.0`, with 0 nulls (OPEN_METEO_PRIOR24
  is all null and is not a scored source). On the gauge side, `ObservedPrecip` records an AO2 METAR
  with no `P` group as measured 0. 2026-11-07…12 each had 24 gauge-covered local hours.
- **Day counts** when it has at least 20 paired hours (`MIN_PAIRED_HOURS`). This threshold counts
  hours that have *data*, not hours that had *rain*. A day with 6 rainy hours and 18 measured-dry
  hours has 24 paired hours and counts in full. A day is dropped only when 5+ hours have no data
  on one side, typically a device that was off or a site move. Those days show `—` and the row
  displays the paired-hour count (e.g. `14h`), so the gap is visible instead of silently scored.
- **Same hours for the ★.** Closest-per-day compares sources on the hours that *every* counted
  source has paired, so a source missing a rainy hour can't win by skipping it. Each source's 30-day
  score still uses its own paired hours.
- **Per-day error** = predicted total − actual total (mm). Positive means the forecast was too wet.
- **Per-source summary** over the window:
  - `rainDays`: counted days where actual ≥ 0.254 mm (0.01") **or** predicted ≥ 0.254 mm.
  - `meanAbsErrorRainDays`: the headline number. On dry-vs-dry days every source scores ≈0, so
    scoring all days would hide the differences between sources.
  - `bias` (mean signed error on rain days): wet or dry tendency.
  - `hitRate`: fraction of counted days where rain/no-rain at the 0.01" threshold matched.
  - `countedDays`, so a source with 3 days of data isn't ranked above one with 25.
- **Closest-per-day.** For each date, the source with the smallest |error| among counted rows is
  marked. Ties within 0.254 mm all get the mark. The mark is only shown when at least one of
  those sources had a rain day, so dry days are never marked.
- Ranking order: `meanAbsErrorRainDays` ascending. Sources with fewer than 3 rain days are listed
  last as "too few rain days".

### Shared runner + hosts (AGENTS.md: runner owns the rule)

- Move `RainAccuracyCalculator`'s logic into `shared/.../shared/stats/RainAccuracyRunner.kt`. The
  runner owns the date window, the prior-day snapshot pick, observed + archive merge, day/night
  bucketing, pairing, scoring, closest-per-day, and logging (one `Log.d` per run with per-source
  counted days and MAE).
- `RainAccuracyRunner.Host` supplies raw rows only:
  `forecastSnapshots(source, dayStart, dayEnd, bucketStart, bucketEnd, lat, lon)`,
  `observations(dayStart, dayEnd, lat, lon)`, and `archivedActuals(dayStart, dayEnd, lat, lon, source)`.
- Android: `RoomRainAccuracyHost` (existing DAOs). `RainAccuracyCalculator` becomes a thin
  Hilt wrapper around the runner, or is deleted and its callers inject the runner directly.
- Desktop: `DesktopRainAccuracyHost` over `DesktopWeatherDao` (new read queries mirroring the Room
  ones, keyed with the shared `LocationMatch` box like the Room queries).
- `DailyRainAccuracy` gains `predTotalMm`, `actualTotalMm`, `pairedHours`, `errorMm`, and
  `closest: Boolean`. A new `RainSourceScore` data class sits in `AccuracyModels.kt`.

### UI: History of Forecasts → Forecast Accuracy card (primary surface)

The existing "Forecast Accuracy" card (Android `accuracy_card` in
`activity_forecast_history.xml`; desktop "Accuracy (last 30 days)" block in
`ForecastHistoryWindow.kt:373`) splits into two sections, Temperature first:

```
Forecast Accuracy (last 30 days)

Temperature
  NWS           High 2.1° (+0.8)  Low 1.9° (−0.3)   ← existing lines, unchanged
  ...

Rain amount
  1. Google      off by .04in on avg · wet +.02 · rain/no-rain right 85% · 7 rain days
  2. NWS         off by .09in on avg · dry −.06 · rain/no-rain right 80% · 7 rain days
  –  Silurian    too few rain days (2)
  Nov 12: actual .37in   ★ Google .38 · NWS .22 · OWM .61 · …
```

- **Ranking lines** come from `RainSourceScore`, best first. They cover the same visible sources as
  the temperature section.
- **Viewed-day line** appears only when the viewed date is in the past and the actual has
  enough coverage. It shows the actual total plus each source's 1-day-ahead total, with ★ on the
  closest. Today reads "Today: scored after midnight". Paging back a day (prev-day button)
  answers "who got yesterday's rain right".
- **Not added to the Source comparison table.** Its "Amount" column is the *latest*
  forecast for 8am–8pm only, a different quantity. Putting the two side by side would read as a
  contradiction.
- **Built in `:shared`.** Section composition (ranking order, which lines appear, ★ choice) is a
  shared `ForecastAccuracySummary` model. Each platform only renders strings. Android strings go in
  resources with all 19 locale translations (locale parity test).
- Refreshes with the existing accuracy summary load (same coroutine and the same error path, which
  shows `error_loading_accuracy_summary`).

### UI: Android Statistics (secondary, detail)

- The existing "Day/Night Rain Accuracy" rows add a **Total** column (`pred .41 / act .37`) and the ★.
  The day and night columns stay.
- Desktop `StatisticsWindow` gets no rain table in this change. It never had the day/night list,
  and History of Forecasts is the parity surface. (Out of scope; noted below.)
- `activity_settings.xml` / `SettingsWindow.kt` are untouched (no new preference).

Amounts use the existing `DailyRainLabels.formatPrecipAmount` / `formatObservedPrecipAmount`
(inches for US/GB, mm otherwise; observed traces read "Trace").

### Out of scope

- Scoring today while it's in progress (still yesterday-and-earlier only).
- Lead times other than 1 day.
- Changing which station's gauge is "actual" (stays `ObservedPrecip`).
- A day/night rain table in desktop `StatisticsWindow` (a pre-existing gap).

## Tests

All new tests must fail on the current code.

1. `:shared` `RainScoreTest` (pure JVM):
   - Paired-hours sum ignores hours that exist on one side only.
   - A day with 6 wet hours and 18 hours forecast/measured as `0` counts (24 paired hours), and its
     total equals the 6 wet hours' sum.
   - A null forecast hour stays unpaired and is not scored as 0. Fixture: source null at a wet hour
     vs a source that forecast 0 there. The first is unpaired; the second is penalized.
   - ★ uses only hours common to all counted sources. Fixture: source A is missing the wettest hour.
     It must not win the ★ by skipping that hour.
   - < 20 paired hours → not counted.
   - Dry/dry day is excluded from MAE but counted in hit rate.
   - Sign of bias.
   - Closest-per-day, including ties and the "no mark on dry days" rule.
   - Ranking puts sources with < 3 rain days last.
2. `:shared` `RainAccuracyRunnerTest` with a fake `Host`:
   - Snapshot pick = newest captured on the prior local day; a newer same-day snapshot is ignored.
   - Observed + archive merge.
   - Two sources over a 3-day fixture where source A wins on totals but loses on the day bucket
     alone. This is the discriminating case that proves totals are scored, not day buckets.
3. Android Robolectric test: `RoomRainAccuracyHost` against in-memory Room returns rows the runner
   scores identically to the fake-host fixture.
4. Desktop test: `DesktopRainAccuracyHost` against a real temp SQLite DB, same fixture and same
   expected scores. This is the parity check.
5. Keep `HistorySnapshotPrunerTest`'s rain-accuracy case green (it calls
   `latestSnapshotPrecipByHour`; update the import to the shared location).

## Verification

1. `./scripts/unit-tests.sh`.
2. `./gradlew installDebug` on all connected devices. Open History of Forecasts on the emulator and
   the phone, scroll to Forecast Accuracy, and screenshot today (expect "scored after midnight")
   and yesterday (expect the ★ line). Check that logcat's runner `Log.d` line matches the ranking.
   Open Statistics and screenshot the Total column.
3. Desktop: `:desktop:createDistributable`, restart, open History of Forecasts, and screenshot the
   same two days. The ranking and ★ must match Android for the same site.
4. **Real-data check on 2026-11-14:** pull DBs (`scripts/backup_databases.py`) and compute
   2026-11-13 totals per source by hand in sqlite. Confirm they match both platforms' rows and the
   ★ choice.

## Docs

- `arch/daily-history-extremes.md`: add a short "Rain amount accuracy" section (definition of
  predicted/actual, paired hours, rain-day MAE, closest-per-day). Link it from `arch/ARCHITECTURE.md`.
- `arch/data-pruning.md`: rename the reader reference from `RainAccuracyCalculator` to
  `RainAccuracyRunner`.

## Open questions

1. `MIN_PAIRED_HOURS = 20` hours with data (not rain): is that strict enough, or should a day need
   all 24?
2. Rain-day threshold 0.01" (0.254 mm): the NWS measurable-rain convention. OK?
3. Viewed-day line: list every visible source inline (proposed) or only the ★ winner and actual?

## Implementation notes (2026-11-13)

1. Desktop English lives in `:desktop` `DesktopRainAccuracyText`, not `:shared`:
   `HardcodedUserFacingStringTest` rejects user-facing prose in `:shared`. `:shared` keeps the
   decisions (`RainAccuracySummary`: lines, order, ★).
2. A date/source with **0 paired hours** lists no Statistics row. The emulator showed a page of
   `pred — / act —` rows for days before its gauge data began. Scoring was unaffected.
3. The viewed-day line puts the source list on its own line: Android string resources collapse
   the planned triple space.
4. Statistics' day/night buckets also sum paired hours only, so day + night = Total.
5. The parity contract is `RainAccuracyHostContract` (`:shared` testFixtures), run by
   `RoomRainAccuracyHostContractTest` (`:app`) and `DesktopRainAccuracyHostContractTest` (`:desktop`).

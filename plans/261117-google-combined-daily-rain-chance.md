# Google: combined rain chance on the daily forecast view

Status: **approved 2026-11-17** — option 2 at ≥ 85%, popup combined too, "2.9in" correct

## Request

> make a plan to show the combined chance in the widget for google only, only for daily forecast view.

"Combined chance" = the chance of rain in at least one hour of the window, treating hours as
independent: `1 − ∏(1 − pₕ)`. Today the daily view shows the **max** hourly chance of the window
(8am–8pm day, 8pm–8am night).

## Evidence (desktop DB, 2026-11-17 13:06)

Google's hourly chances for 11/18 sit flat at 85% from 08:00 to 12:00 (5.8–12.6 mm/h, "Heavy Rain"). The day label
shows 85% (`hourlyDayPrecipMax`), while every other source says 89–100%. Across all ~3,000 stored Google
hourly rows the values are 5%-stepped, with peaks at 75% (193 rows) and 85% (43 rows); only 14 rows are above 85%.

The max and the combined values for each Google window, computed from the stored hourly rows (12 h each):

| Window | Max (shown now) | Combined |
|---|---|---|
| 11/17 day | 35 | 62.5 |
| 11/17 night | 75 | 99.3 |
| **11/18 day** | **85** | **100.0** |
| 11/18 night | 75 | 100.0 |
| 11/19 day | 75 | 99.3 |
| 11/19 night | 20 | 81.5 |
| 11/20 day | 15 | 27.8 |
| 11/20 night | 10 | 54.6 |
| 11/21 day | 15 | 78.8 |
| **11/22 day** | **10** | **71.8** |
| 11/22 night | 25 | 91.9 |
| 11/23 day | 5 | 18.5 |

### ⚠ Side effect to decide on before approval

Google puts a 10% floor on many dry hours. If hours are treated as independent, 12 × 10% gives
**72%**, so a dry day like 11/22 would jump from 10% to 72%. Hours of one weather
system are strongly correlated, so independence is the **upper bound** (max is the lower bound). Pure
independence fixes 11/18 but overstates dry days badly. Options:

1. **Pure independence, as requested.** Simple and matches the question asked, but dry days read 50–80%.
2. **Independence only when the window max is ≥ a threshold** (e.g. ≥ 50%). Otherwise keep the max.
   11/18 → 100, 11/22 stays 10. This is a hide/threshold rule that Claude is proposing, not one the user asked for.
3. **Independence over hours ≥ a floor** (ignore hours ≤ 10–15%, the drizzle floor). 11/22 → 10,
   This is also Claude's proposal.

Recommendation: decide after seeing the table. Claude does not pick one by default.

### Other knock-on effects (existing rules, unchanged)

- **Labels turn into amounts.** A future day label shows the amount instead of the % at ≥ 99%
  (`DailyRainLabels.buildDailyRainLabel`). It does the same at > 95% when the amount is above 1 in. 11/18 would read **"2.9in"** instead of
  "85%". Night labels behave the same way (`buildNightRainLabel`).
- **Label size, bar blue and icon follow the shown chance.** Those come from `ResolvedDailyPrecip`, so they would rise with the combined value.
- **Peak hour stays the first hour at the window's max.** The combined value has no single hour of its own.
- **Hourly view is unchanged.** It draws the per-hour values.

## Design

A shared rule in `:shared`, used by both platforms through the existing resolver.

1. **`DailyRainLabels.periodCombined(rows, date, zone)`**: the same 8am–8pm / 8pm–8am windows as
   `periodMaxima`, returning `1 − ∏(1 − p/100)` rounded to an Int. The option chosen above is applied here.
2. **Which sources use it:** a single place, `WeatherSource.GOOGLE_WEATHER` →
   `DailyChanceReducer.COMBINED`, and `MAX` for every other source. It lives in `:shared`, beside the resolver.
3. **Storage (needed for days beyond the routine 72 h hourly store):** add nullable
   `hourlyDayPrecipCombined` and `hourlyNightPrecipCombined` to `forecasts`. They are written by
   `DailyHourlySummaries.forDate` under the same window-coverage and `carryForward` rules as the max.
   The max columns stay as they are. They still drive the peak hour and the **source-comparison popup**
   (`DaySourceComparison`), which stays on max unless the open question below says otherwise.
   - A Room migration plus an exported schema, and the matching `DesktopWeatherDatabase` migration
     (recreate-table rule only applies to renames/drops; this is ADD COLUMN).
   - `ForecastEntity`, desktop entities/DAO read and write, and `ForecastTypes` mapping.
4. **Resolver:** `resolveLiveDayNightChance` / `DailyHourlySummaries.liveDayNight` choose the
   combined value for Google: the stored combined field for future days, and the stitched hours for today (as with the max).
   If the combined value is missing (rows written before the migration), it falls back to the max, so nothing goes blank.
5. **History:** `DailyHistoryMaintenance` freezes `forecastDayPrecipChance` through
   `resolveLiveDayNightChanceAtSite`, so past Google days record the combined value that was **shown**.
   Rows already frozen keep their max, and none are rewritten.
6. **Logging:** add a `Log.v` `GOOGLE_COMBINED_CHANCE date= day max→combined night max→combined`
   line in the resolver. It is a render-path trace, so it is logcat-only.

## Tests

- **Unit (`:shared`):** `periodCombined` for 11/18 (four hours at 85% → 100) and for a flat 10% day (the chosen option's
  result). A window with partial coverage gives null. Non-Google sources still show the max. The peak hour is unchanged.
- **Label:** a Google future day at combined ≥ 99 with an amount gives the amount string. These tests must fail on the
  current code (max 85 → "85%").
- **Integration:** the real Room DAO (Robolectric) and the desktop SQLite DAO go through `ForecastPersistRun` →
  `DailyColumnBuilder`, checking that the Google column shows combined, NWS shows max, and the comparison host still shows max.
- **Migration:** `WeatherDatabaseMigrationTest` (emulator) and a desktop migration test on real SQLite;
  `MigrationSqliteCompatGuardTest` must pass.
- **Device check:** emulator screenshot of the Google daily view plus the desktop app, with the 11/18 label checked.

## Docs

- `arch/ARCHITECTURE.md` daily-view rain section: chances are window max, except Google, which uses the combined value.
  Note that the comparison popup stays on max, or whatever the open question settles.

## Open questions

1. Which option, 1, 2 or 3, and if 2 or 3, what threshold or floor?
2. Should the source-comparison popup (opened from the daily view) also show the combined value for Google? It is documented as
   "matching the widget's daily view", so keeping it on max would break that match.
3. Should Google's 85% → 100% turn the 11/18 label into "2.9in" (the existing ≥ 99% amount rule), or should
   it stay a %?

## Decisions (user, 2026-11-17)

1. **Option 2, threshold 85%:** Google's window shows the combined value only when the window's max
   hourly chance is ≥ 85%; otherwise the max as before. The stored column holds the pure combined
   value; the threshold is applied at read time, so the column's meaning stays plain.
2. **Popup shows combined too:** the source-comparison popup reads the same resolved chance as the
   daily view (its documented contract).
3. **"2.9in" is correct:** the existing ≥ 99% amount rule stands.

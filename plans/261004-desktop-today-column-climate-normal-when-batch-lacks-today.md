# Desktop: today column shows the climate normal when the latest batch has no row for today

## Symptom

Desktop, Silurian, 23:49 local on 2026-10-04. The today column's forecast bar reads **76.2° / ~57°**
and the actual high was ~89°. NWS and Open-Meteo look right.

## Evidence

- A probe ran the real `DesktopWeatherRepository.loadCached()` + `DesktopDailyForecastModel.build()`
  against a copy of the live DB with the user's config. Today's daily row is
  `DailyForecast(date=2026-10-04, highTemp=76.16, lowTemp=56.85, condition=Historical Avg,
  isClimateNormal=true)`: the climate normal, not a forecast.
- Each source's latest batch, row for today:

  | Source | Latest batch | Today row |
  |---|---|---|
  | NWS | 23:46 | 92.0 / null (partial) |
  | Open-Meteo | 23:47 | 88.8 / null (partial) |
  | **Silurian** | 23:47 | **none**; first day is 10-05 |

- Silurian's last batch with a row for today is 14:08 (89.6 / null). The 19:01 and 23:47 batches
  start at tomorrow. Silurian's daily output apparently starts at the current UTC date, which
  passes local "today" at 17:00 PDT.
- The stored rows for today still include complete ones (newest: 04:37, 90.1 / 67.4).

## Root cause

`DesktopWeatherDao.getDailyForecasts` reads only the latest batch. Its today repair
(`PartialForecastDays.completeReplacement`) runs only when today's row is **present but
partial**. When the row is **absent**, today is missing from the list and
`DesktopWeatherRepository.appendClimateNormalGaps` → `ClimateNormals.fillGaps` fills it with the
normal (76.2 / 56.8).

Android doesn't have the bug. `DailyViewLogic.prepareGraphDays` uses
`weatherByDate[date] ?: forecastSnapshots[date]?.firstOrNull(…)`, which falls back to stored rows
when the latest batch lacks the date, and then applies the same complete-row swap. Desktop lacks
only that fallback.

## Fix (revised 2026-10-04 at the user's request: one shared rule, not a desktop patch)

_Original draft below; see Outcome for what was built._

### Original draft: desktop DAO only

In `getDailyForecasts`, when the latest batch has **no** row for today, take the same stored-row
candidates the partial-row branch already queries:

1. the newest complete row for today (`completeReplacement`), else
2. the newest stored row for today with at least one value. `DesktopDailyForecastModel` already
   fills the missing side from the day's hourly max/min (`todayForecastRange`).

Insert it in date order. If no stored row for today exists at all, behaviour is unchanged: the
climate normal remains the fallback.

Today only. Future days missing from a batch keep the climate-normal fill. The `forecasts`
table's same-day cutoffs are untouched.

## Tests

- `DesktopWeatherDao` (real sqlite):
  - latest batch starts at tomorrow + an older complete row for today → today = that row,
    not a normal;
  - only partial older rows → newest partial;
  - none → today absent (the repository then fills the normal);
  - a present partial row still swaps as before.
- Live: rerun the probe on Silurian. Today = 90.1 / 67.4, `isClimateNormal=false`; screenshot of
  the popup.

## Outcome (implemented 2026-10-05)

- `:shared` `PartialForecastDays.todayRow(batchRow, storedRows, …)`: complete batch row, else
  the newest complete stored row, else the newest one-sided row (batch first), else null.
- Desktop `DesktopWeatherDao.getDailyForecasts` calls it for a partial **or missing** today row
  and inserts a missing one in date order. A replaced batch row keeps its newer day/night rain
  chances, as before.
- Android `DailyTodayResolver.resolveTodayRow` (display source only, at the batch row's site)
  replaces `completeSameSiteReplacement`. `DailyViewLogic` uses it in both paths:
  - graph: the old any-source `forecastSnapshots[date]?.firstOrNull` fallback no longer applies
    to today;
  - text: now also falls back when today is missing.
- Tests: 5 in `PartialForecastDaysTest`, 4 in `DailyTodayResolverTodayRowTest`, and 3 new DAO
  cases in `DesktopWeatherDaoTest`. A mutation restoring partial-only handling fails the 2
  missing-row cases. The two older today tests now write at 05:00 local. They had failed every
  evening because the same-day cutoffs stored nothing at test time.
- Full suite: 4586 passed (00:0x local). A run at 23:59 failed 5 storage tests that pass on
  either side of midnight with and without this change. They depend on the clock and are
  unrelated.
- Live: desktop restarted on the new build; debug build on emulator-5556 (Silurian) and the
  phone (NWS). Both render today normally. The Silurian missing-row case only recurs after
  17:00 PDT; check the desktop today column then.
- Seen, not fixed: desktop decimal retention for "today" (`upsertForecasts` rounding) keys on
  the **UTC** date, so local today's values are rounded to whole degrees after 17:00 PDT.

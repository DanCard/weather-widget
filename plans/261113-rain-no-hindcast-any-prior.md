# Rain chance and amount: no hindcast while any prior exists

**User, 2026-11-13** (after `a02afee7a`, plans/261113-no-hindcasts-unless-prior-missing.md):
"When did meteo change today rain chance to 100%? … Can you change it for all APIs? Don't allow
hindcast for today for rain chance and rain amount?" Answers: **"Rain: any prior wins"** and
**"Desktop: recompute all"**.

## Evidence

Desktop, Open-Meteo, 2026-11-13: the 12:10 fetch said 1 pm = 90% and 12 pm = 91%. The **16:59**
fetch rewrote 1 pm to **100%** (a hindcast about 4 h after the hour), and `FREEZE_RAIN_CHANCE
day=91->100` archived it. The day window closed at 20:00, before the `a02afee7a` install (21:26), so
100% is frozen. The Pixel had no fetch between 11:05 and 21:07 and got 100% at 21:07.

Under the 6 h guard, the phones still show hindcasts for rain wherever the only prior was more than
6 h old. Emulator NWS: 2 pm–9 pm used 21:43 because the only prior was 07:04.

## Where today's rain comes from (all sources)

| Value | Source | Hindcast path today |
|---|---|---|
| Today's day/night chance, live and archived | max of the stitched hours (`DailyHourlySummaries.liveDayNight`, `resolveLiveDayNightChanceAtSite`) | through the hourly pick only (6 h guard) |
| Today's day amount, live | 8 am–8 pm sum of the stitched hours (`DailyRainLabels.dayForecastAmountMm`) | through the hourly pick only |
| Archived `forecastPrecipAmountMm` | the newest daily row's provider total, until midnight (`DailyHistoryFreeze.merge`) | **yes, directly**; not displayed today |
| Rain graph | stitched hours | through the hourly pick only |

## Change

1. **`:shared` stitcher, rain fields:** for a started hour, `precipProbability` and
   `precipAmountMm` come from the newest pre-hour row carrying them, **however old**. They come from
   the earliest post-hour row only when no pre-hour row has them. Temperature, condition and cloud keep
   the 6 h `HindcastGuard`. With the slider at 0 (guard off), rain also takes the newest fetch.
   Implemented as `SettledForecastPick.backfillOrder(..., maxPriorAgeMs = Long.MAX_VALUE)` for the
   two rain fields.
2. **Dimming:** the stitched row records which fetch its rain came from (`precipFetchedAt`; in-memory,
   not a DB column; Room `@Ignore` on `HourlyForecastEntity`). The rain graphs on both platforms judge
   staleness by it, so an old rain prior is drawn dimmed. The temperature and cloud graphs are unchanged.
3. **Archived amount:** `forecastPrecipAmountMm` becomes the 00–24 sum of the stitched hours, so it
   follows the same rule. It falls back to the provider total only when the hours don't cover the day.
4. **Retention:** no change. Rule 2 already keeps the newest pre-hour and earliest post-hour row per
   coalesced field, and `precipProbability` and `precipAmountMm` are coalesced fields.
5. **One-off desktop repair (today, 2026-11-13, every source):** back up `weather.db`, then recompute
   today's archived `forecastDayPrecipChance`, `forecastDayPrecipPeakAt` and `forecastPrecipAmountMm`
   from the stored hourly rows under the new rule. Show the before/after table and write it. The
   phones are not repaired: their pre-hour rows were pruned.

## Tests (each fails on `a02afee7a`)

1. Stitcher: a 9 h-old pre-hour rain chance beats a hindcast while temperature takes the hindcast;
   no prior → earliest post-hour; guard off → newest; `precipFetchedAt` names the rain row.
2. `DailyHistoryRun`: Open-Meteo 11-13 replay → day chance 96%, not 100%; archived amount = stitched sum.
3. Rain graph stale flag uses `precipFetchedAt` (shared helper, both platforms).
4. `./scripts/unit-tests.sh`.

## Verification

Emulator: the NWS rain graph for 2 pm–9 pm shows the 07:04 values (54/36/60/61/52/38/13/10%),
dimmed. Desktop: after the repair, today's Open-Meteo column/history reads 96%. Docs:
`arch/hourly-forecast-update-cadence.md`.

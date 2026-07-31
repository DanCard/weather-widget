# Samsung showed today's high as 84° — incomplete-today repair grabbed another town's row

**Date:** 2026-07-30
**Plan:** none (live debug from a device report)

## Outcome

Reported as "Samsung: forecast high for today of 84, seems wrong — doesn't match hourly forecast and
doesn't match other devices or desktop." All three complaints were accurate and each one was a
separate clue.

Mid-investigation the widget "reverted to the correct high" on its own. That was **not** a fix — a
different render path had repainted. `TODAY_BAR_DEBUG` shows `fHigh` flapping between 81 and 84
depending on which path last painted, so it would have recurred on the next startup repaint.

## Diagnosis

The Samsung travelled during the day, so NWS forecasts for today were fetched at three coordinates.
The Pixel only ever held the one site, which is why only the Samsung disagreed:

| lat/lon | high/low | batch |
|---|---|---|
| `37.417,-122.089` (widget's site) | 81 / **NULL** | 21:34 (newest) |
| `37.377,-122.075` | **84 / 57** | 14:34 |
| `37.422,-122.073` | 77 / 58 | 12:38 |

At 20:34 NWS returned its usual **evening drop** for today — a high-only row with `lowTemp = NULL`
(once the daytime period has passed, the grid returns a low-less period). That triggers the repair
in `DailyViewLogic.prepareGraphDays` / `prepareTextDays`, which swaps in the most recent *complete*
row from `forecastSnapshots[today]`:

```kotlin
forecastSnapshots[date]?.filter {
    it.source == weather.source && it.highTemp != null && it.lowTemp != null
}?.maxByOrNull { it.fetchedAt }
```

It matched on **source only, never on site**. The snapshot pool is built from the
`getAllForecastsInRange*` queries — the ones `ForecastDao.kt:488` explicitly documents as
*deliberately uncollapsed*, because they feed snapshot/evolution views — so it spans the whole
~7 mi `LocationMatch` box. The 14:34 batch from the neighbouring town was the newest complete NWS
row, so it won and replaced **both** numbers with that town's `84/57`.

This is coordinate fragmentation, not stale data: same class as
`snapshot_paths_must_select_a_site` and `location_box_admits_stale_nearby_site`, on a new path.

### Why it flapped

| Render origin | `weatherList` | Repair fires? | `fHigh` |
|---|---|---|---|
| `PROVIDER_ON_UPDATE` (startup coordinator) | collapsed, 9 rows | yes — today's row is incomplete | **84** |
| `WORKER_FETCH` | uncollapsed, 107 rows | no — its today-row happens to be complete | **81** |

The underlying incomplete row was still present with `lowTemp` NULL after the apparent
self-correction.

### Evidence that ruled out the alternatives

- `sHigh=81` stayed correct throughout while `fHigh` moved — so the snapshot selector was fine and
  only the forecast-row selection was wrong. This narrowed the search from the whole daily pipeline
  to one assignment.
- Hourly max for today was **83** across every site fragment, so 84 could not have come from
  `dashedLineHigh = fallbackWeather?.highTemp ?: hourlyMax`'s hourly branch.
- `84/57` matched one DB row *exactly*, proving a whole row was substituted rather than a value
  recomputed.
- Replaying the production collapse query against the pulled DB returned `81/NULL` for the widget's
  own site — confirming `collapseSites` was working and the corruption happened after it.

## What changed

- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` — new private helper
  `completeSameSiteReplacement()`, which constrains candidates with `LocationMatch.sameSite(...)`
  against the incomplete row's own coordinates. Both repair sites (graph mode and text mode, which
  had duplicated the filter) now route through it.
- `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt` — two regression tests
  reproducing the exact on-device shape: same-site `81/null` incomplete, a newer off-site `84/57`,
  and an older same-site `81/57`. Asserts the same-site row wins for both high and low.

Desktop has no equivalent repair, which is why it was already correct. No desktop change needed.

## Verification

- Both new tests **fail** without the source change (verified by stashing it) and pass with it.
- Full `com.weatherwidget.widget.handlers.*` unit suite green.
- Pre-existing evening-drop tests (`DailyViewLogicTest`, `DailyViewHandlerTodayDropIntegrationTest`)
  unaffected — their fixtures use a single site, so `sameSite` is satisfied.

Not yet installed to devices.

## Watch for

Any new code that filters an uncollapsed snapshot/forecast pool by `source` must also filter by
`sameSite`. **`source`-only + `max(fetchedAt)` is the bug signature.**

# Fix the cross-site daily-actuals clobber

**Date:** 2026-08-22
**Defect:** #1 of the two recorded in memory `location_move_collapses_today_actuals`
**Status:** plan, not yet implemented

## The bug

`DailyActualsStore.persistExtremes` computes a blend anchored at ONE site but writes the result
onto EVERY `daily_history` fragment within the coarse ~7 mi proximity box.

```
app/src/main/java/com/weatherwidget/data/repository/DailyActualsStore.kt:282
val existingHistory = dailyHistoryDao
    .getExtremesInRange(dateMillis, dateMillis, latitude, longitude)   // LocationMatch.ROOM_WHERE, ±0.1°
    .groupBy { it.source }
...
fragments.forEach { existing ->                                        // ← every fragment in the box
    val merged = existing.copy(computedLowTemp = new.computedLowTemp, …)
    updateBlendRow(merged, existing, new.updatedAt, date)
}
```

The read that produced `new` is site-collapsed (`ObservationDao.getObservationsInRange` →
`LocationMatch.selectNearestSite`). The write is not. So a recompute anchored at site B overwrites
site A's row with values that were never measured at A.

### Observed on Samsung (SM-F936U1), 2026-08-22

GPS promoted `37.424,-122.088` ("Amphitheatre Parkway") at 18:40:58. Today's Tomorrow.io
observations at that stub start at **12:00**, so its blended low was the noon reading, 66.52°.
One minute later the recompute wrote that onto the *home* row:

```
18:41:58  DAILY_HISTORY_OVERWRITE date=2026-08-22 src=TOMORROW_IO
          at=37.41682052612305,-122.08903503417969   low=57.03->66.52
18:41:31  DAILY_HISTORY_OVERWRITE date=2026-08-21 src=TOMORROW_IO
          at=37.41682434082031,-122.08899688720703   low=60.66->67.69
```

`37.41682…` is home — 40 Tomorrow.io rows spanning 00:00–19:26, true low 57.03. NWS was damaged
the same way, just less visibly (`57.84 -> 57.68`), because its stub still held overnight rows.

The corruption self-repaired at 19:26:29 only because plugging in fired
`GPS_RESAMPLE trigger=power_connected` and moved the anchor back home.

## Scope — read this before assuming it fixes the screenshot

This fix stops **persistent** corruption. It does **not** make the Today column correct while a
bad site is active.

With the guard in place, a recompute anchored at the stub finds no `sameSite` fragment, falls into
the existing `if (fragments.isEmpty())` branch, and **inserts a new row at the stub** carrying
66.52. Reads collapse to the nearest site, so the widget still shows 66.52 until the device returns
home — at which point it now shows 57.03 *instantly*, because the home row was never damaged.

Today: damage outlives the excursion, and the good value is gone for good.
After this fix: the wrong value is confined to the wrong site, and recovery is immediate.

Making the *display* correct during an excursion is defect #2 (promotion gating) and/or a read-side
coverage guard. Both are out of scope here and get their own plan.

## Change

### 1. Constrain the fragment loop to the anchor site

In `persistExtremes`, filter fragments through `LocationMatch.sameSite` against the
`(latitude, longitude)` the blend was anchored at:

```kotlin
val fragments = existingHistory[new.source].orEmpty()
    .filter { LocationMatch.sameSite(it.locationLat, it.locationLon, latitude, longitude) }
```

`sameSite` already exists (`shared/src/main/kotlin/com/weatherwidget/data/local/LocationMatch.kt:48`,
`SAME_SITE_TOLERANCE_DEG = 0.002`, ~200 m) and is the documented in-memory counterpart to the
coarse box. Sub-precision jitter fragments of the anchor still merge — which is the point; only
genuinely different markers are excluded.

Keep the box query as-is. It is the correct coarse pre-filter; the defect is the missing collapse
after it, exactly as with `today_incomplete_repair_cross_site`.

### 2. Record why, in the code

`persistExtremes`' KDoc already explains the optimistic-update race at length. Add a short paragraph
naming this second hazard so the next reader does not "simplify" the filter away: the pool `new`
was computed from is site-collapsed, so writing it to an uncollapsed fragment list mixes sites.

### 3. Tolerance margin — verify, do not assume

The two excursion sites separate from home by thin margins:

| site | Δlat from home | Δlon from home | separates? |
|---|---|---|---|
| `37.4242298,-122.0883022` (Amphitheatre) | 0.0074 | 0.0007 | yes, comfortably |
| `37.4162313,-122.0866975` (Permanente Creek) | 0.0006 | **0.0023** | yes, but by 0.0003 |

The Permanente Creek site clears `SAME_SITE_TOLERANCE_DEG` on longitude alone. That is correct
behaviour for this fix, but it means the guard's effectiveness is sensitive to the constant. Do not
widen `SAME_SITE_TOLERANCE_DEG` as part of this work; if it ever needs widening, this call site
needs re-examining.

## Tests

No `DailyActualsStoreTest` exists today — these are new.

### Integration test (2+ classes: `DailyActualsStore` + `DailyHistoryDao` on in-memory Room)

`app/src/test/java/com/weatherwidget/data/repository/DailyActualsStoreCrossSiteTest.kt`

1. **Regression, mirroring the incident.** Seed `daily_history` with a home row
   (`37.4168,-122.0890`, `computedLowTemp = 57.03`) and observations for home spanning 00:00–19:00.
   Seed a stub site (`37.4242,-122.0883`) with observations from 12:00 only. Run the recompute
   anchored at the **stub**. Assert the home row's `computedLowTemp` is still `57.03`.
   *Prove the test fails without the filter* — revert the one-line change and confirm it goes to
   the stub's noon value.
2. **Same-site jitter still merges.** Seed a fragment 0.0001° from the anchor. Assert it is updated,
   not orphaned into a second row.
3. **Insert path at a genuinely new site.** Anchored at the stub with no stub row present, assert
   exactly one new row is inserted at the stub and the home row is untouched — this pins the
   documented scope limit above so nobody later reads it as a bug.
4. **Freeze guard unaffected.** A past day with `actualsSource = NWS_STATION_PULL` at the anchor
   site stays frozen; the filter must not change that interaction.

### Unit test

`LocationMatchSameSiteTest` already covers the predicate. Add the two real coordinate pairs from
the incident table above as named cases, so the thin Permanente Creek margin is asserted rather
than incidental.

## Verification on device

Recomputes run on widget loads, so no GPS simulation is needed to see the guard hold:

```bash
./gradlew installDebug
adb -s RFCT71FR9NT logcat -c
# open the daily view a few times, then:
DB=backups/<newest>_sm-f936u1_RFCT71FR9NT/databases/weather_database
sqlite3 -line "$DB" \
  "SELECT datetime(timestamp/1000,'unixepoch','localtime'), message
   FROM app_logs WHERE tag='DAILY_HISTORY_OVERWRITE' ORDER BY timestamp DESC LIMIT 20;"
```

Every `at=` coordinate in the output must be the site the device is actually at. An `at=` naming a
site the device left hours ago is the defect recurring.

Per-site coverage check that made the diagnosis (keep it handy):

```sql
SELECT locationLat, locationLon, COUNT(*), ROUND(MIN(temperature),2),
       datetime(MIN(timestamp)/1000,'unixepoch','localtime')
FROM observations
WHERE api='TOMORROW_IO' AND timestamp >= <local midnight ms>
GROUP BY 1,2;
```

## Note on the time filters

`strftime('%s','2026-08-22 18:00:00')` parses the literal as **UTC**, so naive local-time filters
on `app_logs.timestamp` silently shift by the UTC offset (−7h here) and return the wrong window.
Use `strftime('%s','… ','utc')`, or just `ORDER BY timestamp DESC LIMIT n`.

## Related

- Memory: `location_move_collapses_today_actuals` (both defects, with this incident appended)
- Memory: `today_incomplete_repair_cross_site` — same shape, filtered by `source` but not `sameSite`
- Memory: `snapshot_paths_must_select_a_site`, `shared_location_match_predicate`
- Defect #2 (`LocationHandoffPolicy.evaluateCandidateUsability` is observation-blind) — separate plan

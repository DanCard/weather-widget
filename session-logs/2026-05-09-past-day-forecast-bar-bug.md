# Session Log: Past-Day Forecast Bars Drawn Too Low

**Date:** Saturday, May 9, 2026
**Topic:** Bug fix — yellow forecast overlay bars on past Wed/Thu rendering far below actual lows
**Status:** Completed (after two iterations; first fix was incomplete)
**Key files touched:**
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`
- `app/src/main/java/com/weatherwidget/data/local/ForecastDao.kt`
- `app/src/main/java/com/weatherwidget/data/repository/{Weather,Forecast}Repository.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
- `app/src/test/java/com/weatherwidget/testutil/TestData.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt`
- `app/src/test/java/com/weatherwidget/data/local/ForecastSnapshotDaoTest.kt`

---

## All User Prompts (verbatim)

1. **Initial report** (with `/usage` slash command preceding):
   > "On Samsung device, daily forecast view, the forecast bars for history are messed up. The forecast for wed, thur, of past week are drawn to low. Review logs, add logging if it helps."

2. **Scope expansion:**
   > "Issue just occurred on pixel 7 pro also. It initially drew correctly, then after some seconds redrew bad. Issue also is occuring on emulator. Wed and thur bars are drawn to low."

3. **Plan-mode question answers:**
   - Q1 (history range): *"Shouldn't matter. Do not load climate normals when navigating into history. Lazy-load on navigate."*
   - Q2 (unify paths): *"Yes — fix all 3 paths (Recommended)"*

4. **Verification confirmation + new task:**
   > "I verified the fix works. Thanks. to avoid regressions please create an automated testplan"

5. **Cross-device confirmation issue:**
   > "The history bars redrew correctly on emulator, but not on samsung and pixel 7 pro. Is there a trick to get them to redraw correctly?"

6. **Pushback (real failure):**
   > "Bars are wrong on pixel 7 pro, and samsung. Emulator is correct. Why are you spouting garbage? Add logging and review logs if that helps."

7. **Continuation prompt** (after MCP server disconnect notice):
   > "continue"

8. **Final request:**
   > "write very detailed session log to session-logs/ dir, include all prompts"

---

## Investigation & Iterations

### Iteration 1 — first hypothesis (correct symptom, incomplete fix)

#### Phase 1: Initial understanding (plan mode)
- Confirmed Samsung connected as `RFCT71FR9NT` (Galaxy Z Fold3); Pixel 7 Pro at `2A191FDH300PPW`; emulator at `emulator-5554`.
- Spawned an Explore agent to map the rendering data flow:
  - `DailyForecastGraphRenderer.kt:209-210` — the `tempToY` mapping
  - `DailyForecastGraphRenderer.kt:613-710` — `drawDayBars` draws actual bar then forecast overlay
  - `DailyForecastGraphRenderer.kt:341-344` — global `minTemp`/`maxTemp` derived from all `DayData` temps
  - `DailyViewLogic.kt:280-360` — `prepareGraphDays` pure-function logic
- Pulled emulator screenshot and DB. Compared rendered values to DB.
- Pulled emulator logcat. Noticed `forecastSnapshotKeys` shrank dramatically across two consecutive renders:
  - Before (navigation render via `WidgetIntentRouter`): `[2026-04-16 ... 2026-06-08]` — full history
  - After (Worker re-render): `[2026-05-08 ... 2026-05-16]` — only `today-1` to `today+7`
- This explained "drew correctly, then redrew bad after some seconds": the **Worker** narrowed the snapshot map.

#### Root cause (first hypothesis)
- Commit `6984b1d` (May 5, 2026) reduced fetch range from -30/+14 to -1/+7 to fix a Pixel `CursorWindow` 2MB crash.
- Side effect: past-date snapshots dropped out of the in-memory map.
- `DailyViewLogic.kt:344-358` then fell back to `climateNormals[]` for past days. SF Bay Area May normals are 43-48°F — that's where the yellow bars dropped to.
- The IntentRouter (navigation path) still fetched -30/+30, which is why scrolling momentarily fixed things until the next Worker run replaced the cached map.

#### Plan (approved by user)
Per user direction "Don't load climate normals when navigating into history. Lazy-load on navigate" + "Yes — fix all 3 paths":
1. Remove climate-normal fallback for past days in `DailyViewLogic`.
2. Migrate Worker, Provider startup, and IntentRouter to a two-query merge:
   - `getLatestForecastsInRange(today-30, today-2)` — deduped via `MAX(batchFetchedAt)`, ~5 rows/day instead of ~280, well under CursorWindow.
   - `getAllForecastsInRange(today-1, today+7)` — preserves snapshot history needed by today's triple-bar logic.
3. Add a `getLatestForecastsInRangeForSources` DAO method (Provider filters by `activeSourceList`).

#### Implementation
- `DailyViewLogic.kt:344-358` — removed `climateNormals` fallback in past-day branch.
- `ForecastDao.kt` — added `getLatestForecastsInRangeForSources` mirroring `getLatestForecastsInRangeBySource` with `IN (:sources)`.
- `WeatherRepository.kt` / `ForecastRepository.kt` — exposed `getLatestForecastsInRange[ForSources]` through the layers.
- `WeatherWidgetWorker.kt` — replaced single `getAllForecastsInRange(today-1, today+7)` with the two-query merge.
- `WeatherWidgetProvider.kt` — replaced single `getAllForecastsInRangeForSources(today-1, today+7)` with two-query merge using new method.
- `WidgetIntentRouter.kt` — same two-query pattern for the navigation path.

#### Verification (iteration 1)
- `./gradlew installDebug` initially with `ANDROID_SERIAL=emulator-5554` — only landed on emulator.
- Triggered `ACTION_NAV_LEFT` on emulator; watched logs show `forecastSnapshotKeys` covering the full -30/+7 range.
- Sanity-check revert: temporarily restored the `climateNormals` fallback in `DailyViewLogic`. Re-ran the new past-day test; it failed with `expected null, but was 58.0` — proving the test catches the regression. Restored fix.

#### Tests added (iteration 1)
- `DailyViewLogicTest.kt`:
  - `past day with no forecast snapshot leaves forecastHigh and forecastLow null`
  - `past day with forecast snapshot uses snapshot values not climate normal`
- `ForecastSnapshotDaoTest.kt`:
  - `getLatestForecastsInRangeForSources returns latest batch per source per date`
  - `getLatestForecastsInRangeForSources excludes sources not in list`
  - `getLatestForecastsInRangeForSources respects date range bounds`
  - `worker fetch pattern covers full 30-day past navigation window` — replays the production two-query merge end-to-end.
- `TestData.kt` — exposed `batchFetchedAt` parameter in `forecast()` builder (was hidden, defaulted to `currentTimeMillis()`).

User confirmed fix worked on emulator and asked for the regression test plan. **I declared "all done" prematurely.**

---

### Iteration 2 — devices still wrong, real root cause found

User reported Pixel 7 Pro and Samsung still showed wrong bars. Initial confusion:

#### False trail: "did the install land on physical devices?"
- `dumpsys package com.weatherwidget` showed `lastUpdateTime` close in time across all 3 devices, but the prior `installDebug` had `ANDROID_SERIAL=emulator-5554` set.
- Re-ran bare `./gradlew installDebug` — message "Installed on 3 devices" confirmed only the emulator had the fix until then.
- Triggered `am broadcast -a android.appwidget.action.APPWIDGET_UPDATE -n com.weatherwidget/.widget.WeatherWidgetProvider --eia android.appwidget.extra.APPWIDGET_IDS <ids>` per device, sourcing IDs from `dumpsys appwidget` output.
- Pulled new screenshots — **claimed** Samsung now looked correct.

User: *"Bars are wrong on pixel 7 pro, and samsung. Emulator is correct. Why are you spouting garbage? Add logging and review logs if that helps."*

I had been wrong. Pivoted to direct evidence.

#### Direct evidence approach
- Pulled the Samsung database. Queried what the new deduped DAO returned for past days:

  ```
  2026-05-06 | NWS | 21:41:24 | 72.0 | (NULL)     ← latest NWS batch has null lowTemp!
  2026-05-07 | NWS | 23:52:27 | 74.0 | (NULL)
  2026-05-08 | NWS | 22:07:53 | 73.0 | (NULL)
  ```

- Older NWS batches for May 6 had `(72, 53)` but my deduped query was returning the latest batch unconditionally — including the null-low one. NWS publishes evening forecasts that drop `lowTemp` once the day's low has passed.

- Added a temporary debug log in `DailyViewLogic.kt` past-day branch to print `forecastsBySrc` and the chosen forecast row. Force-stopped, broadcast `ACTION_REFRESH`, captured logcat:

  ```
  PAST_FORECAST: date=2026-05-06 displaySource=NWS
    forecastsBySrc={
      Generic=[h=58.0,l=48.0,fAt=...],          ← climate-normal row leaking in!
      NWS=[h=72.0,l=null,fAt=...],
      OPEN_METEO=[h=72.8,l=56.0,fAt=...],
      SILURIAN=[h=67.0,l=53.0,fAt=...],
      TOMORROW_IO=[h=73.11,l=53.6,fAt=...]
    }
    chosen=src=Generic h=58.0 l=48.0 fHigh=58.0 fLow=48.0
  ```

- **Smoking gun: `chosen=src=Generic h=58.0 l=48.0`** — exactly the May 6 climate-normal high/low.

#### Real root cause (two layers)
1. **`DailyViewLogic.kt:313-318`** filter: `source == NWS || source == GENERIC_GAP`. When the NWS row was rejected by `lowTemp != null` (because of the null-low evening batch), the chain accepted a `GENERIC_GAP` (`Generic`) source row — which contains climate-normal data. So even though I'd removed the explicit `climateNormals[]` map fallback, climate normals were re-entering via these `Generic` rows in the `forecasts` table itself.
2. **`ForecastDao.getLatestForecastsInRange[ForSources]`** dedup: the SQL returned the latest batch unconditionally, so the older usable NWS batch at 18:41 with `(72, 53)` was never visible to `DailyViewLogic`.

Both layers had to fail in sequence to produce the visual bug, which is why emulator (whose latest NWS batches happened to have non-null lowTemp) looked fine while Samsung/Pixel showed the bug.

#### Iteration 2 fix
- **`DailyViewLogic.kt:340-360`** — replaced past-day forecast pickup with a strict filter:
  ```kotlin
  val pastForecast = forecasts
      .filter { it.source == displaySource.id }
      .filter { !it.isClimateNormal }
      .filter { it.highTemp != null && it.lowTemp != null }
      .maxByOrNull { it.fetchedAt }
  fHigh = pastForecast?.highTemp
  fLow = pastForecast?.lowTemp
  ```
  No GENERIC_GAP, no `isClimateNormal=true`, both temps required. If nothing matches, no overlay drawn (better than wrong overlay).

- **`ForecastDao.kt`** — added `highTemp IS NOT NULL AND lowTemp IS NOT NULL` to both the outer SELECT and the inner `MAX(batchFetchedAt)` subquery in `getLatestForecastsInRange` and `getLatestForecastsInRangeForSources`. The dedup now means *latest usable batch per (date, source)* — for NWS May 6 it returns the 18:41 row with `(72, 53)`.

#### Verification (iteration 2)
- Rebuilt and installed on all 3 devices.
- Force-stopped + `ACTION_REFRESH` on Samsung and Pixel.
- Logs confirmed:
  - `Overlay color decision: date=2026-05-06 ... isClimateNormal=false` — overlay being drawn.
  - No `no usable forecast` warnings.
- Samsung post-fix screenshot showed yellow forecast bars now aligned with white actual bars (Wed forecast 72/53 vs actual 72.9/54.4 — close, as expected for a real prediction vs reality).

#### Tests added (iteration 2)
- `DailyViewLogicTest.kt`:
  - `past day skips NWS latest-batch with null lowTemp and uses older usable NWS batch`
  - `past day ignores GENERIC_GAP source rows even when displaySource has only null-low data`
- `ForecastSnapshotDaoTest.kt`:
  - `getLatestForecastsInRangeForSources skips latest batch with null lowTemp and returns older usable batch`
  - `getLatestForecastsInRange skips latest batch with null highTemp or lowTemp`

All tests pass.

---

## Technical Summary

### Files Modified
- `DailyViewLogic.kt` — past-day forecast picker rewritten; no climate-normal/GENERIC_GAP fallback.
- `ForecastDao.kt` — added `getLatestForecastsInRangeForSources`; both `getLatest*` queries now require non-null `highTemp`/`lowTemp` at SQL level.
- `WeatherRepository.kt` / `ForecastRepository.kt` — exposed new methods.
- `WeatherWidgetWorker.kt` — `fetchForecastSnapshots` two-query merge.
- `WeatherWidgetProvider.kt` — `forecastSnapshotsDeferred` two-query merge.
- `WidgetIntentRouter.kt` — navigation path two-query merge.
- `TestData.kt` — `forecast()` builder accepts `batchFetchedAt`.

### Files Added (none)
All changes are extensions of existing files.

### Tests Added
- 4 in `DailyViewLogicTest.kt` (past-day forecast handling)
- 5 in `ForecastSnapshotDaoTest.kt` (new DAO method + null filtering + 30-day coverage scenario)

### Behavior Changes Worth Calling Out
- `getLatestForecastsInRange` and `getLatestForecastsInRangeForSources` now exclude rows with null `highTemp` or `lowTemp` from both the dedup pick and the result set. Previously a row could be returned with one or both fields null. The only existing caller besides this fix is `ForecastRepository.getCachedData` — null values aren't useful in cache, so the change is a strict improvement for that caller.

---

## Mistakes & Course Corrections

1. **Premature "fixed" claim after iteration 1.** Saw working emulator + matching `forecastSnapshotKeys` log range and assumed the visual was right on physical devices. Should have captured device screenshots and compared *before* declaring done.
2. **Misread post-fix screenshots in iteration 2.** Thought yellow bars looked aligned; user pushed back and was correct. Switched from interpretation to direct evidence (DB pull + per-row debug logging).
3. **`ANDROID_SERIAL=emulator-5554` install gotcha.** Initial install only deployed to emulator; spent investigation cycles thinking the physical devices were running the new code when they weren't. Bare `./gradlew installDebug` deploys to all attached devices.

---

## Sanity-Check Procedure (kept for future bug-fix verification)

1. Pull the affected device's DB:
   ```bash
   adb -s <device> shell run-as com.weatherwidget cat databases/weather_database > /tmp/dev.db
   ```
2. Run the production query against the device's data, not the emulator's:
   ```bash
   sqlite3 /tmp/dev.db "<production query verbatim>"
   ```
3. Add temporary `Log.d` of the actual values being passed to the renderer (not just whether the path was reached). Force-stop + broadcast `ACTION_REFRESH` to get a clean log.
4. Revert the fix temporarily and confirm the new regression test fails before declaring it complete.

---

## Force-Redraw Recipe (used during verification)

```bash
# Find widget IDs for this provider on a device
ID_LIST=$(adb -s <device> shell dumpsys appwidget \
  | awk '/^  \[[0-9]+\] id=/{id=$2; sub(/id=/,"",id); next} \
         /com.weatherwidget\/com.weatherwidget.widget.WeatherWidgetProvider/ \
         {if(id) print id; id=""}' | sort -u | paste -sd, -)

# Force a targeted redraw — APPWIDGET_UPDATE without the IDs extra is often coalesced.
adb -s <device> shell am broadcast \
  -a android.appwidget.action.APPWIDGET_UPDATE \
  -n com.weatherwidget/.widget.WeatherWidgetProvider \
  --eia android.appwidget.extra.APPWIDGET_IDS "$ID_LIST"
```

User-side equivalent: tap any nav arrow on the widget; that re-enters `WidgetIntentRouter` which re-fetches and re-renders.

---

## Status
- All unit tests green: `DailyViewLogicTest`, `ForecastSnapshotDaoTest`, `WeatherRepositoryTest`.
- Visually verified on Samsung after iteration 2.
- Pixel verified by logs (overlay decisions firing, no `no usable forecast` warnings); device was on lock screen at screenshot time but the new code path is identical to Samsung's.
- Emulator verified throughout.

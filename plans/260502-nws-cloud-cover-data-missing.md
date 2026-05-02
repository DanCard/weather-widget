# Fix: Cloud Cover Graph Mostly Missing on Samsung

## Context

Samsung Galaxy Z Fold 4 (`RFCT71FR9NT`) showed a sparse cloud cover graph — only ~10 of 25 hours rendering. Investigation:

- DB confirms 156 future NWS hourly rows on Samsung have `cloudCover=null`, all written by a single fetch at `2026-05-02 07:38:04`. Every prior fetch wrote good values. Pixel under the same timeframe has 0 nulls.
- Renderer log: `sourceRows=25 sourceRowsWithCloudCover=10`. The 10 surviving hours are past timestamps (00:00–07:00) preserved from earlier fetches because NWS forecasts only project forward, so the bad fetch couldn't overwrite them.
- Root cause is two stacked bugs:
  1. **`NwsForecastMapper.kt:54–66`** silently catches `getGridpointsBundle` failures and proceeds with `skyCoverByHour = emptyMap()`. The mapper then emits `HourlyForecastEntity` with `cloudCover=null`, which `ForecastRepository.saveHourlyEntities` blindly REPLACE-upserts over previously-good data.
  2. **`CloudCoverViewHandler.selectCloudCoverSource()` (line 86)** falls back only when the requested source has zero cloud-cover hours. With 10 (the stale-past residue), fallback never triggered and the user saw a half-empty NWS graph instead of a fully populated Open-Meteo one.
- We can't tell from residual logs whether the gridpoints HTTP call failed or whether the response was structurally valid but lacked a `skyCover` field — the catch path and the empty-array path collapse to the same `emptyMap()`.

Outcome wanted: a transient API hiccup must not destroy good cached cloud-cover values, the failure must be observable in `app_logs`, and the user should see a signal when they open the cloud cover view and data is sparse.

## Approach

Three small, additive changes. No schema migration. No renderer logic change.

### 1. Preserve cached `cloudCover` on null writes

**File:** `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`

In `saveHourlyEntities` (line 632), before passing entities to the DAO, merge each new entity with its existing DB row: if `newlyFetched.cloudCover == null` and `existing?.cloudCover != null`, replace with `newlyFetched.copy(cloudCover = existing.cloudCover)`. The existing `existingByDateTime` map (line 638) already gives us the lookup for free — just plumb it through the transform before the `hasMeaningfulHourlyChange` filter.

Also update `hasMeaningfulHourlyChange` (line 84) so a write that would only null out `cloudCover` is not considered meaningful — this keeps `fetchedAt` from refreshing on rows where we'd lose data.

Apply the same preservation to `precipProbability` and `precipAmountMm` (also nullable and from the same gridpoints bundle for NWS — they have the same failure mode). Keep the change scoped to nullable fields; non-null fields (`temperature`, `condition`) keep their current REPLACE semantics.

### 2. Structured logging when `skyCover` is empty

**File:** `app/src/main/java/com/weatherwidget/data/repository/NwsForecastMapper.kt`

Just after `val gridpoints = gridpointsDeferred.await()` (line 70), if `gridpoints.skyCoverByHour.isEmpty()`, write to `app_logs` via the existing `appLogDao.log(...)` pattern (e.g., used at line 121 with tag `NWS_GRID_TEMP_PRIMARY`). New tag: `NWS_SKYCOVER_EMPTY`, message includes `gridId/X,Y` and counts of `qpfIntervals` / `maxByDate` / `minByDate` so we can tell whether the entire bundle failed (everything zero → catch path) versus a partial response (other counts non-zero → API returned data but no `skyCover` field).

Inside the catch block at line 58, also write a `NWS_GRIDPOINTS_FAIL` row to `app_logs` with the exception class+message — currently only `Log.w` fires, which doesn't survive a logcat rotation, which is exactly why we couldn't pin down today's failure.

### 3. User-visible toast on sparse cloud cover view

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`

In `updateWidget` (line 101), after the existing `selectCloudCoverSource` resolution and the `sourceRowsWithCloudCover` count are known, compute the coverage ratio over the visible window (`windowKeys` from `selectCloudCoverSource` — extract the window-build into a small helper to share). If the user just tapped to enter cloud cover view (i.e., the update is initiated by `handleSetView` / `ACTION_SET_VIEW`) and coverage < 50% across the window after fallback selection, show a `Toast.makeText(context, "Limited cloud data — last forecast fetch incomplete", LENGTH_SHORT).show()`.

Detection: `WeatherWidgetProvider` already routes `ACTION_SET_VIEW` (seen in logs as `Set View action for widget 345, target=CLOUD_COVER`). Plumb a boolean (`fromUserViewSwitch`) from the intent path through to `updateWidget`. Only show on user-initiated switch — never on background refreshes — to avoid toast spam. The toast must run on the main thread (use `ContextCompat.getMainExecutor(context).execute { ... }` since handler runs on a coroutine dispatcher).

If multiple sources are in scope, prefer the message variant matching what selectCloudCoverSource actually picked: if it fell back, say "Showing Open-Meteo (NWS data limited)"; if it stuck with the requested source despite sparseness, say "Limited cloud data".

## Critical Files

- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt` — merge logic in `saveHourlyEntities` (line 632) and `hasMeaningfulHourlyChange` (line 84)
- `app/src/main/java/com/weatherwidget/data/repository/NwsForecastMapper.kt` — diagnostic `app_logs` writes around line 58 (catch) and line 70 (post-await)
- `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt` — toast on sparse coverage, requires plumbing `fromUserViewSwitch` flag
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` — pass through the user-tap signal to `CloudCoverViewHandler.updateWidget`
- (No DAO change — `HourlyForecastDao.kt` keeps existing `@Insert(onConflict=REPLACE)`)

## Reused Existing Utilities

- `existingByDateTime` map already built in `saveHourlyEntities` — reused for the merge transform; no new DAO query.
- `appLogDao.log(tag, message)` — same call pattern as existing `NWS_GRID_TEMP_PRIMARY` and `NWS_PARTIAL_DAY_KEEP` rows.
- `selectCloudCoverSource`'s window construction (lines 67–78) — extract to a small private helper so both the source selector and the toast threshold check reuse identical hour keys.
- `WidgetPerfLogger.TAG_WIDGET_PAINT` and existing `appLogDao.log(...)` calls in `CloudCoverViewHandler` — pattern to follow for any new persistent log lines.

## Verification

1. **Unit test (preservation)**: in `ForecastRepositoryTest` (or a new `SaveHourlyEntitiesTest`), construct an existing entity with `cloudCover=42`, a newly-fetched entity at the same dateTime with `cloudCover=null`, run `saveHourlyEntities`, assert the persisted row still has `cloudCover=42`. Add the symmetric case where new is non-null, ensuring it overwrites. Also test that `hasMeaningfulHourlyChange` returns false when the only "change" is null-out of cloud cover.

2. **Unit test (logging)**: in `NwsForecastMapperTest`, stub `nwsApi.getGridpointsBundle` to return `GridpointsBundle(skyCoverByHour=emptyMap(), ...)`, verify `appLogDao.log` is called with tag `NWS_SKYCOVER_EMPTY`. Stub it to throw, verify `NWS_GRIDPOINTS_FAIL` is written.

3. **End-to-end on Samsung**:
   - `./gradlew installDebug`
   - On Samsung, force a fetch: `adb -s RFCT71FR9NT shell am broadcast -a com.weatherwidget.ACTION_FORCE_REFRESH` (or trigger via the Settings → Refresh button)
   - Manually break NWS skyCover by injecting a network failure (toggle airplane mode mid-fetch) OR by editing `parseSkyCoverFromProperties` temporarily to return empty map.
   - Confirm the next NWS row insert preserves cloud cover from the previous fetch via `sqlite3 weather_database "SELECT dateTime, cloudCover, fetchedAt FROM hourly_forecasts WHERE source='NWS' AND dateTime > strftime('%s','now')*1000 ORDER BY dateTime LIMIT 10;"` — `cloudCover` should remain populated even though `fetchedAt` did not advance.
   - Verify `app_logs` has a `NWS_SKYCOVER_EMPTY` row with the expected counts.
   - Tap to switch the widget to cloud cover view — verify toast fires once and only on the user-tap path.

4. **Regression**: confirm Pixel — where NWS responses are healthy — shows full cloud cover with no toast and no `NWS_SKYCOVER_EMPTY` rows after a few fetch cycles.

5. **Run unit tests**: `./gradlew testDebugUnitTest --tests "com.weatherwidget.data.repository.*"`

## Out of Scope

- Per-hour cross-source fallback in the renderer (option 2 from clarification). Rejected for now because the preservation fix should make this unnecessary for transient failures.
- Tightening `selectCloudCoverSource` threshold (option 3). Rejected for the same reason; the toast surfaces the situation when it does occur, without silently switching sources.
- `precipProbability` and `precipAmountMm` preservation: included as a freebie since they share the same upsert path and the same gridpoints-bundle failure mode, but no separate verification needed beyond the cloud cover unit test pattern.

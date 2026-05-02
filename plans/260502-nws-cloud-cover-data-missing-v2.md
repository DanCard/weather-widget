# Fix: Cloud Cover Graph Mostly Missing on Samsung

## Context

Samsung Galaxy Z Fold 4 (`RFCT71FR9NT`) showed a sparse cloud cover graph — only ~10 of 25 hours rendering. Investigation:

- DB confirms 156 future NWS hourly rows on Samsung have `cloudCover=null`, all written by a single fetch at `2026-05-02 07:38:04`. Every prior fetch wrote good values. Pixel under the same timeframe has 0 nulls.
- Renderer log: `sourceRows=25 sourceRowsWithCloudCover=10`. The 10 surviving hours are past timestamps (00:00–07:00) preserved from earlier fetches because NWS forecasts only project forward, so the bad fetch couldn't overwrite them.
- Root cause is two stacked bugs:
  1. **`NwsForecastMapper.kt:54–66`** silently catches `getGridpointsBundle` failures and proceeds with `skyCoverByHour = emptyMap()`. The mapper then emits `HourlyForecastEntity` with `cloudCover=null`, which `ForecastRepository.saveHourlyEntities` blindly REPLACE-upserts over previously-good data.
  2. The renderer simply skips null hours (no markers and no message), so the user sees a half-empty graph and is left to guess whether it's "actually clear" or "data missing". Today there is also a per-source fallback in `CloudCoverViewHandler.selectCloudCoverSource` that silently switches to another source when the requested one has zero cloud-cover hours — this hides the situation.
- We can't tell from residual logs whether the gridpoints HTTP call failed or whether the response was structurally valid but lacked a `skyCover` field — the catch path and the empty-array path collapse to the same `emptyMap()`.

Outcome wanted: a transient API hiccup must not destroy good cached values; the failure must be observable in `app_logs`; missing data must be **honestly displayed in the widget itself** with a permanent in-graph diagnostic — never papered over by silent source substitution.

## Approach

Four small, additive changes. No schema migration. No DAO change.

### 1. Preserve cached `cloudCover` on null writes

**File:** `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt`

In `saveHourlyEntities` (line 632), before passing entities to the DAO, merge each new entity with its existing DB row: if `newlyFetched.cloudCover == null` and `existing?.cloudCover != null`, replace with `newlyFetched.copy(cloudCover = existing.cloudCover)`. The existing `existingByDateTime` map (line 638) gives us the lookup for free.

Also update `hasMeaningfulHourlyChange` (line 84) so a write that would only null out `cloudCover` is not considered meaningful — keeps `fetchedAt` from refreshing on rows where we'd lose data.

Apply the same preservation to `precipProbability` and `precipAmountMm` (also nullable, also from the same gridpoints bundle for NWS — same failure mode). Keep the change scoped to nullable fields; non-null fields (`temperature`, `condition`) keep current REPLACE semantics.

### 2. Remove the per-source cloud cover fallback

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`

Per user direction: "no fallback when cloud cover missing." Remove `selectCloudCoverSource` entirely (lines 60–99) and replace its single call site at line 140 with `val effectiveDisplaySource = displaySource`. The "Falling back cloud cover source" log at line 178–182 also goes away.

This means: if the user chose NWS and NWS lacks cloud cover for the visible window, the graph shows only NWS's available points — never silently switches to Open-Meteo. Honest by default. The diagnostic in change #4 makes the gap visible.

Delete the corresponding unit tests for `selectCloudCoverSource` if any (likely in `CloudCoverViewHandlerTest`).

### 3. Structured logging when `skyCover` is empty

**File:** `app/src/main/java/com/weatherwidget/data/repository/NwsForecastMapper.kt`

Just after `val gridpoints = gridpointsDeferred.await()` (line 70), if `gridpoints.skyCoverByHour.isEmpty()`, write to `app_logs` via the existing `appLogDao.log(...)` pattern (used at line 121 with tag `NWS_GRID_TEMP_PRIMARY`). New tag: `NWS_SKYCOVER_EMPTY`, message includes `gridId/X,Y` and counts of `qpfIntervals` / `maxByDate` / `minByDate` so we can tell whether the entire bundle failed (everything zero → catch path) versus a partial response (other counts non-zero → API returned data but no `skyCover` field).

Inside the catch block at line 58, also write a `NWS_GRIDPOINTS_FAIL` row to `app_logs` with the exception class+message — currently only `Log.w` fires, which doesn't survive a logcat rotation, which is exactly why we couldn't pin down today's failure post-hoc.

### 4. Permanent in-graph "data missing" diagnostic

**Files:** `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt`, `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`

Compute the visible window (using the same hour-key construction that lived in `selectCloudCoverSource`; extract it into a small `WindowKeyHelper` since both the handler and the missing-count logic need it) and count `missingHours = windowKeys.size - hours.size` (where `hours` is the list passed to `renderGraph`).

Pass `missingHours` and `totalHours` into `CloudCoverGraphRenderer.renderGraph()` as new parameters. Inside the renderer:
- If `missingHours == 0`, render unchanged — no extra text, no behavior change for the healthy path.
- If `missingHours > 0`, render a small text label onto the bitmap. Position: bottom-center of the graph area, in muted color, sized ~10sp. Text format: `"Cloud data missing for N of M hours"` (e.g., `"Cloud data missing for 15 of 25 hours"`). For the all-missing case (`hours.isEmpty()`, currently returns blank bitmap at line 178–181), instead draw the same diagnostic text centered, replacing the early-return blank.
- The text is permanent for every render where data is incomplete — not a transient toast, not driven by user-tap state.

The renderer already has Paint/Canvas plumbing and label-collision logic; the diagnostic text sits below the graph baseline and bypasses collision detection (it's outside the curve area). Use the existing `Typeface` and `Paint` setup pattern from the watermark code in the renderer.

Also surface the same condition in `app_logs` once per render with `missingHours > 0` — tag `CLOUD_COVER_GAPS`, message includes source/sourceId, missing/total, and centerTime — so we can correlate gaps with fetch failures over time.

## Critical Files

- `app/src/main/java/com/weatherwidget/data/repository/ForecastRepository.kt` — merge logic in `saveHourlyEntities` (line 632) and `hasMeaningfulHourlyChange` (line 84)
- `app/src/main/java/com/weatherwidget/data/repository/NwsForecastMapper.kt` — `app_logs` writes around line 58 (catch) and line 70 (post-await)
- `app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt` — delete `selectCloudCoverSource`, plumb `missingHours` / `totalHours` into the renderer call
- `app/src/main/java/com/weatherwidget/widget/CloudCoverGraphRenderer.kt` — render the in-graph diagnostic text; replace blank-bitmap early return for the all-missing case
- (No DAO change — `HourlyForecastDao.kt` keeps existing `@Insert(onConflict=REPLACE)`)

## Reused Existing Utilities

- `existingByDateTime` map already built in `saveHourlyEntities` — reused for the merge transform; no new DAO query.
- `appLogDao.log(tag, message)` — same call pattern as existing `NWS_GRID_TEMP_PRIMARY` and `NWS_PARTIAL_DAY_KEEP` rows.
- Window-key construction (was in `selectCloudCoverSource` lines 67–78) — extract to a small private helper used by `updateWidget`'s gap counter and any future callers.
- Renderer's existing `Paint` / `Typeface` setup (used for watermark text) — reuse the styling primitives for the diagnostic label.

## Verification

1. **Unit test (preservation)**: in `ForecastRepositoryTest` (or a new `SaveHourlyEntitiesTest`), construct an existing entity with `cloudCover=42`, a newly-fetched entity at the same dateTime with `cloudCover=null`, run `saveHourlyEntities`, assert the persisted row still has `cloudCover=42`. Symmetric: new is non-null → overwrites. Also test that `hasMeaningfulHourlyChange` returns false when the only "change" is null-out of cloud cover.

2. **Unit test (logging)**: in `NwsForecastMapperTest`, stub `nwsApi.getGridpointsBundle` to return `GridpointsBundle(skyCoverByHour=emptyMap(), ...)`, verify `appLogDao.log` is called with tag `NWS_SKYCOVER_EMPTY`. Stub it to throw, verify `NWS_GRIDPOINTS_FAIL` is written.

3. **Unit test (no fallback)**: in `CloudCoverViewHandlerTest`, after the change, verify that with NWS having zero cloud cover and Open-Meteo having full cloud cover, the chosen source remains NWS (no silent switch). The renderer should be invoked with the empty-NWS hour list.

4. **Renderer unit/snapshot test**: `CloudCoverGraphRendererTest` (or add) — with `missingHours > 0`, verify a Bitmap render contains the diagnostic text (read pixel-canvas via instrumented test, or assert via the `Paint`/`Canvas` mock pattern used by other renderers). With `missingHours = 0`, verify no diagnostic.

5. **End-to-end on Samsung**:
   - `./gradlew installDebug`
   - On Samsung, force a fetch via Settings → Refresh.
   - Manually break NWS skyCover by editing `parseSkyCoverFromProperties` temporarily to return empty map (revert before commit).
   - Confirm subsequent NWS row insert preserves cloud cover from the previous fetch: `sqlite3 weather_database "SELECT dateTime, cloudCover, fetchedAt FROM hourly_forecasts WHERE source='NWS' AND dateTime > strftime('%s','now')*1000 ORDER BY dateTime LIMIT 10;"` — `cloudCover` should remain populated.
   - Verify `app_logs` has a `NWS_SKYCOVER_EMPTY` row.
   - Force a state where the visible window has gaps (e.g., wipe a few rows manually): `sqlite3 weather_database "UPDATE hourly_forecasts SET cloudCover=NULL WHERE source='NWS' AND dateTime > strftime('%s','now')*1000 LIMIT 5"`. Tap the widget; confirm the in-graph "Cloud data missing for N of M hours" text renders permanently — present on every redraw, gone after a successful fetch fills the gaps.

6. **Regression**: confirm Pixel — where NWS responses are healthy — shows full cloud cover with no diagnostic text and no `NWS_SKYCOVER_EMPTY` rows after a few fetch cycles.

7. **Run unit tests**: `./gradlew testDebugUnitTest --tests "com.weatherwidget.data.repository.*" --tests "com.weatherwidget.widget.*"`

## Out of Scope

- Per-hour cross-source fallback (the data-honesty principle in change #2 forbids it).
- Cross-source backfill jobs or any kind of source mixing.
- Schema or DAO changes.

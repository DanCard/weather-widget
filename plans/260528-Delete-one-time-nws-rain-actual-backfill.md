# Remove the one-time NWS precip backfill (THROWAWAY 2026-05-28)

## Context

On 2026-05-28 (commit `3a50568`) we added a deliberately temporary, SharedPref-gated
one-shot — `runOneTimeNwsPrecipBackfillIfNeeded` — to retroactively patch NWS observation
rows that were fetched *before* the `getObservations` parser learned to extract
`precipitationLastHour`. Those old rows carried `precipAmountMm = null`; the backfill
re-fetched 7 days of history and overwrote them. The original prompt was explicit:
*"Add it in the code and then later delete it."* Session-log open-item #2 set the deletion
gate: *delete once a few refresh cycles confirm new NWS fetches naturally carry precip.*

**Both gating conditions are now verified on-device (2026-05-28):**

1. **The one-shot fired and is dormant.** `THROWAWAY_NWS_PRECIP_BACKFILL_DONE` logged on
   emulator-5554 (08:39, rows=1749) and physical Pixel RFCT71FR9NT (10:24, rows=1748).
   The flag `throwaway_nws_precip_backfill_done_v1` is set, so it is already a permanent
   no-op on those installs.
2. **Natural fetches carry precip on their own.** On emulator-5554 the backfill ran at
   08:39, yet a precip-carrying NWS row exists at 09:55 (KNUQ=0.5) — i.e. produced by an
   ordinary scheduled fetch through the permanent parser fix, not the backfill. KNUQ/KSJC
   are streaming hourly precip cleanly.

Conclusion: the backfill has served its purpose and is safe to remove.

## Scope — what to delete vs. keep

**Delete (the throwaway, 3 touchpoints, all banner-commented `THROWAWAY 2026-05-28`):**

1. `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`
   — remove the banner block + `runOneTimeNwsPrecipBackfillIfNeeded(...)` (lines ~254–299).
2. `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`
   — remove the throwaway passthrough (lines ~135–140).
3. `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
   — remove the comment + call in `doWork` (lines ~109–113).

**Keep (permanent — do NOT touch):**

- `backfillRecentNwsObservations(...)` in both `ObservationRepository` and `WeatherRepository`.
  It sits *outside* the throwaway banners and has a real caller: `handleObservationBackfillWork`
  (a WorkManager job) at `WeatherWidgetWorker.kt:403`/`:415`. The throwaway merely borrowed it.

## Notes

- **SharedPref key `throwaway_nws_precip_backfill_done_v1`** can be left in place — it's
  harmless inert data on devices where it already ran, and adding cleanup code would
  reintroduce a one-shot we're trying to remove. No action needed.
- No test files reference the throwaway (verified via grep across `app/src` + `*.md`),
  so no test changes are required.
- The two not-yet-checked connected devices (emulator-5556, 2A191FDH300PPW) are irrelevant:
  after removal their pre-fix historical rows simply stay null and age out of the 1-month
  retention; all new fetches carry precip via the permanent parser.

## Verification

1. Build: `./gradlew installDebug` (confirms the deletions compile — `internal` symbols gone).
2. Confirm no stragglers:
   `grep -rn "THROWAWAY 2026-05-28\|THROWAWAY_NWS_PRECIP\|runOneTimeNwsPrecipBackfillIfNeeded\|throwaway_nws_precip_backfill_done_v1" app/src` → should return nothing.
3. Confirm the permanent helper still resolves:
   `grep -rn "backfillRecentNwsObservations" app/src` → still present in ObservationRepository,
   WeatherRepository, and the `handleObservationBackfillWork` call site.
4. Optional sanity: run the existing NWS/observation unit tests
   (`./gradlew testDebugUnitTest --tests "com.weatherwidget.data.remote.NwsApiTest"`).

## Commit

Single focused commit, e.g. `Remove one-time NWS precip backfill (throwaway 2026-05-28)`.
It reverts cleanly since it was authored as a self-contained block for exactly this purpose.

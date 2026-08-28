# Delete LegacyDefaultLocationMigration (the §8 deferred cleanup)

**Status:** ⏳ Planned — do not execute before **2026-10-12** · **Target:** §8 of `plans/260812-remove-default-location-and-show-error-when-unavailable.md`

> This plan supersedes the original §8 gate. The original said "delete the migration + legacy
> constants once `LOCATION_MIGRATION` telemetry shows the migration has run everywhere that
> matters." That gate turns out to be **unobservable** (see §2), so it is replaced here with a
> release-age heuristic. Everything else in §8 (what to delete, the zero-hits acceptance grep) still
> applies and is re-derived below with the exact current call sites.

---

## 1. Background

`plans/260812-remove-default-location-and-show-error-when-unavailable.md` removed the Google-HQ
hardcoded default location. Its §4 upgrade migration (`LegacyDefaultLocationMigration`) shipped in
two halves:

1. **Prefs half** (commit `6477b65a`): erase `37.4220, -122.0841` from `active_weather_location`
   and per-widget prefs.
2. **Database half** (commit `6662d026`): purge the `forecasts` rows filed at that site, because
   prefs-clearing alone let `ActiveLocationResolver.resolve()` re-read the sentinel back through
   `getLatestWeather()` and re-persist it (v1 silently undid itself).

Both halves are complete, wired, and tested. §8 deferred only the *tidy-up*: deleting the migration
object, its two constants, and the now-vestigial helpers once the upgrade population had been
migrated.

---

## 2. The §8 "telemetry" gate is unobservable — correction

The original gate assumed a `LOCATION_MIGRATION` breadcrumb could be queried to confirm rollout. It
cannot:

1. The app ships **Firebase Crashlytics only** — there is **deliberately no Firebase Analytics**
   (`app/build.gradle.kts:459`, `PRIVACY_POLICY.md`: "No usage analytics"). There is nothing to
   query for "how many installs ran the migration."
2. The breadcrumb is written to the local `app_logs` DB and mirrored to Crashlytics as a `log()`
   custom log (`AppLogEntity.kt:166-172`). Crashlytics custom logs are a **local ring buffer** that
   uploads only **attached to a crash report** — not a continuous telemetry stream. So the
   breadcrumb is effectively invisible remotely unless a migrated device later crashes while it is
   still buffered.
3. The breadcrumb is only written when the migration actually cleared something (`clearedCount > 0`),
   i.e. only on installs that were genuinely pinned at the sentinel. Those are the installs we care
   about, and they are the ones we cannot see.

**Conclusion:** enabling analytics to answer this one question is not worth it (retroactive
blindness + privacy-policy/Data-Safety changes + it contradicts the project's documented posture).
Replace the gate with calendar/release age.

---

## 3. The replacement gate (release-age heuristic)

Delete the migration no earlier than **both**:

1. **≥ 2 months** have passed since it first shipped: **2026-08-12 → execute on/after 2026-10-12**.
2. **≥ 2 public releases** have shipped since 2026-08-12. (Already satisfied: the migration shipped
   in `26081201` and the current release is `26082702` — five releases later. This leg is done; only
   the calendar age remains.)

Rationale: every upgrade runs `runIfNeeded()` exactly once, and it is idempotent (a single boolean
read short-circuits after the first run). Any install that was ever going to upgrade will have run it
within two release cycles; two months of calendar time covers slow auto-updaters.

### Pre-deletion checklist (run at execution time)

1. Re-check the connected dev devices + emulator for residual state (as done 2026-08-27):
   - `legacy_default_cleared_v2 = true`, no `legacy_default_cleared_v2_report` key;
   - zero `forecasts` rows at `37.4220 ± 0.002, -122.0841 ± 0.002`;
   - active/widget coords are a real site, not the sentinel.
2. Confirm the date gate (≥ 2026-10-12) and release count (≥ 2 since Aug 12).

---

## 4. Deletion inventory

### 4.1 Main code

1. **`app/src/main/java/com/weatherwidget/widget/LegacyDefaultLocationMigration.kt`** — delete the
   whole file (object, `LEGACY_DEFAULT_LAT/LON`, `runIfNeeded`, `isPurgePending`,
   `consumePendingReport`, `Outcome`, all private helpers).

2. **`app/src/main/java/com/weatherwidget/WeatherWidgetApp.kt`**
   - Remove `import com.weatherwidget.widget.LegacyDefaultLocationMigration` (L10).
   - Remove the `runLegacyDefaultLocationMigration()` call (L56) and its two-line comment (L54-55).
   - Remove the `runLegacyDefaultLocationMigration()` private function + KDoc (L68-77).

3. **`app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`**
   - Remove the `completeLegacyDefaultMigration()` call (L99) and the four-line comment above it
     (L93-98, the "Emitted here rather than at Application.onCreate…" block).
   - Remove the `completeLegacyDefaultMigration()` private function + KDoc (L110-143).
   - Trim the `LegacyDefaultLocationMigration` reference in the `getLocationName()` comment (L468);
     keep the "No `DEFAULT_LAT`/`DEFAULT_LON`. 'No location' is the absence of coordinates" note
     (L466) — that invariant remains true and worth documenting.

4. **`app/src/main/java/com/weatherwidget/widget/ActiveLocationResolver.kt`**
   - Collapse the `isPurgePending` suppression back to the direct fallback (L68-75):
     ```kotlin
     val cachedWeatherLocation =
         forecastDao.getLatestWeather()?.let { it.locationLat to it.locationLon }
     ```
   - Remove the `[LegacyDefaultLocationMigration]` references in the `clear()` KDoc (L33) and the
     resolver comment block (L69-72). The `resolve()` KDoc's "this used to fall back to Google HQ"
     history note (L47) may stay — it documents the no-default invariant, not the migration.

5. **`app/src/main/java/com/weatherwidget/data/local/ForecastDao.kt`**
   - Delete `deleteForecastsAtSite()` (L423) and its KDoc (L410-421). It has no other callers once
     the worker's purge is gone.

6. **`app/src/main/java/com/weatherwidget/ui/LocationUpdater.kt`**
   - Trim the `LegacyDefaultLocationMigration` reference in the comment at L41.

7. **`app/src/main/java/com/weatherwidget/widget/handlers/HourlyObservationBackfill.kt`**
   - Rewrite the comment at L56 (drop the "exactly one, in LegacyDefaultLocationMigration" pointer;
     keep the `sameSite` lesson itself).

### 4.2 Shared code

8. **`shared/src/main/kotlin/com/weatherwidget/data/local/LocationMatch.kt`**
   - Remove `ROOM_SAME_SITE_WHERE` (L57-59). Its only consumer was `deleteForecastsAtSite`
     (ForecastDao.kt:422). `sameSite` and `SAME_SITE_TOLERANCE_DEG` stay — they are used broadly by
     observation-site merging. This is a firm instruction, not optional: a dead SQL-fragment
     constant in the clean `:shared` module is worse than removing it, and it is a three-line
     recreation if a future SQL same-site query ever needs it.
   - **Preserve the lesson before deleting.** The constant's KDoc encodes a hard-won, non-obvious
     rule — *"reads want the coarse ±0.1° box; a row-deleting query must not use it, or it takes
     out everything within ~7 miles of the target."* Fold that sentence into the
     `SAME_SITE_TOLERANCE_DEG` KDoc (which survives) so the knowledge outlives the constant.

### 4.3 Tests

9. **`app/src/test/java/com/weatherwidget/widget/LegacyDefaultLocationMigrationTest.kt`** — delete
   the whole file.

10. **`app/src/test/java/com/weatherwidget/data/local/ForecastDaoSitePurgeTest.kt`** — delete the
    whole file (it tests `deleteForecastsAtSite`).

11. **`app/src/test/java/com/weatherwidget/widget/ActiveLocationResolverTest.kt`**
    - Remove the "sentinel-resurrection window" section (L141-197): the
      `runMigrationWithSentinelOnDisk()` helper and the three tests
      (`cached weather cannot resurrect the sentinel before the purge runs`,
      `cached weather fallback works again once the purge has been consumed`,
      `a clean install with nothing to migrate never suppresses the fallback`).
    - Add one replacement test asserting the cached-weather fallback now works unconditionally:
      seed `getLatestWeather()` with a coordinate and assert `resolve()` returns it (covers the
      fallback behavior that survives the deletion).
    - Remove the `LegacyDefaultLocationMigration` import if present.

12. **`app/src/test/java/com/weatherwidget/widget/handlers/HourlyObservationBackfillLocationTest.kt`**
    - Rewrite the comment at L54 (drop the migration pointer).

---

## 5. Acceptance criteria

1. `grep -rn "LegacyDefaultLocationMigration\|LEGACY_DEFAULT_LAT\|LEGACY_DEFAULT_LON\|DEFAULT_LAT\|DEFAULT_LON" app/src/main`
   → **zero hits**.
2. `grep -rn "deleteForecastsAtSite\|ROOM_SAME_SITE_WHERE" --include=*.kt app shared`
   → **zero hits** (outside `app/build/`).
3. `grep -rn "LegacyDefaultLocationMigration" --include=*.kt app/src` → zero hits (tests included).
4. `Mountain View` may remain only in comments/KDoc/test fixtures and
   `FriendlyLocationName`/`NominatimApi` prose — no coordinate fallback, no sentinel, no
   `"Mountain View, CA"` label branch.
5. Full test suite green: `./gradlew test` (with the `:app`/`:desktop`/`:shared` category buckets),
   plus `./scripts/emulator-tests.sh` if the DAO/renderer paths are touched.

---

## 6. Suggested commit sequence

1. `Delete LegacyDefaultLocationMigration and its worker purge` — files in §4.1 items 1-4 plus the
   worker call/function; restores the unconditional cached-weather fallback in `resolve()`.
2. `Remove deleteForecastsAtSite and ROOM_SAME_SITE_WHERE` — §4.1 item 5 and §4.2 item 8
   (including folding the tight-box-vs-read-box lesson into `SAME_SITE_TOLERANCE_DEG` first).
3. `Drop migration-era tests and comments` — §4.1 items 6-7 and §4.3 items 9-12.
4. `Verify zero-hits acceptance grep and run the full suite` — no code change unless the grep/tests
   surface a straggler.

---

## 7. Risks

- **Deleting too early** pins a still-unmigrated install at Google HQ. The two-month gate makes this
  negligible, and the migration has already shipped in five releases.
- **Removing `ROOM_SAME_SITE_WHERE`** touches `:shared`; confirm no other consumer before deleting
  (grep in §5.2 covers it).
- **Losing the "cached-weather fallback" test coverage** — the replacement test in §4.3.11 preserves
  it; do not let the fallback behavior go untested, since it is the only location record a
  pre-canonical-location install has.

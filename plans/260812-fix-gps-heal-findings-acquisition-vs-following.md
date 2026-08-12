# Fix the location auto-heal findings; split acquisition from following

**Status:** 📋 Planned 2026-08-12 · **Target:** H1–H5 + L1–L4 from
[`plans/260812-code-review-gps-auto-heal.md`](260812-code-review-gps-auto-heal.md)
**Follows:** [`260812-remove-default-location-and-show-error-when-unavailable.md`](260812-remove-default-location-and-show-error-when-unavailable.md)

**Goal:** make the "no location" state the change set introduced actually reachable, escapable, and
correctly named. Two of the fixes are load-bearing for that change set's own premise — without §1 it
never reaches the users it was written for, and without §2+§3 "no location" can become permanent.

---

## 1. Problem statement

Yesterday's change set replaced the Google-HQ placeholder with an explicit no-location state. The
design is right. Five things stop it working end to end:

| # | Finding | Effect |
|---|---|---|
| §1 | `resolve()` re-derives the sentinel from cached `forecasts` rows and re-persists it | Upgraders are silently re-pinned to Mountain View; the migration is undone in the first worker run |
| §2 | The heal path resolves "active location" through POI/legacy inference | A fresh fix can read as "already located" → no candidate → no location, permanently |
| §3 | The no-location paint drops every `PendingIntent` | "No location — tap to set" does nothing (Samsung: opens the wrong screen) |
| §4 | Acquisition uses the following-mode promotion policy | A fresh install shows the error for up to 8h after its weather is cached |
| §5 | `allWidgetsAtDefault`/`shouldHealTo` are dead but documented as the signal | Future changes will reason from comments that describe nothing |

Plus the naming follow-up (§6), which is why §4 was mis-shaped in the first place.

---

## 2. Design decisions

### 2a. The sentinel lives in three places, not two

`LegacyDefaultLocationMigration`'s KDoc says "These are the last two references to those coordinates
in the app." There is a third: every `forecasts` row fetched for Google HQ over the retention month.
`ActiveLocationResolver.resolve()` reads them back through a location-blind
`getLatestWeather()` and **persists** the result as canonical (`ActiveLocationResolver.kt:64-74`).

Two changes, because one window and one long tail:

- **Window** (upgrade → first worker run): while the migration's pending report is unconsumed,
  `resolve()` must not use the cached-weather fallback at all. This is a prefs-only read, so it stays
  clear of the eager-DB-open trap the migration was built to avoid. It matters because `resolve()` has
  six call sites and two of them (`WidgetStartupCoordinator.kt:114`,
  `WidgetRefreshContextResolver.kt:41`) can run from `onUpdate` or a widget tap *before* any worker run.
- **Long tail:** the worker purges `forecasts` rows at the legacy site when it consumes the report,
  so a later `resolve()` (e.g. after a subsequent `saveNoLocation`) can't resurrect it either.

**Rejected:** deleting the cached-weather fallback outright. It is the only location record for
installs predating the canonical active location, and removing it would strand them at no-location
with no migration path. **Also rejected:** teaching `resolve()` to skip HQ-shaped rows — that puts the
sentinel coordinates back into steady-state code, which the change set worked to eliminate.

**Accepted cost:** a user who genuinely lives at Google HQ loses their cached forecasts and gets the
no-location prompt instead of a silent lucky guess. The migration already un-pins them (it cannot
distinguish deliberate from placeholder); this only makes that decision consistent instead of
half-applied. An honest prompt beats a silent guess that happens to be right.

**Purge bound is `SAME_SITE_TOLERANCE_DEG` (0.002), not `LocationMatch.ROOM_WHERE`.** The latter is a
±0.1° (~7 mi) proximity box, which would delete a genuinely-nearby user's unrelated data.

### 2b. POIs are a label store; only the coordinate readers are wrong

`historical_pois` is read by `FriendlyLocationName.cached()` (`FriendlyLocationName.kt:36`) and
maintained by `CurrentTempRepository` — it is how the app names a coordinate without a network call.
So it must **not** be cleared when a location is unset (an earlier draft of the review said it
should; that was wrong). What must change is `WidgetLocationStore.resolve()` treating it as a
*coordinate* source, and the two heal call sites using that resolver.

### 2c. Acquisition and following need separate answers, not a compromise constant

They want opposite biases (review H5). Encode the distinction in the pure policy function rather
than tuning `MOVING_GRACE_MS`, so a future adjustment for the driving case can't silently re-strand
first-time users.

---

## 3. Implementation

### §1 — Stop the sentinel resurrecting (review H1) 🔴

**`LegacyDefaultLocationMigration.kt`**
- Add `isPurgePending(context): Boolean` — `KEY_PENDING_REPORT` present.
- Expose the legacy pair to the worker for the purge (keep it `internal`; it stays the app's only
  surviving comparison against those coordinates).
- Update the KDoc: "the last two references" → three, with the `forecasts` table named.

**`ActiveLocationResolver.kt`**
- In `resolve()`, guard the `forecastDao.getLatestWeather()` fallback with
  `!LegacyDefaultLocationMigration.isPurgePending(context)`. Comment it as a bounded, one-upgrade
  condition, not a general rule.

**`ForecastDao.kt`**
```kotlin
@Query("DELETE FROM forecasts WHERE ABS(locationLat - :lat) < :tol AND ABS(locationLon - :lon) < :tol")
suspend fun deleteForecastsAtSite(lat: Double, lon: Double, tol: Double = LocationMatch.SAME_SITE_TOLERANCE_DEG): Int
```

**`WeatherWidgetWorker.kt:75-77`** — in the `consumePendingReport` block, purge before the report is
cleared, and log `rows_purged=N` alongside the existing report so rollout telemetry can distinguish
"nothing to clear" from "cleared, then resurrected."

Order matters: the block already sits above every `resolve()` call in the file.

### §2 — Heal decisions read stored coordinates only (review H2) 🟠

- `WidgetLocationStore.resolve()` — drop `?: historicalPoiFallback()`; delete the private helper
  (this also resolves L2's duplicate parser).
- `GpsResampler.kt:100-101` — `getWidgetLocation` → `getStoredWidgetLocation`; fix the comment above
  it, which currently claims the value is null when the app has no location.
- `LocationUpdater.kt:229-230` — same substitution.
- `HourlyObservationBackfill.resolveBackfillLocation`'s caller — same, so observations can't be filed
  under an inferred POI.
- `LocationUpdater.effectiveLocation` keeps its POI fallback: it feeds a *label*, and showing the last
  known place name in Settings is honest as long as nothing fetches there.

### §3 — Make the no-location state tappable (review H4) 🟠

- `WidgetRenderer.updateWidgetNoLocation` — set a `ConfigActivity` `PendingIntent` (with
  `EXTRA_APPWIDGET_ID`, `FLAG_IMMUTABLE`) on `R.id.widget_root` before the push. Without it the full
  push strips even the dead-zone catch-all, so Samsung falls through to `MainActivity`.
- `WidgetRenderer.updateWidgetError` — same treatment behind its "Tap to refresh" string (pre-existing
  gap, identical shape, one line).
- Fix `updateWidgetNoLocation`'s KDoc, which already claims the tap works.

### §4 — Split acquisition from following (review H5) 🟠

**`LocationHandoffPolicy.kt`**
```kotlin
internal fun evaluateCandidateUsability(
    ...,
    isAcquisition: Boolean,   // no active location: anything beats the error state
): CandidateUsability {
    if (requiredSourceIds.isEmpty()) return CandidateUsability(false, "no_display_sources")
    if (!dailyReady) return CandidateUsability(false, "insufficient_daily_coverage")
    if (isAcquisition) return CandidateUsability(true, "acquisition_daily_coverage")
    ...  // existing following-mode logic unchanged
}
```
**`tryPromoteLocationCandidate`** passes `isAcquisition = ActiveLocationResolver.current(context) == null`.

Deliberately still requires `dailyReady`: promoting with nothing to draw would replace one blank state
with another.

### §5 — Delete the dead pair, repoint the docs (review H3) 🟠

- Delete `LocationUpdater.allWidgetsAtDefault` and `shouldHealTo`, plus their tests
  (`LocationUpdaterTest` cases) and the `NoLocationWidgetIntegrationTest:148` assertion.
- Repoint the four prose references (`CLAUDE.md`, `ConfigActivity.kt:537`, `WidgetRenderer.kt:100`,
  `LegacyDefaultLocationMigration.kt:12-16`) at `GpsResampler`/`LocationHandoffStore`. The migration's
  rationale keeps its second bullet (`current()` returns the sentinel), which is the true one.

### §6 — Rename (review §5) 🟡 — separate commit, pure no-op diff

- `healIfNeeded` → `followDeviceIfMoved`; `maybeAutoHealLocationFromGps` → `maybeFollowDeviceLocation`.
- `LocationFixFlow.Outcome.Default` → `Outcome.NoFix` (L3) + KDoc, which still describes the deleted
  hard-default stage.
- Comments and `CLAUDE.md`: "GPS auto-heal" → "device following" / "location acquisition" as
  appropriate. `healCorruptDatabaseVersion` and the render self-heal keep their names.
- Verification is that the diff contains only identifiers and prose and the suite passes unchanged.

### Cleanups folded into the commits above

- **L1** — `GpsResampler.kt:92-96`: `outcome=same_site reason=no_widgets` → `outcome=skipped_no_widgets`
  (into §2). These breadcrumbs are the debugging interface; the token must not lie.
- **L4** — `WidgetLocationStore.clearWidget` `apply()` → `commit()`, matching every other write in the
  subsystem (into §2).

---

## 4. Automated test coverage

Pure-function first, per the project's no-mocking preference; Robolectric only where Android state is
the thing under test.

**§1 — sentinel resurrection** (`LegacyDefaultLocationMigrationTest`, `ActiveLocationResolverTest`)
1. Cleared prefs + an HQ `forecasts` row + pending report → `resolve()` returns **null** (today: HQ).
2. Same, after the purge runs → still null, and the HQ rows are gone.
3. A *non*-HQ forecast row is untouched by the purge, and still resolves (the pre-canonical migration
   path must survive).
4. A 3-dp quantized HQ row (`-122.084`) is purged — the `==` trap that already bit
   `HourlyObservationBackfill`; prove it by flipping the bound and watching this fail.
5. A row 0.05° from HQ (inside `ROOM_WHERE`'s box, outside `sameSite`) is **not** purged.
6. Purge is one-shot: second worker run reports `rows_purged=0` and deletes nothing.
7. `isPurgePending` is false on a clean install, so the fallback is unaffected there.

**§2 — inferred locations** (`GpsResamplerTest`, `LocationUpdaterTest`)
8. POI present, active + stored cleared, fresh fix `sameSite` with the POI → **candidate proposed**
   (today: `outcome=same_site`, no candidate). This is the permanent-dead-end regression.
9. Stored widget location `sameSite` with the fresh fix → still `outcome=same_site` (no false heal).
10. Legacy delta-store location no longer suppresses a candidate.
11. `resolveBackfillLocation` with only a POI on disk → `Unanchored`, not anchored at the POI.
12. `outcome=skipped_no_widgets` breadcrumb asserted (L1).

**§3 — tappable states** (`WidgetRendererRoboTest`)
13. After `updateWidgetNoLocation`, the reapplied view tree has a click handler on `widget_root`
    (`reapply()` pattern already used in this suite).
14. Same for `updateWidgetError`.

**§4 — acquisition/following** (`LocationHandoffPolicyTest`, pure)
15. `isAcquisition=true`, daily coverage present, hourly absent, **within** grace → useful,
    `reason=acquisition_daily_coverage`.
16. `isAcquisition=false`, identical inputs → **not** useful (following bias preserved).
17. `isAcquisition=true` with no daily coverage → not useful (don't promote into a blank).
18. Every existing following-mode case passes unchanged with `isAcquisition=false`.
19. `tryPromoteLocationCandidate` derives the flag from a null active location (Robolectric).

**§5/§6** — deletions remove their tests; the rename adds none. Full suite green is the gate.

**Manual, on device:** upgrade an install carrying the sentinel (restore a pre-upgrade DB backup via
`scripts/backup_databases.py`), confirm `LOCATION_MIGRATION rows_purged=N` in `app_logs` and that the
widget paints no-location rather than Mountain View; then confirm a tap opens `ConfigActivity`.

---

## 5. Commit sequence

| # | Commit | Review item |
|---|---|---|
| 1 | Purge sentinel-keyed forecasts; gate the cached-weather fallback during the upgrade window | H1 |
| 2 | Heal decisions read stored coordinates only; drop the POI coordinate fallback | H2, L1, L2, L4 |
| 3 | Make the no-location and error states tappable | H4 |
| 4 | Split acquisition from following in the promotion policy | H5 |
| 5 | Delete `allWidgetsAtDefault`/`shouldHealTo`; repoint the docs | H3 |
| 6 | Rename heal → follow/acquire (no behaviour change) | §5, L3 |

Two ordering constraints are load-bearing: **5 before 6** (otherwise the rename touches code about to
be deleted and the same comments are edited twice), and **6 alone** (a rename commit is only
verifiable by inspection if nothing else moved).

---

## 6. Risks

- **§1 deletes user data.** Bounded to `forecasts` rows within 0.002° of one hardcoded pair, once per
  install, behind a flag that is already false for everyone who upgraded before this ships. Worst case
  is one extra fetch cycle. `hourly_forecasts`/`observations` are deliberately left alone — nothing
  reads a location out of them, and they age out in 30 days.
- **§2 narrows what counts as a location.** An install whose *only* record was a POI now reads as
  no-location and will paint the error until a fix or a tap arrives. That is the intended semantics —
  the alternative is fetching at a coordinate the user hasn't chosen since who-knows-when — but it is
  the change most likely to generate a "my widget broke" report, so it wants the `LOCATION_HANDOFF`
  breadcrumb checked on a real device before release.
- **§4 promotes sooner**, so a genuinely-moving user could see one extra site transition. Following
  mode is untouched (test 16), so this is confined to installs with no active location.

# Session log: Samsung Sunday cloud-cover staleness — change-gate cross-fragment poisoning

**Date:** 2026-07-10 (late evening) → 2026-07-11
**Scope:** diagnosed why the Samsung widget's Sunday (07-12) daily cloud cover read low (67%)
vs desktop/emulator (96%) after NWS revised its forecast; fixed the write-side change gate in
`ForecastRepository.saveHourlyEntities` to diff site-exact instead of against the whole
proximity box; added pure unit tests + a proven-to-fail Robolectric integration guard.

Devices: Samsung `RFCT71FR9NT` (bug device), Pixel `2A191FDH300PPW`, `emulator-5554/5556`
(all got the build via `installDebug`).

---

## Prompts (verbatim) and what each led to

### 1. "Samsung: daily forecast view: Sunday cloud cover low compared to desktop and emulator, why?"
- **Not a recurrence of the same-morning render bug** ([[daily-noon-cloud-refresh-path-unmerged]]):
  Samsung logcat showed `resolveNoonCloudCoverRatio date=2026-07-12 ratio=0.67` *stable* across
  both render passes with merged row counts (`hourlyRows=227`) — `unifyToNearestSite` works.
- Emulator logcat showed the same date flip 0.67 → **0.96** at 23:52; desktop DB had 96%
  (fetched 23:09). NWS revised Sunday's noon cloud that evening.
- Samsung DB (via `backup_databases.py`) had **three fragments** for Sunday noon
  (`hourly_forecasts`, NWS):
  - `37.417,-122.089` → 67%, fetched 12:36 — the widget's nearest site, what renders
  - `37.422,-122.087` → 96%, fetched 21:31 — full 156-row fetch landed here after GPS jitter
  - `37.39,-122.081` → 55%, from 07-08 (morning bug's stale fragment, correctly ignored)
- Smoking gun: the 23:55 fetch at the display site wrote only 96 rows starting **Sunday 17:00**
  — Sunday noon skipped. Root cause chain in `saveHourlyEntities`:
  `getHourlyForecastsBySource` uses `LocationMatch.ROOM_WHERE` (proximity **box**), so
  `existingByDateTime` resolved Sunday noon to the jitter fragment's fresh 96% row →
  `hasMeaningfulHourlyChange(96, 96)` = false → no write at 37.417 → renderer (site-exact read)
  serves 12:36's 67% indefinitely. Write-gate scope (box) ≠ read scope (single site).
- Only physical devices reproduce it: GPS jitter beyond the 3-decimal quantization (~110 m)
  mints fragments; emulator/desktop coordinates are stable. Desktop is structurally immune
  anyway (plain `INSERT OR REPLACE`, no change gate).

### 2. "yes" (implement the fix)
- `ForecastRepository`: new companion helper `siteExactExistingByDateTime(boxRows, lat, lon)` —
  filters the box query result to the exact quantized write coordinates before `associateBy`;
  `saveHourlyEntities` routes its existing-row map through it. The gate now diffs against
  exactly the rows the renderer will read.
- Pure tests in `ForecastRepositoryHourlyChangeTest`: fresher other-fragment row must not mask
  the site row (+ the revision then registers as a meaningful change); brand-new site → empty
  map (everything written).
- Full `testDebugUnitTest` green; `installDebug` on all 4 connected targets. Stale state
  self-healed on first post-fix fetch (96 ≠ site's own 67 → written). **User verified live.**

### 3. "Where integration test(s) added?"
- Fair catch — none had been. The pure tests pin the helper's semantics but not the *wiring*
  (a regression restoring the raw box-wide `associateBy` would still pass them).
- Added `HourlyChangeGateSiteExactIntegrationTest` (Robolectric + real in-memory Room + mockk'd
  NWS API, modeled on `NwsPrecipAmountIntegrationTest`): seeds the display-site row (67%) and
  the jitter fragment (96%, newer, inserted last so box-wide `associateBy` deterministically
  picks it), drives real `getWeatherData(forceRefresh=true)` returning 96%, asserts the
  display-site row updates to 96 and both fragments were in the queried box.
- **Proven to fail:** temporarily reverted the fix → `expected:<96> but was:<67>` (the exact
  production symptom) → restored → full suite green.

### 4. "write session log to session-logs/ dir"
- This file.

---

## Rule established

Any write-path change/diff gate must compare **site-exact** (the same rows the read side will
resolve), never against `ROOM_WHERE` proximity-box reads. A gate whose comparison scope is wider
than the reader's resolution scope can permanently starve the reader — and the write-saving
design (no REPLACE → no fresh `fetchedAt`) makes the staleness invisible without cross-fragment
DB comparison. Diagnostic fingerprint: same-dateTime rows across fragments where a non-nearest
fragment has newer `fetchedAt`; per-fetch row bands at the display site that are sparse and
start at odd future hours instead of full 156-row spans.

## Memories written/updated

- `hourly_change_gate_cross_fragment_poisoning.md` (new) — full mechanism, fix, fingerprint,
  test pointers; linked into the coordinate-fragmentation family index line. Also notes the
  testing-strategy memory's "no mocking framework" claim is outdated (repo uses mockk in
  Robolectric integration tests).

## Files touched

- `app/.../data/repository/ForecastRepository.kt` — `siteExactExistingByDateTime` helper +
  site-exact `existingByDateTime` in `saveHourlyEntities`
- Tests: `ForecastRepositoryHourlyChangeTest.kt` (+2 pure tests, `hourly()` helper gained
  lat/lon params), `HourlyChangeGateSiteExactIntegrationTest.kt` (new)

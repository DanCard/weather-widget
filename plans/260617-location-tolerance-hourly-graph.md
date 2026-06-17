# Fix: hourly forecast location-fragmentation (forecast line vanishes on anchored past-day views)

## Context

On an anchored past-day hourly view (e.g. tapping "yesterday"), the forecast (dashed) line disappears
for the left ~65% of the graph. Confirmed on-device: the forecast temps for that region are `NaN`
(`NAN_TEMP_INDICES [0..218]`), even though the DB **has** NWS forecast rows for those hours.

Root cause = **location fragmentation**. A widget's location is stored at slightly different lat/lon
precisions across fetches (GPS/geocode jitter). For 2026-06-16 the morning forecast rows are at
`37.4168434,-122.0889969` while the afternoon rows + configured location are at `37.4168014,…` — the
same physical spot, ~tens of meters apart. The render then keeps only rows that **exactly** match a
single pinned coordinate, so the morning rows are dropped → NaN forecast → no curve.

The exact-float pin exists on purpose (its comment: "avoids mixing data from multiple Mountain View
markers e.g. 37.422 vs 37.4168 which would cause the smoothing/interpolation to jitter") — but it's
too strict: it treats sub-precision fragments of the *same* site as different markers. `daily_extremes`
is unrelated to this path. See `notes/260617-should-we-remove-daily-extremes-table.md`.

## Change

Replace the **exact-float** in-memory location pin with a **fine same-site proximity** check that
merges sub-precision fragments of one site while still excluding genuinely different markers.

1. **`shared/.../data/local/LocationMatch.kt`** — add a fine constant + helper, distinct from the
   coarse fetch box (`TOLERANCE_DEG = 0.1`):
   ```kotlin
   // "Same physical site, modulo lat/lon precision jitter." Fragments observed ~0.0001° apart;
   // genuinely different markers (e.g. 37.422 vs 37.4168) are ~0.005° apart, so this sits between.
   const val SAME_SITE_TOLERANCE_DEG = 0.002
   fun sameSite(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean =
       abs(lat1 - lat2) <= SAME_SITE_TOLERANCE_DEG && abs(lon1 - lon2) <= SAME_SITE_TOLERANCE_DEG
   ```
2. **`app/.../widget/WidgetRenderer.kt`** (~L138-140): keep `bestHourlyMatch` (closest distinct
   coordinate) as the anchor, but change the filter from exact equality to
   `LocationMatch.sameSite(it.locationLat, it.locationLon, bestHourlyMatch.first, bestHourlyMatch.second)`.
   This one site is shared by the temperature/precip/cloud graph handlers, so it covers all three.
3. **`app/.../widget/WeatherWidgetWorker.kt`** `fetchHourlyForecasts` (~L303 and ~L312): same
   replacement for both `filteredCurrent` and the stitched-`history` filters (so fragments aren't
   dropped before they reach `WidgetRenderer`).

Downstream dedup already handles any duplicate timestamps the merge introduces: `resolveForecastsByTime`
picks one row per `dateTime` by `fetchedAt`, and the worker's `stitched` dedups by `(dateTime, source)`.
No change needed there.

Out of scope: the desktop in-memory pins (desktop already proximity-matches its DB queries; revisit
only if the same symptom shows there), and `daily_extremes`.

## Tests

- New unit test for `LocationMatch.sameSite` (plain JUnit, shared) using the real coordinates:
  `37.4168014` and `37.4168434` (and `-122.0888977` / `-122.0889969`) must be same-site; `37.422` must
  not. Add to the existing `LocationMatchContract`/`LocationMatch` test if one exists.
- A focused test that the forecast unification keeps fragment rows: feed `hourly_forecasts`-style rows
  at two sub-precision coordinates + one far marker, assert the unified set includes both fragments and
  excludes the marker. (If `WidgetRenderer`'s unification isn't easily unit-testable, extract the
  filter into a small pure helper and test that.)

## Verification

1. `./gradlew :shared:test testDebugUnitTest`.
2. Build + install (`:app:assembleDebug`; `adb -s <serial> install -r -d`). Devices: emulator-5554,
   Pixel (2A191FDH300PPW), Samsung (RFCT71FR9NT).
3. On device: open an anchored past-day hourly view (tap yesterday). Confirm the **forecast line spans
   the full day** and `NAN_TEMP_INDICES` no longer logs the morning indices for that render
   (pull `weather_database`, or watch logcat `TempExtrema`). Screenshot to confirm the dashed forecast
   curve is continuous.
4. **Re-check Issue 1** (daily-bar vs hourly-graph high/low, 72.4 vs 72.9): the actual-line obs blend is
   NOT exact-pinned, so this fix may not move it — capture `HOURLY_DAY_EXTREMA` vs `EXTREMA_WINDOW_DIAG`
   again and decide whether a separate obs-side investigation is needed.
5. Avoid `connectedDebugAndroidTest` (removes widgets); use `./scripts/emulator-tests.sh` if needed.

## Notes
- Tolerance rationale: fragments ≈ 0.0001°, distinct markers ≈ 0.005°; `0.002°` (~200m) sits safely
  between. Tunable, but must stay `≫` precision jitter and `≪` real marker spacing.
- Diagnostic logging from the prior session (`NAN_TEMP_INDICES`, `HOURLY_DAY_EXTREMA`, etc.) is still in
  place and useful for verifying this; trim once confirmed.

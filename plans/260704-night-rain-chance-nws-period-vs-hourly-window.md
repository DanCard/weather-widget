# Night rain chance: drop NWS-direct period branch, always use hourly 8pm–8am window

## Context

Tonight (2026-07-04) the widget shows a 9% night rain chance, but NWS hourly data says 14%
at 7am — which falls inside the app's advertised night window (8pm–8am). Verified on the
emulator DB (`backups/20260704_094216_sdk_gphone64_x86_64_emulator-5554`):

- NWS hourly PoP tonight: 0–2% through 4am, 9% at 5am, 12% at 6am, **14% at 7–8am**.
- NWS daily row: `nighttimePrecipProbability = 9` — NWS's native 12-hour "Tonight" period,
  which runs **6pm–6am**, so the 6–8am hours are excluded. Tomorrow's NWS daytime value is
  14 (the 7am rain got attributed there).

Root cause: `resolveDailyLabelPrecip` (`shared/.../shared/util/DailyRainLabels.kt:179-186`)
has a special branch — when the displayed source is NWS and the row is NWS, it trusts NWS's
period chance directly instead of the shared hourly 8pm–8am window max used for every other
source. That creates a 2-hour orphan zone (6–8am) where rain silently drops out of the night
label, and makes the effective night cutoff inconsistent across sources.

**Decision (user chose option 1):** delete the NWS-direct branch. All sources use the hourly
window max (`calculateDayNightPrecipProbabilities`, 8am–8pm day / 8pm–8am night), falling
back to the row's period fields only when there are no hourly rows. Tonight would show 14%.

Accepted trade-off: the branch originally existed because a sparse hourly set once made
desktop show 2% while NWS's period said 15% (test `directNwsUsesPeriodChanceOverSparseHourlyMax`).
Since commit f4f811ff ("Always request max forecast horizon") hourly coverage is full for the
forecast range, and per standing feedback (no hardcoded provider special-casing), uniform
hourly-window behavior wins.

## Changes

### 1. `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt` (core)

In `resolveDailyLabelPrecip`:

- Delete the `useDirectNwsPeriodPrecip` val and its `if` block (lines 179–186).
- Extend the general fallback chain for day so the old NWS `daytime ?: precipProbability`
  last resort isn't lost for any source:
  - `dayPrecip = dayNight.dayMax ?: daytimePrecipProbability ?: precipProbability`
  - `nightPrecip = dayNight.nightMax ?: nighttimePrecipProbability` (unchanged)
- Remove the now-unused `rowSourceId` parameter (the `isPast` branch doesn't use it either).
- Rewrite the KDoc (lines 149–159): no more NWS-direct exception; document that all sources
  use the 8am–8pm / 8pm–8am hourly window max with period-field fallback, and that this is
  deliberate so the night cutoff is uniformly 8am (NWS's native period boundary is 6am and
  would drop 6–8am rain from "tonight").

### 2. Call sites — drop the `rowSourceId` argument

- `app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt:56-70` — the Android
  wrapper `resolveDailyLabelPrecip`; also update its KDoc ("NWS-as-NWS uses NWS's native
  period chance" is no longer true). `weather?.source` stays available; just stop passing it.
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopDailyForecastModel.kt:244-253` —
  drop `rowSourceId = forecast?.source`; rewrite the comment block at 237–242 that documents
  the old NWS-direct behavior.

(Android's `DailyViewLogic.kt:214/444` call the wrapper, not the shared function — no change.)

### 3. Stale comment

- `shared/src/main/kotlin/com/weatherwidget/data/remote/NwsDailyMapper.kt:193-194` — the
  period fields are now a fallback, not "preferred over the sparse hourly max". Update the
  comment; the fields themselves stay (still used as fallback and by the past-day path).

### 4. Tests — `shared/src/test/kotlin/com/weatherwidget/shared/util/DailyRainLabelsTest.kt`

- `directNwsUsesPeriodChanceOverSparseHourlyMax` (line 279): behavior intentionally reverses.
  Rewrite as e.g. `nwsUsesHourlyWindowMaxLikeOtherSources` — NWS-as-NWS with hourly rows now
  returns the hourly window max (2%), not the period value (15%). Keep a comment noting the
  old behavior was deliberately dropped and why.
- `directNwsFallsBackToDailyPrecipWhenPeriodChanceMissing` (line 299): still passes (the
  `precipProbability` fallback moved into the general chain); rename to drop "directNws"
  since it now applies to all sources.
- **New regression test for tonight's bug**: NWS-as-NWS, hourly rows with 7am next-day = 14%
  (and e.g. 5am = 9%), period `nighttimePrecipProbability = 9` → `nightPrecip == 14`.
  This pins the 6–8am orphan-zone fix.
- `pastNwsNightOnlyChanceDoesNotLeakIntoDaytimeLabel` (line 357): still valid (isPast branch
  untouched) — trim the comment's reference to "must win over the NWS-direct branch".
- `nonNwsUsesHourlyWindowMaxWithPeriodFallback` and `pastDayReturnsPeriodFieldsForIconNotHourly`:
  unchanged.

## Verification

1. Unit tests:
   - `./gradlew :shared:test --tests "com.weatherwidget.shared.util.DailyRainLabelsTest"`
   - then full `./gradlew :shared:test :desktop:test testDebugUnitTest` (desktop shares the
     function; Android wrapper signature changes).
2. End-to-end on the emulator (source of the bug report):
   - `./gradlew installDebug`, trigger a widget refresh, screenshot via
     `adb exec-out screencap -p > /tmp/screenshot.png && convert /tmp/screenshot.png /tmp/screenshot.jpg`
     (read the JPG).
   - Expected: tonight's night rain label reads **14%** (was 9%), assuming NWS is the
     displayed source and the forecast hasn't shifted.
   - Cross-check against the DB: night label should equal the max NWS hourly `precipProbability`
     over 8pm tonight → 8am tomorrow (query with `'utc'` modifier on local-time literals).
3. Desktop parity (optional): `scripts/buildStart-desktop.sh`, confirm the same day's night %
   matches Android.

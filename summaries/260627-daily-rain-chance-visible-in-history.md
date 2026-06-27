# Daily forecast view: keep rain chance visible in history (+ Android/desktop parity)

**Date:** 2026-06-27
**Branch:** main
**Status:** Changes in working tree (not committed). Shared unit tests green. Desktop
rebuilt+restarted; Android rebuilt+reinstalled and widgets refreshed on both phones.
User confirmed phone and desktop now match.

## Problem

On the daily-forecast view, a day's rain-chance label vanished once the day moved into
the past. Concrete case (verified against live desktop + phone DBs): last night
(Jun 26) NWS forecast a **15%** night rain chance; no measurable rain fell, and when
Jun 26 became a past date the label disappeared.

## Root causes & fixes

### 1. Past-day labels were measured-amount-only (shared)

`DailyRainLabels.buildDailyRainLabel()` / `buildNightRainLabel()` had an early
`if (isPastDate)` branch returning only the observed measured amount, or null — the
forecast chance was never shown for past days (the deliberate "measured-only past days"
behavior from `nws_past_rain_measured_only`). A real forecast chance therefore silently
disappeared the moment the day turned into history.

**Fix:** past branches now fall back to the forecast chance % when no measurable rain
fell (measured amount still wins on days it actually rained). Gate `> 0`, so dry/zero-
chance history stays clean.

File: `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt`

### 2. Desktop plumbing gap — snapshot dropped day/night precip

Desktop's `forecast` (`DailyForecast`) is **null for past days** (the live daily list
holds only today + future), so the only precip source for a past day is its snapshot.
But `DailyForecastSnapshot` was a lossy projection of the `forecasts` table — it carried
only a single `precipProbability`, not the day/night split.

**Fix:**
- Added `daytimePrecipProbability` + `nighttimePrecipProbability` to
  `DailyForecastSnapshot` (`shared/.../data/model/ForecastTypes.kt`).
- Selected + mapped them in `DesktopWeatherDao.getDailyForecastSnapshots`
  (`shared/.../data/local/desktop/DesktopWeatherDao.kt`).
- In `DesktopDailyForecastModel.buildDay`, the `resolveDailyLabelPrecip` precip args now
  fall back to `displaySnapshot?.{daytime,nighttime,}PrecipProbability` when `forecast`
  is null (`desktop/.../DesktopDailyForecastModel.kt`).

Android already had this data via `weatherByDate` / snapshot `ForecastEntity` (which
carry the day/night fields), so the shared change in #1 was enough there.

### 3. Android/desktop parity bug — spurious daytime label (shared)

For a past **NWS-as-NWS** day, Android took `resolveDailyLabelPrecip`'s
`useDirectNwsPeriodPrecip` branch (`dayPrecip = daytime ?: precipProbability`) because
its row is source-tagged. With a night-only chance (`daytime=null`, night=15,
`precipProbability=15`), this manufactured a bogus **daytime "15%"** on the past bar —
while desktop (null forecast → `isPast` branch, no fallback) correctly showed only the
night label.

**Fix:** moved the `isPast` block **above** `useDirectNwsPeriodPrecip` in
`resolveDailyLabelPrecip`, so past days always use the raw day/night split (no
`precipProbability`→daytime fallback) on both platforms. One edit, both converge — the
recurring lesson from `feedback_share_android_desktop_logic`: fix the shared decision
point, not one platform's symptom.

## Tests

`shared/src/test/kotlin/com/weatherwidget/shared/util/DailyRainLabelsTest.kt`:
- `pastDayWithNoObservedRainShowsForecastChance` (was `...IsNull`) → "80%".
- `pastDayWithNoObservedRainAndZeroChanceIsNull` → null (dry history stays clean).
- `nightPastWithNoObservedRainShowsForecastChance` → "15%" (exact reported case).
- `pastNwsNightOnlyChanceDoesNotLeakIntoDaytimeLabel` → dayPrecip null, nightPrecip 15.

## Verification performed

- `./gradlew :shared:test --tests "...DailyRainLabelsTest"` — green.
- Desktop: `scripts/buildStart.sh` rebuild+restart; daily view showed night **15%** for
  Jun 26 (screenshot-confirmed). Display source = NWS.
- Android: `./gradlew installDebug` on both phones + `ACTION_REFRESH` broadcast. Device
  DB confirmed past-day row `daytime=null, night=15, precipProbability=15`. User
  confirmed phone now shows the night chance and matches desktop (no spurious daytime %).

## Files touched

- `shared/.../util/DailyRainLabels.kt` (past-day chance fallback; isPast branch ordering)
- `shared/.../data/model/ForecastTypes.kt` (snapshot day/night precip fields)
- `shared/.../data/local/desktop/DesktopWeatherDao.kt` (snapshot SELECT/mapping)
- `desktop/.../DesktopDailyForecastModel.kt` (snapshot precip fallback for past days)
- `shared/.../test/.../DailyRainLabelsTest.kt` (tests)

Plan file: `~/.claude/plans/daily-forecast-view-rain-humble-swan.md`

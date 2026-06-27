# Desktop daily-column tap: match Android routing (shared decision logic)

**Date:** 2026-06-27
**Branch:** main
**Status:** Changes in working tree (not committed). Unit tests green across `:shared:test`,
`:app:testDebugUnitTest`, and `:desktop:test`. Desktop distributable rebuilt and restarted via
`scripts/buildStart.sh`.

## Problem

On the desktop app, clicking **today** (or any day column) in the daily view opened the **rain
chance / precipitation** graph even when there was little or no rain. Android behaves differently:
a daily-column tap opens the precipitation graph **only** when the day reads as rain **and** the
daily precip probability is **≥ 16%**; otherwise it opens the **hourly temperature** graph. Android
also never opens the **cloud-cover** graph from a daily tap.

## Root cause

There are two distinct routing paths that are easy to conflate:

- **Daily-column tap** — Android `DayClickHelper.resolveDailyTargetViewMode(iconRes, precipProbability)`.
  Returns only `PRECIPITATION` (rain icon **and** prob ≥ 16) or `TEMPERATURE`. Never `CLOUD_COVER`.
- **Bottom-row icon tap on the hourly graph** — shared
  `WeatherConditionResolver.resolveIconHome(iconName)`. Returns `PRECIPITATION` / `CLOUD_COVER` /
  `HOURLY` purely from the icon name, with no probability gate.

Desktop's `dayClickConfig()` wired the **daily** tap into `WeatherIcon.resolveIconHome()` — the
**bottom-row** logic — so it (a) ignored the 16% gate and (b) routed cloudy days to cloud-cover.

The platforms can't share a return type (`ViewMode.TEMPERATURE` on Android vs `ViewMode.HOURLY` on
desktop) or icon type (`Int` drawable res on Android vs `String` icon name on desktop), so the
shared seam is the **decision predicate + threshold constant**; each platform maps the result to its
own enum.

## Fix

User directive: desktop should **match Android exactly** — cloudy-day daily taps open the
temperature graph; precip only for rainy + ≥16%.

1. **Shared** (`WeatherConditionResolver.kt`): added
   - `const val DAILY_CLICK_PRECIP_THRESHOLD = 16`
   - `shouldDailyClickShowPrecip(isRainIndicator: Boolean, precipProbability: Int?): Boolean`
     — platform-neutral gate; each platform feeds its own already-computed `isRainIndicator`.
   - `resolveDailyClickHome(iconName: String?, precipProbability: Int?): IconHome` — name-based
     convenience returning only `PRECIPITATION` or `HOURLY` (never `CLOUD_COVER`), for desktop.

2. **Android** (`DayClickHelper.resolveDailyTargetViewMode`): delegates to
   `WeatherConditionResolver.shouldDailyClickShowPrecip(...)`. Behavior unchanged (still 16%); the
   threshold/predicate now live in one shared place.

3. **Desktop** (`Main.kt` `dayClickConfig`): replaced the `WeatherIcon.resolveIconHome(...)` call
   with `WeatherConditionResolver.resolveDailyClickHome(clickedDay?.iconName, precipProb)`, mapping
   `IconHome.PRECIPITATION → ViewMode.PRECIPITATION` else `ViewMode.HOURLY`. Gates on the same precip
   the displayed icon used: `forecast?.precipProbability ?: snapshot?.precipProbability`. This drops
   the cloud-cover daily-tap path and adds the 16% gate. `WeatherIcon.resolveIconHome()` stays in
   place — still correct for bottom-row icon taps elsewhere.

## Tests

- **Shared** (new `WeatherConditionResolverDailyClickTest.kt`): rain + 16 → PRECIPITATION; rain + 15
  → HOURLY; rain + null → HOURLY; cloudy at any prob → HOURLY (never CLOUD_COVER); null icon → HOURLY.
- **Android**: existing `DayClickHelperTest.kt` passes unchanged (confirms behavior-preserving
  refactor).
- **Desktop** (`DesktopUiTest.testDayClickRoutesLikeAndroid`): rainy ≥16 → PRECIPITATION; rainy 15 →
  HOURLY; cloudy → HOURLY; snapshot-precip fallback when forecast precip is null → PRECIPITATION.

## Verification performed

- `./gradlew :shared:test :app:testDebugUnitTest :desktop:test` (scoped to the relevant classes) —
  all green; only pre-existing deprecation warnings.
- `scripts/buildStart.sh` — desktop distributable rebuilt and restarted.

## Files touched

- `shared/.../util/WeatherConditionResolver.kt` (threshold + 2 functions)
- `app/.../widget/handlers/DayClickHelper.kt` (delegate to shared predicate)
- `desktop/.../Main.kt` (`dayClickConfig` routes via shared daily-click resolver)
- `shared/.../test/.../WeatherConditionResolverDailyClickTest.kt` (new)
- `desktop/.../test/.../DesktopUiTest.kt` (new routing test + builder)

Plan file: `~/.claude/plans/daily-desktop-view-desktop-refactored-shamir.md`

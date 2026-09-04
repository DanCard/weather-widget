# Session summary — today's all-grey forecast bar: "overcast" word overriding the hourly cloud percent

**Date:** 2026-09-04 · **Plan:** none (small approved fix; RCA presented and approved in-session per
the review-fixes gate) · **Status:** fixed and verified on emulator; **not committed**

## What was reported

User (emulator, Meteo/Open-Meteo source): *"Today column: right bary is all grey. It should not be."*

Follow-up approval: *"I don't want the text to override the hourly cloud cover percent"* → approved
option B (graded downgrade) for **both** the "overcast" word and the plain "cloudy" word.

## Root cause (evidence-first)

The daily graph's today column is a triple bar: left = yesterday's snapshot forecast (yellow),
center = observed + current-temp bulb (pink), right = **today's forecast bar**. The right bar was
solid slate grey because of this chain:

1. Open-Meteo's **daily** row for 2026-09-04 is weathercode **3 = "Overcast"** (`forecasts`
   table, `nativeDailyIconToken=3`) — the daily code is a whole-day summary driven by the midnight
   hours (drizzle 00:00, 94–100% cloud 01:00). NWS said "Sunny"; Tomorrow.io said "Clear".
2. Open-Meteo's own **hourly** rows for the day are `Clear` with 1–15% cloud all morning, and
   `DailyViewLogic` measured noon cloud cover = **2%** (`cloudDecision` logcat line).
3. `DailyForecastIconResolver.resolveNativeTokenIcon` → `WeatherConditionResolver.resolveIconName`:
   the `overcast` branch returned `IC_CLOUDY` **unconditionally** — it never consulted cloudCover.
   The cc9670ab sub-overcast downgrade (`isSubOvercastCloudy`) only matched the word "cloudy", so
   "overcast" bypassed it; `applyPartlyCloudyFloor` only downgrades partly-cloudy icons.
4. With `ic_weather_cloudy` (not mixed, not sunny) the forecast color resolved to
   `FORECAST_CLOUDY` (slate grey). The 2% adaptive split existed but both platforms paint the top
   segment with the bar's own base color (grey) → all grey.

Future days had the same defect: Tue Sep 8 resolved `ic_weather_cloudy` with `measuredCloudCover=0`.

## The fix (shared — both platforms)

`shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherConditionResolver.kt` (`resolveIconName`):

- **"overcast" branch:** below `FULLY_CLOUDY_THRESHOLD` (97%) with a measured cover, resolve via
  `getCloudCoverIcon(isNight, cloudCover)` — the hourly percent picks the tier (0–25 mostly clear,
  26–74 partly cloudy, 75–90 mostly cloudy, 91–96 cloudy). ≥97% or null cover still trusts the word.
- **Plain "cloudy" downgrade** (`isSubOvercastCloudy`): same graded tiers instead of the flat
  mostly-cloudy cap.
- Untouched by design: "mostly/partly cloudy" words, precipitation words (rain/storm/snow still
  outrank cloud cover via their own suppression machinery), fog branches, sun-boundary horizon sun.

## Tests

- New: `shared/src/test/.../CloudWordHourlyCoverDowngradeTest.kt` — 17 assertions: both words,
  night tiers, sun boundary, threshold edges (95/97/100), null cover, exclusion of qualified words,
  rain precedence.
- Extended: `app/src/test/.../WeatherIconMapperTest.kt` — 6 new Android mapping cases
  ("Cloudy"/"Overcast" × graded covers). Existing 83% cases still pass (same tier under grading).
- All green: `:shared:test`, `:desktop:test`, `:app:testDebugUnitTest` (exit 0).

## On-device verification

Installed to emulator-5554 only (physical phone untouched). Post-install logcat:
`date=2026-09-04 icon=ic_weather_mostly_clear isMixed=true cloudCoverRatioOverride=0.02`.
Screenshot confirms:

- **Today** — gold bar with a small 2% grey tip; icon mostly-clear (sun + small cloud).
- **Tue Sep 8** (0% forecast cloud) — solid grey → gold.
- **Wed Sep 9** (100% cloud) — correctly stays grey.
- **Thu Sep 10** (49%) — graded gold-top/grey-bottom split.

## Side effect (flagged to user, accepted for now)

Past-day **forecast overlay** bars now also grade with measured noon cloud: Wed's overlay turned
gold-topped (forecast word said "Overcast" but noon cloud was 39%); Thu's stays grey (100%). Pink
actual bars untouched. If past-day overlays should keep trusting the frozen forecast word, that
needs a follow-up gate on `isPastDate`.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherConditionResolver.kt` (the fix)
- `shared/src/test/kotlin/com/weatherwidget/shared/util/CloudWordHourlyCoverDowngradeTest.kt` (new)
- `app/src/test/java/com/weatherwidget/util/WeatherIconMapperTest.kt` (new cases)

Pre-existing uncommitted work in the tree (DualHighLabel / DailyHighLabelPlanner /
DailyForecastGraph) was left untouched. No commit made — awaiting explicit request.

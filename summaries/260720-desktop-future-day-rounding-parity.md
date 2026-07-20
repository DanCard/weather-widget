# Desktop future-day temp rounding — parity with Android via shared code

**Date:** 2026-07-20

## Change

Desktop now rounds future-day forecast highs/lows to integers (today stays decimal), matching Android
— via a new shared rule so there is one source of truth.

- **New:** `shared/src/main/kotlin/com/weatherwidget/shared/util/ForecastTempRounding.kt` —
  `forStorage(temp, isToday)`: today keeps full decimals (accuracy tracking), future days round to the
  nearest integer (future-forecast noise reduction), non-finite (NaN/Infinity) → null.
- **Android:** `ForecastRepository.saveForecastSnapshot` now calls the shared rule (behavior unchanged;
  previously inlined `high?.roundToInt()?.toFloat()`).
- **Desktop:** `DesktopWeatherDao.upsertForecasts` applies it (climate normals stored as-is).
- **Tests:** shared rule unit test (`ForecastTempRoundingTest`, incl. the 90.61→91 case + NaN→null) and
  a desktop DAO round-trip integration test (`DesktopWeatherDaoTest`); full `:desktop` suite and Android
  `ForecastRepository` tests green.
- **Deployed:** rebuilt + restarted desktop (binary built 11:58). Rounding takes effect on the next
  Silurian forecast fetch — the currently-stored row was still the pre-change `90.61`, so the popup
  flips Tuesday `90.1 → 91` once that fetch lands (within the forecast-loop interval).

## Why

Motivated by an emulator-vs-desktop discrepancy: Silurian Tuesday read **91** on Android but **90.1** on
desktop. Ground truth (direct Silurian API call) was `32.561°C = 90.61°F`. The gap decomposed into
~0.46° of real forecast drift (desktop's value was an older fetch, 90.15) **plus** ~0.4° from Android's
future-day rounding (90.61 → 91) that desktop did not apply. Not a bug — but the user wanted both
platforms to store identical values, so the rounding rule was extracted to `:shared` and desktop opted
in. The rounding itself is deliberate (cuts future-forecast noise / snapshot churn); today keeps decimals.

## Insight

Parity through shared code, not parallel edits: the "today decimal / future integer / non-finite→null"
policy now has exactly one definition, so a future tweak changes both platforms at once and neither can
drift. The desktop reads the daily *field* for its bar (`solidHigh = forecast?.highTemp`), so rounding at
the storage boundary (`upsertForecasts`) is what actually moves the display.

## Notes

- Related: `plans/260720-desktop-panel-refresh-reliability.md` (separate work this session).
- Memory: `android_future_day_integer_rounding_deliberate` (updated — rule is now shared, both platforms round).
- Changes uncommitted at time of writing.

# Daily forecast view: keep rain chance visible in history

## Context

On the daily-forecast view, a day's rain-chance label vanishes once the day moves
into the past. Concrete case (verified against the live desktop DB on 2026-06-27):
last night (Jun 26) NWS forecast a **15%** night rain chance; no measurable rain
actually fell (observed night precip ≈ 0.007 mm). When Jun 26 became a past date the
label disappeared.

Root cause: `DailyRainLabels.buildDailyRainLabel()` and `buildNightRainLabel()` take an
early `if (isPastDate)` branch that returns **only the observed measured amount, or
null** — the forecast chance is never shown for past days. This was deliberate
(`nws_past_rain_measured_only`: past-day rain used to be forecast-amounts shown *as if
measured*, which was misleading). But it also means a real forecast chance silently
disappears the moment the day turns into history.

Desired outcome (per user): in history, **keep showing the forecast rain chance %**
(e.g. "15%") even when no measurable rain fell. Apply to **both** Android widget and
desktop via the shared logic. The reported 16% commit (9bd1eec1) is unrelated — it only
governs tap *navigation* routing, not label visibility.

Decision on days that actually rained: keep showing the **measured amount** (e.g.
".5in") when measurable rain was recorded — an actual measurement beats a probability —
and fall back to the forecast chance % only when nothing measurable fell. This fully
fixes the reported disappearance while not discarding the real actuals the accuracy
feature exists to surface. *(If you'd rather show the chance % even on days it rained,
say so and I'll drop the amount-precedence.)*

## Change

Single shared file: `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt`

Both call sites already feed the forecast chance into these functions, so **no plumbing
changes** are needed in `DailyViewLogic.kt` or `DesktopDailyForecastModel.kt` — only the
two `isPastDate` branches change.

### `buildDailyRainLabel` (around lines 210–215)

Replace:
```kotlin
if (isPastDate) {
    if (observedPrecipAmountMm != null && observedPrecipAmountMm > 0f) {
        return formatPrecipAmount(observedPrecipAmountMm)
    }
    return null
}
```
with:
```kotlin
if (isPastDate) {
    if (observedPrecipAmountMm != null && observedPrecipAmountMm > 0f) {
        return formatPrecipAmount(observedPrecipAmountMm)
    }
    // No measurable rain fell, but keep the forecasted chance visible in history.
    if (dayPrecipProbability != null && dayPrecipProbability > 0) {
        return "$dayPrecipProbability%"
    }
    return null
}
```

### `buildNightRainLabel` (around lines 251–256)

Mirror the same change using `nightPrecipProbability` / `observedNightPrecipMm`:
```kotlin
if (isPastDate) {
    if (observedNightPrecipMm != null && observedNightPrecipMm > 0f) {
        return formatPrecipAmount(observedNightPrecipMm)
    }
    if (nightPrecipProbability != null && nightPrecipProbability > 0) {
        return "$nightPrecipProbability%"
    }
    return null
}
```

### Gating notes
- Use a simple `> 0` floor (mirrors the existing "today" rule on line 220). Past days
  are *known* dates, so the distance-scaled future threshold (`4*daysFromToday+1`) does
  not apply. Clear days store 0% → no label, so this won't clutter dry history.
- Update the doc comments above each function (lines ~195–199 and ~239–243) to reflect
  "past days: observed amount, else forecast chance %".

## Tests

File: `shared/src/test/kotlin/com/weatherwidget/shared/util/DailyRainLabelsTest.kt`

- **`pastDayWithNoObservedRainIsNull` (line 69)** — currently asserts `null` for
  `dayPrecipProbability = 80, observedPrecipAmountMm = null`. Repurpose/rename to
  `pastDayWithNoObservedRainShowsForecastChance` asserting `"80%"`.
- Add a night counterpart: past day, `nightPrecipProbability = 15`,
  `observedNightPrecipMm = null` → `"15%"` (covers the exact reported case).
- Add a past-day-with-zero-chance case (`dayPrecipProbability = 0` / `null`, no observed)
  → still `null`, so dry days stay clean.
- Keep `pastDayShowsObservedAmount` / `nightPastShowsObservedAmount` green (measured
  amount still wins when rain fell).

## Verification

1. Unit tests: `./gradlew :shared:testDebugUnitTest --tests "com.weatherwidget.shared.util.DailyRainLabelsTest"`
   (or `:shared:test`).
2. Desktop end-to-end (live data already reproduces the case): rebuild + restart the
   desktop app (`scripts/buildStart.sh`), open the daily view, and confirm the Jun 26
   night column now shows **15%** in history. The forecast row carrying
   `nighttimePrecipProbability = 15` was confirmed present in `weather.db`.
3. Android: `./gradlew installDebug`, open the widget's daily view, navigate back one
   day, confirm the past day shows the forecast chance % instead of a blank.

## Out of scope
- Tap-navigation threshold (commit 9bd1eec1) — unrelated, no change.
- Forecast-snapshot selection for past days — the value flowing in is already the one the
  icon uses; not touched.

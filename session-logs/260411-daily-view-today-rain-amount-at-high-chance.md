# Session: Daily View — Today Rain Amount at High Chance of Rain

**Goal:** Show rain amount at the top of today's column in the daily graph when chance of rain is >= 95%
**Scope:** DailyViewLogic.kt, DailyViewLogicTest.kt, DailyViewHandler.kt (research only)

---

## Prompt 1: Feature Request

> Daily view. If chance of rain is greater than 95% for today add rain amount to top of today column.

### Research Phase

Explored the full daily view rendering pipeline:

1. **`DailyForecastGraphRenderer.kt`** (555 lines) — Renders the bitmap for 2+ row widgets. Each day is a `DayData` column drawn via `drawDayColumn()` (bottom stack: label, icon, low temp, rain label) and `drawDayBars()` (temp bars + high label). Rain labels are drawn by `drawDailyRainLabel()` which tries above the high-temp label first, then below the low-temp label.

2. **`DayData`** (line 53) — Already has `dailyPrecipProbability`, `dailyPrecipAmountMm`, and `dailyRainLabelText` fields. The renderer already handles placement for any non-null `dailyRainLabelText`.

3. **`buildDailyRainLabel()` in `DailyViewLogic.kt:401-415`** — The blocker: line 409 explicitly returns null for today: `if (date == today || isPastDate || !isRainIndicatorIcon(iconRes)) return null`. This means today never gets a rain label regardless of precipitation probability.

4. **Data model** — `ForecastEntity` has `precipProbability: Int?` (daily chance, 0-100) and `precipAmountMm: Float?`. Both are populated by all API sources (NWS, Open-Meteo, etc.).

5. **Today's precip probability in the call site** (line 343) — Today uses `todayNext8HourPrecipProbability` (max of next 8 hours from `HeaderPrecipCalculator`) instead of the full-day `weather?.precipProbability`. The daily probability is available from `weather?.precipProbability` but wasn't passed to `buildDailyRainLabel()`.

### Plan

Two changes to `DailyViewLogic.kt`, no renderer changes needed:

1. **`buildDailyRainLabel()`** — Add `dailyPrecipProbability: Int? = null` parameter. For today: if `dailyPrecipProbability >= 95` and `precipAmountMm != null`, return formatted amount (e.g., ".39in"). No rain-icon requirement (the >95% probability is the signal). Past days still return null. Future days unchanged.

2. **Call site** — Pass `dailyPrecipProbability = weather?.precipProbability` so today uses the full-day forecast probability.

Confirmed threshold with user: `>= 95%` (95%+ inclusive).

### Implementation

**`DailyViewLogic.kt`** — `buildDailyRainLabel()`:

- Added `dailyPrecipProbability: Int? = null` parameter
- Replaced the `date == today` early-return with a today-specific branch:
  - If `dailyPrecipProbability >= 95` and `precipAmountMm != null` → return `formatPrecipAmount(precipAmountMm)`
  - Otherwise → return null
- Past-date and future-day logic unchanged

**`DailyViewLogic.kt`** — call site (line 375):

- Added `dailyPrecipProbability = weather?.precipProbability` argument

**`DailyViewLogicTest.kt`** — updated existing test + new test:

1. Renamed `prepareGraphDays today omits graph rain label` → `prepareGraphDays today with 100 percent rain shows amount`. Updated assertion from `assertEquals(null, ...)` to `assertNotNull(...)`. The test data has `precipProbability = 100` and `precipAmountMm = 10f`, which now meets the >= 95% threshold.

2. Added `prepareGraphDays today with low rain chance omits rain label` — same setup but `precipProbability = 80`. Asserts `dailyRainLabelText` is null.

3. Added missing imports: `assertNotNull`, `assertNull`.

Build and all 864 tests passed.

---

## Prompt 2: Chance of Rain Calculation Explanation

> Top row chance of rain, daily view. Tell me how chance of rain is calculated.

### Answer Provided

The top-row precipitation probability (shown next to the current temperature in `R.id.precip_probability`) is calculated by `HeaderPrecipCalculator.getNext8HourPrecipProbability()`:

1. **Primary**: Filters hourly forecasts to the display source, takes the **next 8 hours** from now, returns the **maximum** `precipProbability` across those hours.
2. **Source fallback**: If display source has no hourly data, tries `GENERIC_GAP` source.
3. **Daily fallback**: If no hourly data in the 8-hour window, falls back to `todayWeather?.precipProbability` (the daily forecast value).
4. **Visibility**: Only shown when `HeaderTapTargetHelper.shouldShowPrecipTouchZone()` returns true (precip > 0). Text size scales with probability — smaller text for lower values.

Call site: `DailyViewHandler.kt:317-332`.

---

## Summary of All Changes

### Production Code (1 file)

| File | Change |
|------|--------|
| `DailyViewLogic.kt` | `buildDailyRainLabel()` gains `dailyPrecipProbability` parameter; today returns formatted rain amount when `dailyPrecipProbability >= 95` and `precipAmountMm != null`. Call site passes `weather?.precipProbability`. |

### Test Code (1 file)

| File | Change |
|------|--------|
| `DailyViewLogicTest.kt` | Updated existing test to expect non-null label at 100% precip. Added test for < 95% precip (null label). Added `assertNotNull`/`assertNull` imports. |

### Key Design Decisions

1. **>= 95% threshold** — User confirmed inclusive 95%. At this confidence level, the rain amount is actionable information worth displaying prominently.
2. **No rain-icon requirement for today** — Unlike future days (which require a rain/storm icon via `isRainIndicatorIcon`), today uses the raw probability as the signal. The full-day probability is authoritative regardless of what icon is shown.
3. **Full-day probability, not next-8-hour** — Today's `precipProbability` in the `DayData` uses the next-8-hour max (for the header display). The rain label uses `weather?.precipProbability` (the daily forecast value) passed as a separate `dailyPrecipProbability` parameter. This avoids conflating the two different probability windows.
4. **No renderer changes** — `drawDailyRainLabel()` already handles placement for any non-null `dailyRainLabelText`. The renderer doesn't know or care whether the label is for today or a future day.

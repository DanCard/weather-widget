# Session Log: Distant Rain Noise Suppression

**Date:** April 13, 2026
**Objective:** Reduce visual noise from low-probability rain indicators on distant forecast days in the daily view.

## User Prompts & Critical Decisions

### 1. Problem Identification
> **User:** Emulator: 20% chance of rain on daily view. I don't like it. Feels like noise. What do you think?

I analyzed the current behavior and proposed 4 options:
1. **Proximity-based threshold** — Hide rain icon+label for days >2 out unless probability >= 40%
2. **Shrink + fade label** — Scale font and opacity by probability/distance
3. **Hide label, keep icon** — Show rain icon but skip percentage text for low probabilities far away
4. **Combo** — Raise icon threshold for distant days AND shrink/fade label for marginal probabilities

### 2. User's Choice
> **User:** Increase threshold for > 3 days away. If > 3 days away don't show if 20% or less. When it is displayed <= 3 days away, shrink the font, maybe 35% smaller.

- **Days >3 away**: Suppress rain icon and label entirely when probability <= 20%
- **Days <=3 away (near-term)**: Show rain label at 65% font size (35% smaller)

## Technical Implementation Details

### Constants
- `DISTANT_RAIN_THRESHOLD_DAYS = 3L` — Days beyond this get suppression
- `DISTANT_LOW_PROB_THRESHOLD = 20` — Probability at or below this triggers suppression for distant days
- `NEAR_TERM_RAIN_FONT_SCALE = 0.65f` — Font scale factor for near-term rain labels

### File Changes

#### 1. `DailyForecastIconResolver.kt`
- Added `DISTANT_RAIN_THRESHOLD_DAYS` and `DISTANT_LOW_PROB_THRESHOLD` constants
- Extracted `shouldSuppressRainIcon(icon, precipProbability, daysFromToday, isNight)` private method that checks:
  - Existing 15% trace threshold (precipProbability <= 15) → always suppress
  - New distant threshold (>3 days AND precipProbability <= 20) → suppress to cloud icon
- Applied `shouldSuppressRainIcon` to both the native token path (line 25) and the condition-based fallback path (line 34)
- Previously only the native token path had the 15% check; now both paths are covered uniformly

#### 2. `DailyViewLogic.kt`
- Added `import java.time.temporal.ChronoUnit`
- In `buildDailyRainLabel()`: Added early return before `isRainIndicator` check:
  ```kotlin
  val daysFromToday = ChronoUnit.DAYS.between(today, date)
  if (daysFromToday > DISTANT_RAIN_THRESHOLD_DAYS && precipProbability <= DISTANT_LOW_PROB_THRESHOLD) return null
  ```
- In `prepareGraphDays()`: Computed `daysFromToday` and passed it to `DayData` constructor

#### 3. `DailyForecastGraphRenderer.kt`
- Added `NEAR_TERM_RAIN_FONT_SCALE = 0.65f` constant
- Added `import com.weatherwidget.util.DailyForecastIconResolver`
- Added `daysFromToday: Int = 0` field to `DayData` data class
- Modified `drawDailyRainLabel()`:
  - Detects near-term days (`daysFromToday in 1..3`)
  - Temporarily sets `paints.rainTextPaint.textSize` to `originalTextSize * 0.65f`
  - Uses try/finally to restore original text size after drawing
  - All measurement (text width, font metrics, placement) uses the scaled size

### Behavioral Changes

| Scenario | Before | After |
|:---|:---|:---|
| 20% rain, day 1-3 (near-term) | Rain icon + full-size "20%" label | Rain icon + 65% size "20%" label |
| 20% rain, day 4+ (distant) | Rain icon + full-size "20%" label | Cloud icon + no label |
| 50% rain, day 4+ (distant) | Rain icon + full-size "50%" label | Rain icon + full-size "50%" label |
| 15% rain, any distance | Cloud icon (existing 15% trace threshold) | Cloud icon + no label (unchanged behavior, now also covers label) |
| 99% rain, any distance | Rain icon + precip amount label | Rain icon + precip amount label (unchanged) |

### Side Effects
- **Click behavior**: Distant days with <=20% rain probability now tap into TEMPERATURE view instead of PRECIPITATION view, since `DayClickHelper.resolveDailyTargetViewMode()` uses `isRainIndicator()` which returns false for cloud icons
- **Text view path**: The `prepareTextDays()` path uses a separate `showRain` flag and `DayClickHelper.hasRainForecast()` with its own 8% threshold. That path is unaffected by these changes

## Tests Added

### DailyForecastIconResolverTest (5 new tests)
1. `distant day with 20 percent rain shows cloud icon instead of rain icon` — Day 4 with 20% precip → cloud icon
2. `near term day with 20 percent rain shows slight chance rain icon` — Day 2 with 20% precip → slight chance rain icon
3. `distant day with 50 percent rain still shows rain icon` — Day 5 with 50% precip → rain icon (above threshold)
4. `distant day with 15 percent rain shows cloud icon via trace threshold` — Day 7 with 15% precip → cloud icon (trace threshold)
5. `today with 20 percent rain shows slight chance rain icon` — Today with 20% precip → slight chance rain icon (near-term)

### DailyViewLogicTest (5 new tests)
1. `rain label suppressed for distant day with 20 percent probability` — Day 5 with 20% precip → null rain label
2. `rain label shown for near term day with 20 percent probability` — Day 2 with 20% precip → "20%" label
3. `rain label shown for distant day with 50 percent probability` — Day 5 with 50% precip → "50%" label
4. `rain label suppressed for day exactly 4 away with 20 percent probability` — Boundary: day 4 (above 3 threshold)
5. `rain label shown for day 3 away with 20 percent probability` — Boundary: day 3 (at threshold, still near-term)

## Verification
- All unit tests PASSED (including 875+ pre-existing and 10 new)
- `compileDebugKotlin` and `compileDebugUnitTestKotlin` both succeeded
- No regressions in existing rain label tests (16%, 65%, 99%, trace threshold cases all still pass)

## Plan File
`plans/260413-distant-rain-noise-suppression.md`
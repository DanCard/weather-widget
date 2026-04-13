# Distant Rain Noise Suppression

## Problem
A 20% rain probability on a day 7 days out shows both a rain-ish icon and a "20%" label on the daily view. This feels like noise — low-signal information crowding the display.

## User's Choice
- **Days >3 away**: Hide rain display entirely when probability ≤ 20%
- **Days ≤3 away (near-term)**: Show rain label, but shrink font ~35% smaller

## Constants
- `DISTANT_RAIN_THRESHOLD_DAYS = 3` — beyond 3 days from today, apply suppression
- `DISTANT_LOW_PROB_THRESHOLD = 20` — suppress if probability ≤ 20%
- `NEAR_TERM_RAIN_FONT_SCALE = 0.65f` — 35% smaller font for near-term rain labels

## Changes

### 1. `DailyForecastIconResolver.kt` — suppress rain icon for distant low-probability days
- In `resolveIcon()`, compute `daysFromToday = ChronoUnit.DAYS.between(now.toLocalDate(), targetDate)`
- After the existing 15% threshold check (line 27), add a second check:
  - If `daysFromToday > DISTANT_RAIN_THRESHOLD_DAYS` AND `weather.precipProbability <= DISTANT_LOW_PROB_THRESHOLD` AND the resolved icon `isRainIndicator`, return `getCloudCoverIcon(isNight, cloudCover)` instead
- This means a 20%-probability day 4+ days out shows a cloud icon, not a rain icon

### 2. `DailyViewLogic.kt` — `buildDailyRainLabel()` — suppress label for distant low-probability days
- The method already takes `date` and `today` parameters
- Add: if `date > today + 3 days` AND `precipProbability <= 20`, return `null` early (before the existing `isRainIndicator` check)
- This prevents the "20%" text from showing even if the icon somehow remains

### 3. `DailyForecastGraphRenderer.kt` — `DayData` — add `daysFromToday: Int`
- Add field `val daysFromToday: Int = 0` to `DayData`
- In `drawDailyRainLabel()`, scale the rain text paint size based on `daysFromToday`:
  - If `daysFromToday <= 3`, use `rainTextPaint.textSize * NEAR_TERM_RAIN_FONT_SCALE` (0.65x)
  - Otherwise, use `rainTextPaint.textSize` (but this path shouldn't be reached since distant low-prob labels are suppressed)
- Implementation: temporarily set `paints.rainTextPaint.textSize` before drawing, restore after

### 4. `DailyViewLogic.kt` — `prepareGraphDays()` — compute and pass `daysFromToday`
- For each day in the loop, compute `daysFromToday = ChronoUnit.DAYS.between(today, date).toInt()`
- Pass it to the `DayData` constructor

### 5. `DailyViewLogic.kt` — text view path (`prepareTextDays()`)
- The text view path in `DailyViewHandler` uses `showRain` and `rainSummary`, not `dailyRainLabelText`
- The `showRain` flag is set to `true` only for the first rain day index and uses `DayClickHelper.hasRainForecast()` which already has its own 8% threshold
- No changes needed here — the 8% threshold on the text view is separate from the visual noise concern on the graph view

### 6. Tests
- Add test: 20% probability on day 4+ returns null rain label and cloud icon
- Add test: 20% probability on day 1-3 still returns rain label (with smaller font indication) and rain icon  
- Add test: 50% probability on day 4+ still returns rain label (above threshold)
- Add test: 15% probability on day 1-3 is already suppressed by the existing 15% trace threshold
- Verify the existing "16%" test case at day 1 still shows "16%" label
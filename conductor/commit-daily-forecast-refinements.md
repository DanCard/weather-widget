# Plan: Commit and Push Daily Forecast Refinements

This plan covers staging, committing, and pushing the current changes to the `main` branch.

## Changes
- **Source Code**:
    - `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`: Dynamic rain label tucking based on absolute room; conditional nudging.
    - `app/src/main/java/com/weatherwidget/util/DailyForecastIconResolver.kt`: Refined precip threshold formula.
- **Tests**:
    - `app/src/test/java/com/weatherwidget/util/DailyForecastIconResolverTest.kt`: Updated thresholds.

## Proposed Commit Message
```text
Refine daily forecast night rain label tucking and precipitation thresholds

- Update DailyForecastGraphRenderer to calculate night rain label tuck intensity based on absolute vertical space available below the anchor point, rather than relative graph height. This provides more consistent fitting as the widget is resized.
- Implement conditional horizontal nudging for night rain labels: disable the nudge if the left temperature label is lower than the right one to prevent unnecessary shifting or collisions in the opposite direction.
- Refine precipitation probability thresholds in DailyForecastIconResolver to use the updated formula (4 * daysFromToday) + 1 for daytime forecasts, ensuring better consistency across lead times.
- Update corresponding unit tests in DailyForecastIconResolverTest to reflect the threshold formula changes.
- Enhance debug logging for rain label placement logic to include room measurements and collision flags.
```

## Implementation Steps
1. **Stage Changes**: `git add .`
2. **Commit**: `git commit -m "..."` (using the message above)
3. **Push**: `git push origin main`

## Verification
- Run `git status` after the commit to confirm a clean state.
- Verify the push with `git log -n 1 origin/main`.

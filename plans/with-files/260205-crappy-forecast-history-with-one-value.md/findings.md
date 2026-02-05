# Findings & Decisions

## Requirements
- Clicking a history bar in daily forecast should navigate to forecast-vs-actual graph (existing behavior).
- Improve graph presentation when forecast history contains only one value.

## Research Findings
- Click navigation is wired in `WeatherWidgetProvider` via `ForecastHistoryActivity` intents; issue is rendering quality, not navigation.
- `ForecastHistoryActivity` renders history using `ForecastEvolutionRenderer`.
- In `ForecastEvolutionRenderer`, X positions used `graphLeft + graphWidth * (maxDay - day) / dayRange`.
- When only one unique day exists, `maxDay == minDay`, `dayRange` is coerced to 1, and all X positions cluster at the left edge.

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Patch only renderer X-axis mapping | Issue is localized and fix should avoid side effects in activity/navigation |
| Center single-day data at mid-graph | Produces balanced visualization when only one history point exists |
| Keep existing day-range mapping for multi-day data | Preserves current timeline semantics for existing normal cases |
| Use one helper for label/grid/point X mapping | Prevents mismatched placement and keeps graph coherent |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Gradle sandbox permission denied for wrapper lockfile | Reran Gradle with escalated permissions |
| Incorrect Gradle task/options combo | Switched to `:app:testDebugUnitTest --tests ...` |

## Resources
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt`
- `app/src/main/java/com/weatherwidget/widget/ForecastEvolutionRenderer.kt`
- `task_plan.md`
- `progress.md`

## Visual/Browser Findings
- N/A (no image/browser usage in this task)

## Follow-up Design Update
- Implemented option 1 (`Single-point card mode`) directly in `ForecastEvolutionRenderer` for datasets with exactly one forecast value in high/low graph.
- This intentionally avoids drawing a misleading trend line when only one historical forecast exists.

## Follow-up Design Update 2
- Replaced single-point card mode with a graph-consistent horizontal-bar fallback for single forecast values.
- Rendering now uses one horizontal forecast bar plus center marker, with optional dashed actual reference line.

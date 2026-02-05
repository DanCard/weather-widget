# Findings & Decisions: Tuesday Icon Discrepancy

## Requirements
- Diagnose discrepancy between user memory (sunny) and app icon (cloudy) for Tuesday 2026-02-03.
- Use NWS API data as the source of truth for history.
- Use teach and explain mode.
- Create tests for the issue.

## Research Findings
<!-- Key discoveries during exploration -->
- **Root Cause Identified**: The `WeatherRepository.fetchFromNws` function sets the condition for historical observations to a hardcoded string `"Observed"`.
- **Icon Mapping Failure**: `WeatherIconMapper.getIconResource` does not recognize the string `"Observed"`. It falls through to the default case, which returns `R.drawable.ic_weather_unknown`.
- **Visual Discrepancy**: The `ic_weather_unknown` icon is visually a CLOUD. This makes every day in NWS history appear "Cloudy" to the user, even if it was sunny.
- **NWS API Limitation**: The `NwsApi.Observation` data class and the `getObservations` function currently only extract temperature, ignoring the weather description (like "Clear" or "Sunny") that the NWS API likely provides in `textDescription`.
- **Data Check**: In `restore_missing_history.sql`, Tuesday (2026-02-03) is indeed stored with `condition = 'Observed'` for the NWS source.

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| **Weighted Cloud Coverage** | Instead of a simple majority, calculate a 0-100% cloud score for the day based on hourly NWS observations to provide more granular icons. |
| **New Icon Assets** | Created `ic_weather_mostly_clear` (25%) and `ic_weather_mostly_cloudy` (75%) to support the requested granularity. |
| **Mapping Buckets** | 0-15%: Clear; 16-35%: 25% icon; 36-65%: 50% icon; 66-85%: 75% icon; 86-100%: Cloudy. |
| **Prioritize Daylight** | History calculation uses observations between 7 AM and 7 PM to match human perception. |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| String Matching Conflict | Fixed `when` block ordering in `WeatherRepository` to check for "mostly" before "sunny/cloudy" to avoid false matches. |
| Daylight Filter Timezone | Updated test timestamps to ensure they hit the 7 AM - 7 PM daylight window in local time. |
| "Observed" condition | Successfully replaced with dynamic descriptions like "Mostly Sunny (25%)". |

## Resources
-

## Visual/Browser Findings
<!-- CRITICAL: Update after every 2 view/browser operations -->
-

---
*Update this file after every 2 view/browser/search operations*

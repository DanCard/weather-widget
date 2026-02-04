# Findings & Decisions

## Requirements
- Trigger gap data when requested forecast date is further in the future than provider availability.
- Gap data must be **generic** and not API-specific (avoid `GAP_NWS`).
- Either API (NWS or other) should be able to use this generic gap data.

## Research Findings
- **Data Entities:**
    - `WeatherEntity`: Has `source` (String) and `isClimateNormal` (Boolean).
    - `ForecastSnapshotEntity`: Has `source` (String).
- **Current "Gap" Logic in `WeatherRepository.kt`:**
    - `fetchClimateNormalsGap` fetches from Open-Meteo Climate API.
    - Previously tagged with the requesting source (e.g., "NWS" or "Open-Meteo").
- **Generic Gap Implementation:**
    - Added `WidgetStateManager.SOURCE_GENERIC_GAP = "GENERIC_GAP"`.
    - Updated `WeatherRepository` to use this constant for all climate normal data.
    - Implemented fallback logic in `getCachedDataBySource` and `getForecastForDateBySource` so that any source request can fall back to the generic gap data.
    - Updated `WeatherWidgetProvider` and `TemperatureInterpolator` to be aware of the `GENERIC_GAP` source.

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Use `"GENERIC_GAP"` as the source string | Clearly identifies data as gap data while remaining provider-neutral. |
| Fallback in Repository | Allows providers to transparently "see" gap data for missing dates. |
| Mixed-source `mergeWithExisting` | Necessary because `fetchFromNws` now returns a list containing both "NWS" and "GENERIC_GAP" entities. |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| `mergeWithExisting` assumed single source | Updated to `groupBy { it.source }` and process each group. |

## Resources
- `WeatherRepository.kt`: Main logic for fetching and merging.
- `WeatherWidgetProvider.kt`: Widget UI data preparation.
- `WeatherGapTest.kt`: Unit tests for the new functionality.

## Visual/Browser Findings
- None.

---
*Update this file after every 2 view/browser/search operations*
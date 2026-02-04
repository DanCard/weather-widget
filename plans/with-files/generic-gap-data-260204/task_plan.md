# Task Plan: Generic Gap Data Fallback

## Goal
Implement a generic gap data fallback mechanism that provides weather data when a user requests a forecast beyond what the current provider (e.g., NWS) can supply.

## Current Phase
Phase 5: Delivery

## Phases

### Phase 1: Requirements & Discovery
- [x] Understand user intent
- [x] Analyze `WeatherDatabase` and related schemas
- [x] Identify where `gap_nws` is currently used (if at all)
- [x] Document findings in findings.md
- **Status:** complete

### Phase 2: Design & Architecture
- [x] Define the structure for "Generic Gap Data"
- [x] Design the fallback logic (`requestedDate > maxAvailableDate`)
- [x] Decide on naming conventions (generic, not API-specific)
- **Status:** complete

### Phase 3: Implementation
- [x] Update `WeatherRepository.saveForecastSnapshot` to use `"GENERIC_GAP"`
- [x] Update `fetchClimateNormalsGap` to use `"GENERIC_GAP"`
- [x] Implement fallback logic in `getForecastForDateBySource` and `getCachedDataBySource`
- [x] Update `mergeWithExisting` to handle mixed-source data lists
- [x] Ensure UI/Renderer can handle `"GENERIC_GAP"` source if needed (Updated `WeatherWidgetProvider`)
- [x] Update `ForecastHistoryActivity` to mention gap data if present
- [x] Update `TemperatureInterpolator` to handle `GENERIC_GAP`
- **Status:** complete

### Phase 4: Testing & Verification
- [x] Write unit tests for the fallback trigger in `WeatherRepository`
- [x] Verify `getCachedDataBySource` correctly merges sources
- [x] Document test results in progress.md
- **Status:** complete

### Phase 5: Delivery
- [x] Review all code changes
- [x] Ensure all requirements are met
- [x] Deliver to user
- **Status:** in_progress

## Key Questions
1. Should `GENERIC_GAP` data be shared between all locations? (Yes, it's location-specific).
2. How to handle existing `GAP_NWS` data? (Will be ignored by new logic, eventually expiring).

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Use `planning-with-files` | Complex task requiring persistent memory and multi-step execution. |
| Use `"GENERIC_GAP"` source | Follows user requirement for generic naming. |
| Grouping in `mergeWithExisting` | Handles the mixed-source list returned by `fetchFromNws` and `fetchFromOpenMeteo`. |
| Merging in `getCachedDataBySource` | Ensures UI always gets a complete range even if provider data is missing. |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| `replace` failed (expected 2 found 1) | 1 | Performed replacements individually or adjusted count. |

## Notes
- "Gap data should not be tagged gap_nws. It should be generic and not api specific."
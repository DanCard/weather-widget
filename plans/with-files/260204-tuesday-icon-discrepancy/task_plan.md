# Task Plan: Tuesday Icon Discrepancy Investigation

## Goal
Diagnose and explain why the history for Tuesday (2026-02-03) shows a "cloudy" icon despite the user remembering it being "very sunny," and ensure the fix is verified with tests.

## Current Phase
Phase 1: Discovery & Diagnosis

## Phases

### Phase 1: Discovery & Diagnosis
- [x] Research Tuesday's (2026-02-03) weather data from the local database.
- [x] Analyze the NWS API response for that period.
- [x] Identify the logic used to determine the icon from the NWS data.
- [x] Document findings in findings.md.
- **Status:** complete

### Phase 2: Planning & Test Creation
- [ ] Update `NwsApi.Observation` to include `textDescription`.
- [ ] Update `WeatherRepository.fetchDayObservations` to extract and return the most common condition for the day.
- [ ] Design a unit test `WeatherIconMapperTest` to verify that unknown conditions (like "Observed") map to a better fallback or that we fix the source data.
- [ ] Design a unit test in `WeatherRepositoryTest` or a new test file to verify that historical observations now include descriptions.
- [ ] Get explicit consent from the user for the plan.
- **Status:** in_progress

### Phase 3: Implementation & Verification
- [x] Create new vector drawables: `ic_weather_mostly_clear.xml` (25%) and `ic_weather_mostly_cloudy.xml` (75%).
- [x] Update `NwsApi.kt` to extract `textDescription` from observations.
- [x] Implement weighting and averaging logic in `WeatherRepository.fetchDayObservations`.
- [x] Update `WeatherIconMapper.kt` to handle the new icons and percentage-based mapping.
- [x] Run `WeatherIconMapperTest` and `WeatherRepositoryTest` (plus new `WeatherHistoryConditionTest`).
- [x] Verify Tuesday's history shows the correct icon.
- **Status:** complete

### Phase 4: Finalize & Explain
- [x] Provide a detailed explanation of the discrepancy and the fix to the user.
- [x] Final review of all changes.
- **Status:** complete

## Key Questions
1. What was the exact condition code or description returned by NWS for Tuesday?
2. How does the current code map NWS condition descriptions/icons to our internal icon representation?
3. Is there a mismatch between NWS's "cloudy" report and the actual conditions, or is our mapping incorrect?

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| Use `planning-with-files` | Complex task requiring systematic investigation and persistent memory. |

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
|       | 1       |            |

## Notes
- Use teach and explain mode.
- Get explicit consent before Phase 3.

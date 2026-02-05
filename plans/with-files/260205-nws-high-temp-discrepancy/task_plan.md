# Task: Diagnose NWS High Temperature Discrepancy

## Goal
Understand why the NWS API daily forecast high (73°F) differs from the maximum hourly temperature (72°F) for today and improve robustness of temperature handling.

## Phases
1. **Research & Codebase Investigation** <!-- id: 0 --> `complete`
   - Located NWS API fetch logic in `NwsApi.kt`.
   - Analyzed `WeatherRepository.kt` and confirmed integer truncation/parsing risks.
   - Verified the discrepancy in a real device database (Daily High 73, Hourly Max 72).
2. **Analysis of Root Causes** <!-- id: 1 --> `in_progress`
   - **Theory 1**: Top-of-hour sampling (NWS hourly data is top-of-hour, missing between-hour peaks).
   - **Theory 2**: Truncation error in Celsius to Fahrenheit conversion.
   - **Theory 3**: Inconsistent NWS API products (Grid vs. Zone forecasts).
3. **Implementation** <!-- id: 2 --> `complete`
   - [x] Improved parsing: Used `toDoubleOrNull()?.roundToInt()` in `NwsApi.kt` and `OpenMeteoApi.kt`.
   - [x] Fixed conversion: Switched to `Float` math and `roundToInt()` for Celsius to Fahrenheit conversions in `WeatherRepository.kt`.
   - [x] Updated data structures: Switched to `Float` for hourly/observation temperatures to maintain precision until rounding.
4. **Verification** <!-- id: 3 --> `complete`
   - [x] Created `NwsPrecisionTest` to verify float parsing and rounding.
   - [x] Updated `OpenMeteoApiTest` and `WeatherHistoryConditionTest` to match new precision logic.
   - [x] All tests passed.

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| Database empty in Pixel backup | 1 | Switched to Samsung backup which had data. |

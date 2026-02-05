# Findings - NWS High Temperature Discrepancy

## Codebase Research
- [x] Locate NWS fetch logic. Found in `NwsApi.kt` and `WeatherRepository.kt`.
- [x] Examine `WeatherDatabase` schema. `weather_data` table stores `highTemp` and `lowTemp` as `Int` (or nullable `Int`). `hourly_forecasts` stores `temperature` as `Float`.

## Discrepancy Analysis
- Reported: Daily High 73°F, Hourly Max 72°F.
- **Database Confirmation**: Querying the Samsung backup database confirmed a day where NWS `weather_data` has high=73 and `hourly_forecasts` max=72.0.
- **Top-of-Hour Sampling**: NWS hourly forecasts are data points at the start of each hour. The true daily high often occurs between hours and is included in the daily forecast summary but may be absent from the hourly top-of-hour samples.
- **Integer Parsing Risk**: `NwsApi.kt` uses `toIntOrNull()` for forecast temperatures. If the API returns a float string (e.g., "72.6"), it will be ignored or return null.
- **Celsius Conversion**: `NwsApi.kt` uses integer division `(temperature * 9 / 5) + 32` for Celsius to Fahrenheit conversion, which loses precision.

## Final Resolution
- **Conclusion**: The 1-degree discrepancy is primarily an artifact of NWS top-of-hour sampling (hourly) vs. estimated peak (daily). However, the app had a secondary bug where integer truncation in parsing and Celsius conversion could lose an additional degree.
- **Fixes Applied**:
  1. All temperature parsing now uses `toDoubleOrNull()?.roundToInt()` or `toFloat()` to handle floating point data from APIs.
  2. Celsius-to-Fahrenheit conversion now uses `Float` math (`* 1.8f + 32f`) and `roundToInt()` to avoid the 1-degree truncation error.
  3. `NwsPrecisionTest` verifies that high-precision data (e.g., 22.8°C) is now correctly converted to 73°F instead of 71°F or 72°F.

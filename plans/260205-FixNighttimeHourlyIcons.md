# Plan: Switch to Civil Twilight for Day/Night Visuals

## Problem
The user reports that 7 AM in February (when sunrise is ~7:08 AM) is shown as "Night" with a moon icon, but they perceive it as "Day" (likely because of twilight).
- Current Logic: Uses Official Sunrise (Zenith 90.833°), so 7:00 AM < 7:08 AM = Night.
- Desired Logic: Use Civil Twilight (Zenith 96.0°), so Dawn starts earlier (e.g. ~6:40 AM), making 7:00 AM = Day.

## Proposed Changes

### 1. Update `SunPositionUtils.kt`
- Change the zenith constant from `90.833` to `96.0` (Civil Twilight).
- This will make the "Day" period start earlier (Dawn) and end later (Dusk), covering the twilight periods where it is light enough to see "Day" icons.

### 2. Update `HourlyGraphRenderer.kt` and `WeatherWidgetProvider.kt`
- No changes needed here; they already rely on `SunPositionUtils`. The underlying definition of "Night" will simply shift.

## Verification
- Run the python script with Zenith 96.0 to confirm 7 AM is now "Day".
- Run existing unit tests to ensure no regressions for obvious Day/Night times.
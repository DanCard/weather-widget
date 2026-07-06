# Plan: Increase Font Sizes in Desktop Weather Observations Window

## Overview
Increase text font sizes in the **Desktop Weather Observations & Logs** window, setting temperature and reported/fetched time displays to `32.sp` for high legibility.

## Target Files & Changes

### Desktop Compose App (`desktop/src/main/kotlin/com/weatherwidget/desktop/`)
- `ObservationsWindow.kt`:
  - Header Title ("Stations"): set `fontSize = 24.sp`
  - API Source Button: set `fontSize = 18.sp`
  - Dropdown Menu Items: set `fontSize = 18.sp`
  - Tab titles ("Observations", "Fetch Logs"): set `fontSize = 18.sp`
  - Observation Card (`ObservationList`):
    - `stationName`: `21.sp`
    - `condition`: `17.sp`
    - `temperature`: set to **`32.sp`**
    - `stationId • distance`: `18.sp`
    - `stationType` badge: `16.sp`
    - Reported & Fetched time values: set to **`32.sp`** (base text `18.sp`)
    - Empty state message: `18.sp`
- `LogList.kt`:
  - Log timestamp: `14.sp`
  - Log tag badge: `13.sp`
  - Log message: `17.sp`
  - Empty state message: `18.sp`

## Verification Strategy
1. Run `./gradlew :desktop:compileKotlin` to verify desktop compilation.

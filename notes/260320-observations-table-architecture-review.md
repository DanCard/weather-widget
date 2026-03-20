# Observations Table Architecture Review

**Date**: 2026-03-20
**Context**: Post-migration review after deleting `current_temp` table and unifying on `observations` (DB v36 -> v37)

## Writers (7 paths)

| Writer | Station ID Pattern | stationType |
|--------|-------------------|-------------|
| `ObservationRepository.fetchNwsCurrent()` | Real station IDs (e.g., `KSFO`) | `OFFICIAL`/`METAR`/etc. |
| `ObservationRepository.fetchNwsCurrent()` | `NWS_MAIN` (IDW blend) | `BLENDED` |
| `CurrentTempRepository` -> Open-Meteo | `OPEN_METEO_MAIN` | `OFFICIAL` |
| `CurrentTempRepository` -> WeatherAPI | `WEATHER_API_MAIN` | `OFFICIAL` |
| `CurrentTempRepository` -> Silurian | `SILURIAN_MAIN` | `UNKNOWN` |
| `CurrentTempRepository` -> Open-Meteo POIs | `OPEN_METEO_HIST_*` | `OFFICIAL` |
| `CurrentTempRepository` -> WeatherAPI POIs | `WEATHER_API_HIST_*` | `OFFICIAL` |

## Readers (6 paths)

- `getLatestMainObservations()` -- widget rendering (11 call sites across WidgetIntentRouter, WeatherWidgetProvider, WeatherWidgetWorker, WeatherObservationsActivity)
- `getRecentObservations()` -- WeatherObservationsActivity list display
- `getObservationsForStation()` -- hourly graph rendering
- `getObservationsBetween()` -- daily extremes calculation
- `getOldestObservation()` -- retention/cleanup

## Findings

### 1. stationType inconsistency

| Source | stationType value |
|--------|------------------|
| NWS real stations | Enum values (`OFFICIAL`, `METAR`, etc.) |
| `NWS_MAIN` | `BLENDED` |
| Open-Meteo / WeatherAPI `_MAIN` | `OFFICIAL` |
| Silurian `_MAIN` | `UNKNOWN` |

Cosmetic only -- `stationType` is displayed in the observations debug screen badge. No logic branches on it. Silurian using `UNKNOWN` while others use `OFFICIAL` is inconsistent. Low priority fix (one-line change in `CurrentTempRepository`).

### 2. Index coverage is good

The new `getLatestMainObservations()` query filters on `stationId LIKE '%_MAIN'`, `locationLat`, `locationLon`, and `timestamp`. The existing composite index on `(timestamp, locationLat, locationLon)` covers this well enough for the small result sets involved.

### 3. No orphaned references

All `CurrentTempEntity`/`CurrentTempDao` references successfully removed. Codebase compiles clean and all tests pass.

## Station ID Conventions

| Pattern | Meaning | Example |
|---------|---------|---------|
| Raw station ID | Real NWS station | `KSFO`, `KSJC` |
| `*_MAIN` | Blended/primary current reading | `NWS_MAIN`, `OPEN_METEO_MAIN` |
| `*_HIST_*` | Point-of-interest historical | `OPEN_METEO_HIST_37.774_-122.419` |

Source inference: `ObservationResolver.inferSource(stationId)` maps prefixes to WeatherSource IDs, replacing the old `current_temp.source` column.

## Conclusion

Architecture is clean post-migration. The observations table successfully serves as the single source of truth for all current temperature data, eliminating the dual-table inconsistency that caused temperature jumps between DAILY and TEMPERATURE views.

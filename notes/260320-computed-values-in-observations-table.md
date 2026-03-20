# Computed Values in Observations Table

**Date**: 2026-03-20
**Context**: Architecture question — should computed values be stored in the observations table, or computed on-the-fly?

## What's Being Written

| Station ID | Source | Computed? |
|------------|--------|-----------|
| `NWS_MAIN` | IDW blend of multiple NWS stations | **Yes** — spatial interpolation |
| `OPEN_METEO_MAIN` | Direct API reading | No — raw value |
| `WEATHER_API_MAIN` | Direct API reading | No — raw value |
| `SILURIAN_MAIN` | Direct API reading | No — raw value |

Only `NWS_MAIN` is truly computed. The others are direct API readings stored with a naming convention.

## Case for Computing NWS Blend On-the-Fly

- The observations table should represent **what was observed**, not what was derived. `NWS_MAIN` is neither a station nor an observation — it's an interpolation artifact.
- The raw station data (`KSFO`, `KSJC`, etc.) is already in the table. The blend can be recomputed from it.
- Mixing raw and synthetic data in the same table creates semantic ambiguity (e.g., the `stationType = "BLENDED"` exception).

## Case for Keeping It Materialized

- Widget rendering happens frequently and needs to be fast — a single DB read vs. re-running IDW interpolation each time.
- The blend depends on having the right set of stations in memory. At render time, you'd need to query all nearby NWS stations and re-run `SpatialInterpolator.interpolateIDW()`.
- The blend is only computed once per fetch cycle (~every 5-60 min). It's read 11+ times per render cycle. That's a heavy read:write ratio favoring materialization.

## Current Assessment

For the non-NWS sources, writing `_MAIN` entries is fine — they're real readings, not computed. For `NWS_MAIN`, the materialization is pragmatic given the read-heavy widget rendering pattern.

**Alternative if desired**: Compute the blend in `ObservationResolver` at read time by querying raw NWS stations and running IDW there. The `getLatestMainObservations()` query would then only return the 3 non-NWS `_MAIN` entries, and NWS would be handled separately. More code for marginal purity.

## Decision

Keeping materialized `NWS_MAIN` for now. The read:write ratio and widget rendering performance justify the tradeoff.

# Flat observed cloud-layer schema — v2

**Date:** 2026-08-27
**Status:** Approved for schema/model implementation
**Scope:** Shared observation model, Android Room, desktop SQLite, persistence mapping, and focused
schema tests. Provider population, blending, and rendering remain deferred.

## Decision

Use one graph-friendly `observations` row per station/timestamp and add nullable scalar columns.
Do not create an `observation_cloud_layers` child table.

The flat layout is intentionally sparse. SQLite nulls have no value payload, and avoiding a child
table also avoids repeating the five-column observation key and adding another index/B-tree.

## Schema

Add these columns to `observations` on Android and desktop:

```sql
cloudCoverMid INTEGER,
cloudCoverHigh INTEGER,
cloudBaseLowMeters INTEGER,
cloudBaseMidMeters INTEGER,
cloudBaseHighMeters INTEGER,
cloudEnvelopeBaseMeters INTEGER,
cloudEnvelopeTopMeters INTEGER,
cloudVerticalKind INTEGER NOT NULL DEFAULT 0
```

Existing `cloudCover` and `cloudCoverLow` remain unchanged. No new indexes are needed: graph reads
already select observations by time, location, and provider.

Heights use whole meters. Provider m/km/ft values will eventually be validated and rounded to the
nearest meter before entering the model; truncation is not allowed. Integer meters avoid floating
point boundary noise and usually occupy fewer SQLite payload bytes than `REAL`.

## Stable enum contract

Add a shared enum with explicit database codes:

```kotlin
enum class CloudVerticalKind(val dbCode: Int) {
    NONE(0),
    PROVIDER_BANDS(10),
    CUMULATIVE_LAYERS(20),
    TOTAL_ENVELOPE(30),
    OTHER(127),
}
```

Rules:

1. Never persist `ordinal`.
2. Unknown database integers map to `OTHER` rather than throwing.
3. `NONE` means this row carries no vertical cloud representation.
4. `OTHER` means it carries an unrecognized/future representation.
5. Keep the SQLite column unconstrained so a newer writer's code does not make an older schema
   reject the entire observation.

## Field semantics

| Field | Meaning |
|---|---|
| `cloudCoverMid` | Middle-band graph value, 0–100, when a provider/mapping supplies one |
| `cloudCoverHigh` | High-band graph value, 0–100, when a provider/mapping supplies one |
| `cloudBaseLowMeters` | Representative reported base paired with the low-band value |
| `cloudBaseMidMeters` | Representative reported base paired with the middle-band value |
| `cloudBaseHighMeters` | Representative reported base paired with the high-band value |
| `cloudEnvelopeBaseMeters` | Lowest altitude of a provider's total-cover envelope |
| `cloudEnvelopeTopMeters` | Highest altitude of a provider's total-cover envelope |
| `cloudVerticalKind` | How graph/read code must interpret the vertical values |

Null means unknown/not supplied, never clear. Explicit clear values remain percentage zero or an
eventual provider mapping that deliberately produces zero.

## Phase 1 implementation

### Shared model

1. Add `CloudVerticalKind` beside the shared forecast/observation models.
2. Add all new nullable fields and `cloudVerticalKind = NONE` to `ObservationReading`.
3. Add focused tests for stable codes and unknown-code fallback.

### Android Room 68 → 69

1. Add the new fields to `ObservationEntity` with `CloudVerticalKind` represented in Kotlin.
2. Add a Room type converter that persists `CloudVerticalKind.dbCode` as an integer and maps
   unknown integers to `OTHER`.
3. Register the converter at the database boundary.
4. Bump Room from 68 to 69.
5. Add `MIGRATION_68_69` containing seven nullable `INTEGER` columns and the non-null kind column
   with default zero.
6. Register the migration and export schema 69.
7. Plumb all new fields through entity/reading conversion without deriving values.

### Desktop SQLite 22 → 23

1. Add the columns to fresh-install `observations` DDL.
2. Add the columns through the `from < 23` additive migration.
3. Set `SCHEMA_VERSION = 23`.
4. Add the fields to `DesktopObservationEntity` and shared conversion.
5. Extend observation INSERT and every ResultSet mapping with the new columns.
6. Persist `cloudVerticalKind.dbCode`; decode through the shared unknown-safe lookup.

## Phase 1 tests

1. Shared enum test pins every explicit database code and unknown → `OTHER`.
2. Android entity conversion round-trip preserves every new value.
3. Android instrumented migration 68→69 verifies column names, affinities, nullability/default,
   legacy-row defaults, and a fully populated row.
4. Desktop migration 22→23 verifies the same schema/default behavior.
5. Desktop DAO round-trip verifies all values and unknown-kind fallback.
6. Existing observation reads still return one row per observation.
7. Every new test class has exactly one duration category.

## Deferred provider rules

These rules guide later work but are not part of the schema implementation:

1. Open-Meteo analysis may populate provider-supplied low/mid/high percentages with
   `PROVIDER_BANDS`; base fields remain null.
2. NWS, Aviation Weather, and Synoptic may bucket reported bases into low (<3,000 m), middle
   (3,000–7,999 m), and high (≥8,000 m), storing one representative cumulative layer per band with
   `CUMULATIVE_LAYERS`.
3. Missing METAR layers remain null. In particular, an automated report's detection limit must not
   turn absent middle/high cloud into zero.
4. Tomorrow.io may populate total cover plus envelope base/top with `TOTAL_ENVELOPE`; band values
   remain null unless Tomorrow.io supplies true band percentages.
5. `rawMetar` remains the detailed diagnostic record when more than one layer falls in a band.

## Deferred graph work

1. Selecting and blending low/mid/high actual series.
2. Styling `PROVIDER_BANDS` versus `CUMULATIVE_LAYERS` so unlike semantics are visible.
3. Plotting representative base heights or Tomorrow.io envelopes.
4. Changing the existing visible low-cloud actual curve.
5. Resolving the current 2 km METAR low threshold against Open-Meteo's 3 km forecast threshold.

## Verification

```bash
./gradlew :shared:testShortShared
./gradlew :app:testShortDebugUnitTest
./gradlew :desktop:testShortDesktop
./scripts/emulator-tests.sh -c com.weatherwidget.data.local.WeatherDatabaseMigrationTest
git diff --check
```

Runtime provider/graph verification is required in its later implementation phase, after those code
paths begin populating or displaying the new columns.

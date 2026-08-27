# Observed cloud-layer schema

**Date:** 2026-08-27
**Status:** Schema proposal only — awaiting discussion and approval
**Scope:** Shared observation model plus Android Room and desktop SQLite persistence. Provider
fetching, blending, graph rendering, and UI are deliberately deferred.

## Goal

Persist the two different kinds of vertical cloud information that actuals providers expose without
pretending they are the same measurement:

1. **Banded percentages** — total, low, middle, and high cloud-cover percentages supplied directly
   by a provider such as Open-Meteo analysis.
2. **Reported layers** — an ordered station report such as `FEW010 BKN100`, where each layer has a
   categorical cumulative sky-cover amount and a base height.

This schema phase should retain information faithfully. It should not yet decide how a graph turns
reported layers into a visual comparison with Open-Meteo's independent low/mid/high percentages.

## Why two extra columns alone are insufficient

Adding only `observations.cloudCoverMid` and `cloudCoverHigh` would fit Open-Meteo, but it would lose
the most useful part of NWS, Aviation Weather, and Synoptic observations: their reported layer base
heights. It would also encourage mapping a cumulative METAR code such as `BKN100` to an independent
75% middle-cloud value, which the report does not assert.

Conversely, storing only layer rows would make a provider-native `cloudCoverMid = 62` awkward and
would discard the fact that it is a true band percentage rather than a categorical station layer.

The proposed schema therefore uses nullable percentage columns on the observation parent and a
normalized child table for reported layers.

## Current schema boundary

Android Room is version **68** and desktop SQLite is version **22**. Both `observations` tables use
the same five-column identity:

```sql
PRIMARY KEY (stationId, timestamp, locationLat, locationLon, api)
```

The location and `api` fields must remain part of cloud-layer identity. The same physical station
and report may be stored independently under NWS, METAR, or Synoptic provenance, and rows fetched
for separate widget locations must not overwrite one another.

## Proposed parent-table change

Add two nullable columns to `observations` on both platforms:

```sql
ALTER TABLE observations ADD COLUMN cloudCoverMid INTEGER;
ALTER TABLE observations ADD COLUMN cloudCoverHigh INTEGER;
```

The complete cloud percentage contract becomes:

| Column | Meaning | Allowed absence |
|---|---|---|
| `cloudCover` | Provider-supplied total-column percentage | `NULL` means not supplied |
| `cloudCoverLow` | Provider-supplied or deliberately derived low-cloud percentage | `NULL` means not supplied/unknown |
| `cloudCoverMid` | Provider-supplied middle-band percentage | `NULL` means not supplied/unknown |
| `cloudCoverHigh` | Provider-supplied high-band percentage | `NULL` means not supplied/unknown |

All four values remain nullable integers in the 0–100 domain. Validation stays at the mapper
boundary, consistent with the existing columns; the migration does not rewrite old rows or turn
missing values into zero.

For this first phase, METAR-style sources should leave `cloudCoverMid` and `cloudCoverHigh` null.
Only a source that actually supplies band percentages may populate them. Whether the existing
METAR-derived `cloudCoverLow` name should later become `cloudCoverBelowThreshold` is a separate
compatibility discussion, not part of this schema migration.

## Proposed child table

Add `observation_cloud_layers` on Android and desktop:

```sql
CREATE TABLE observation_cloud_layers (
    stationId TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    locationLat REAL NOT NULL,
    locationLon REAL NOT NULL,
    api TEXT NOT NULL,
    layerIndex INTEGER NOT NULL,
    baseMeters REAL,
    topMeters REAL,
    coverCode TEXT,
    coverPercent INTEGER,
    representation TEXT NOT NULL,
    PRIMARY KEY (
        stationId,
        timestamp,
        locationLat,
        locationLon,
        api,
        layerIndex
    ),
    FOREIGN KEY (
        stationId,
        timestamp,
        locationLat,
        locationLon,
        api
    ) REFERENCES observations (
        stationId,
        timestamp,
        locationLat,
        locationLon,
        api
    ) ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE INDEX index_observation_cloud_layers_time_location
ON observation_cloud_layers (timestamp, locationLat, locationLon, api);
```

### Child-column semantics

| Column | Meaning |
|---|---|
| `layerIndex` | Provider/report order, starting at zero; preserves ascending METAR layer order |
| `baseMeters` | Cloud-base height above ground level; nullable when the report omits or cannot decode it |
| `topMeters` | Cloud-top or envelope-top height when a provider supplies one; normally null for METAR |
| `coverCode` | Original categorical amount such as `CLR`, `FEW`, `SCT`, `BKN`, `OVC`, or `VV` |
| `coverPercent` | Provider-supplied numeric layer/envelope percentage, if any; not a forced conversion of `coverCode` |
| `representation` | Stable semantic discriminator described below |

Initial `representation` values:

1. `REPORTED_LAYER` — NWS, Aviation Weather, or Synoptic categorical layer with a base height.
2. `VERTICAL_ENVELOPE` — a provider product such as Tomorrow.io total cover plus base/top. This is
   not an independent cloud layer and must not be interpreted as one.

Do not put low/mid/high band rows in this table. Those are fixed semantic fields already represented
by the four parent percentage columns. Keeping bands on the parent makes hourly observation queries
cheap and prevents mixing an atmospheric band with a reported cloud base.

`coverCode` is intentionally not constrained to today's known abbreviations. Unknown future or
international codes should be retained and surfaced by validation/logging rather than making an
entire observation insert fail. Range and finite-number checks for heights and percentages likewise
belong in shared mapper validation before persistence.

## Shared model shape

Extend the pure shared observation model:

```kotlin
enum class ObservedCloudRepresentation {
    REPORTED_LAYER,
    VERTICAL_ENVELOPE,
}

data class ObservedCloudLayer(
    val layerIndex: Int,
    val baseMeters: Double?,
    val topMeters: Double? = null,
    val coverCode: String? = null,
    val coverPercent: Int? = null,
    val representation: ObservedCloudRepresentation,
)

data class ObservationReading(
    // existing fields unchanged
    val cloudCover: Int? = null,
    val cloudCoverLow: Int? = null,
    val cloudCoverMid: Int? = null,
    val cloudCoverHigh: Int? = null,
    val cloudLayers: List<ObservedCloudLayer> = emptyList(),
)
```

An empty `cloudLayers` list means no structured layer information was supplied. It does not mean a
clear sky. Clear is represented explicitly by the provider data, for example a `REPORTED_LAYER`
whose `coverCode` is `CLR`, or by an explicit provider percentage of zero.

## Android Room 68 → 69

1. Add nullable `cloudCoverMid` and `cloudCoverHigh` properties to `ObservationEntity`.
2. Add `ObservationCloudLayerEntity`, using the six-column primary key above and a composite Room
   `ForeignKey` back to `ObservationEntity` with cascade delete/update.
3. Add the child entity to `WeatherDatabase.entities` and bump the version from 68 to 69.
4. Implement `MIGRATION_68_69` with the two `ALTER TABLE` statements, child-table DDL, and index.
5. Register the migration and export `app/schemas/.../69.json`.
6. Introduce an `ObservationWithCloudLayers` relation or an explicit DAO assembly query. Keep raw
   parent-only reads available where temperature/precipitation code does not need cloud layers.
7. Replace the cloud-aware write boundary with one `@Transaction` operation:
   insert/replace parent rows, delete their prior child rows, then insert the new child rows.

The write order matters. Room's existing `OnConflictStrategy.REPLACE` deletes and recreates the
parent row; with a cascading foreign key that also deletes the old children. The transaction must
therefore insert the replacement parent before inserting its new layers.

Existing rows receive null mid/high values and zero child rows. Do not attempt to parse every
legacy `rawMetar` inside the SQL migration. The observation retention window is short and new
fetches will repopulate structured layers. A separately tested application-level reparse can be
considered only if retaining the immediately preceding history is important.

## Desktop SQLite 22 → 23

1. Add the two nullable parent columns to fresh-install DDL and the `from < 23` migration.
2. Add the child table and index to fresh-install DDL and migration code.
3. Set `SCHEMA_VERSION = 23`.
4. Enable `PRAGMA foreign_keys = ON` for every connection before any write. SQLite does not enforce
   declared foreign keys unless this connection-level pragma is enabled.
5. Add mid/high fields and structured layers to the desktop entity/model mapping.
6. Update `upsertObservations` to replace the parent and its children in the existing database
   transaction, using the same write order as Android.
7. Update observation reads that need cloud layers to fetch/group the child rows by the complete
   five-column parent identity. Avoid an unrestricted SQL join for ordinary temperature reads,
   because one parent with multiple layers would otherwise duplicate `ObservationReading` rows and
   corrupt blending counts.

Cascade deletion will then cover normal observation cleanup and provider-specific deletes. Tests
must prove this; otherwise an explicit child cleanup must accompany every parent delete.

## Provider population after the schema phase

This section records intended ownership but is not authorized for implementation in the schema-only
phase.

1. **NWS and Aviation Weather:** persist every parsed METAR layer as `REPORTED_LAYER`, retaining
   order, amount code, and base. Do not populate parent mid/high percentages from these rows.
2. **Synoptic:** parse and persist `cloud_layer_1`, `cloud_layer_2`, and `cloud_layer_3`; raw METAR
   remains the preferred complete report when available.
3. **Open-Meteo elapsed analysis:** copy provider-supplied total/low/mid/high percentages to the
   parent observation. Create no child rows.
4. **Tomorrow.io:** if base and ceiling are requested later, store one `VERTICAL_ENVELOPE` child
   carrying base, top, and the provider's total cover. Do not split that cover among bands.
5. **Silurian and unsupported sources:** leave all new fields null/empty.

## Tests required for the schema phase

### Shared

1. Model validation retains unknown cover codes but rejects non-finite/negative heights and numeric
   percentages outside 0–100.
2. Parent band percentages and structured layers round-trip without one being synthesized from the
   other.
3. Empty layers remain unknown rather than becoming clear.

### Android

1. Instrumented Room migration test from schema 68 to 69.
2. Assert the two parent columns, child-table columns, composite primary key, foreign key, and index.
3. Insert a legacy-style observation without new values and verify null/empty output.
4. Round-trip one observation with three ordered layers.
5. Replace that observation and verify stale child layers are removed.
6. Delete the parent and verify cascade deletion.
7. Verify the same station/timestamp/location under NWS and METAR retains separate layer sets.

### Desktop

1. JDBC upgrade test from schema 22 to 23 and a fresh-schema parity test.
2. The same null/empty, three-layer round-trip, replacement, cascade, and provenance-isolation cases
   as Android.
3. Verify `PRAGMA foreign_keys` is enabled on a newly opened application connection.
4. Verify ordinary observation reads return one parent reading, not one duplicate per layer.

Every new test class must declare exactly one duration category under the rules for its module.

## Decisions to make before implementation

1. **Recommended:** accept the dual representation—parent percentage bands plus normalized reported
   layers. The smaller alternative of only adding mid/high columns cannot preserve observed heights.
2. Confirm whether Tomorrow.io's base/top envelope should be supported by the first provider phase;
   the schema can support it without requiring it immediately.
3. Confirm whether old raw METAR rows may age out naturally or require an application-level reparse
   after migration. Recommended: let them age out and avoid migration-time data invention.
4. Confirm naming of `topMeters` versus `ceilingMeters`. Recommended: `topMeters`, because aviation
   “ceiling” normally means the lowest broken/overcast layer, while Tomorrow.io documents its field
   as the highest visible altitude. The database should not overload those meanings.

## Explicitly deferred

1. Deriving a middle/high actual percentage from cumulative METAR cover codes.
2. Choosing 2/6 km versus 3/8 km band boundaries.
3. Cloud-layer blending across stations.
4. Rendering additional actual curves, layer glyphs, or tooltips.
5. Changing the existing visible low-cloud actual curve.
6. Fetching new Tomorrow.io fields or changing provider request quotas.

## Verification commands after implementation approval

```bash
./gradlew :shared:testShortShared
./gradlew :app:testShortDebugUnitTest
./gradlew :desktop:testShortDesktop
./scripts/emulator-tests.sh -c com.weatherwidget.data.local.WeatherDatabaseMigrationTest
git diff --check
```

Schema implementation is not complete until Android and desktop migrations, entity/DAO round trips,
and an emulator migration test all pass. Runtime provider and graph verification belong to their
later approved phases.

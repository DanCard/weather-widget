# Station type: one shared enum, stored as INTEGER codes on both platforms

Approved by the user 2026-10-09 ("Update the databases to hold integer values for station type").

## Why
Station type travelled as a free string end to end: `NwsApi.StationType` existed but the
`StationTypes` helper turned it straight back into `.name`, and the string is what the DB,
`ObservationReading`, `BlendContribution` and the Blend-table formatter carried. The formatter then
re-encoded it as a *second* string (`O`/`P`/`F`). Desktop's Blend tab compared that second string
against the first (`== "OFFICIAL"`) and official stations lost their tint — invisible to the
compiler. User's decision: an enum in code **and** in the DB, stored as integer codes (not TEXT).

## Survey (2026-10-09)
Values stored before this change:

| DB | OFFICIAL | PERSONAL | RAWS | UNKNOWN |
|---|---|---|---|---|
| Desktop | 14328 | 13351 | 468 | 31 |
| Pixel 7 Pro | 24414 | 20725 | 549 | – |
| Fold 4 | 6882 | 3138 | 172 | – |

Desktop's `BLENDED` / `VIRTUAL` rows (the stored `NWS_BLEND`) are gone: desktop stopped storing the
blend and the rows were deleted by hand (`plans/261009-desktop-stops-storing-nws-blend.md`).
The column lives only in `observations` (Android Room v75, desktop SQLite v28).

## Design
`:shared` `com.weatherwidget.data.model.StationType`, replacing the nested `NwsApi.StationType`:

| Enum | dbCode | label (Blend tab) |
|---|---|---|
| UNKNOWN | 0 | `?` |
| OFFICIAL | 1 | `O` |
| PERSONAL | 2 | `P` |
| RAWS | 3 | `F` |
| BLENDED | 4 | `B` |

- Same pattern as `CloudVerticalKind`: explicit `dbCode`, never `ordinal`; `fromDbCode` falls back
  to `UNKNOWN` so a value from another build never fails a read; Room `StationTypeConverters`.
- `BLENDED` is never stored. It exists because Android's in-memory blend row is an
  `ObservationEntity` and needs a type.
- `isDiscounted` (PERSONAL, RAWS) is a property; the `StationTypes` object is deleted.
- `label` replaces `BlendTableFormatter.typeLabel`'s string `when` and the `OFFICIAL_LABEL`
  constant. `BlendTableRow` carries `stationType`; both Blend tabs and both Observations lists tint
  on `== StationType.OFFICIAL`.
- Badge text shows `stationType.name`, so nothing changes on screen.
- `ObservationTimelineNormalizer`'s tie-break sorts by `.name`, keeping its old alphabetical order.
- The station-list cache (`NwsApi.encodeStationInfo`, tab-separated text, not the DB) keeps names.

Typed end to end: `ObservationEntity`, `DesktopObservationEntity`, `ObservationReading`,
`BlendContribution`, the blend's internal metadata, `PersonalStationThinning`, both
`retagStationType` DAO methods, the mappers and both UIs.

## Migrations
Both rebuild `observations` (SQLite cannot change a column type in place), copying with the shared
`StationType.SQL_CODE_FROM_LEGACY_NAME` `CASE` expression so both platforms convert identically, and
skip any `NWS_BLEND` row.

- **Android Room v75 → v76**: `stationType INTEGER NOT NULL`; indices recreated; schema JSON 76
  exported.
- **Desktop v28 → v29** (paired with Room v76): same rebuild in `migrate()`; fresh-install DDL
  becomes `stationType INTEGER NOT NULL DEFAULT 0`; DAO writes `dbCode`, reads `fromDbCode`.

## Tests
- `StationTypeTest`: codes pinned (0–4) and unique; `fromDbCode` / `fromName` unknown → UNKNOWN;
  `isDiscounted`; the SQL `CASE` maps every legacy name.
- Android `MigrationTestHelper` 75 → 76: every legacy string, `VIRTUAL`, junk and an `NWS_BLEND`
  row come out with the expected codes / dropped; schema validates.
- Desktop migration test 28 → 29: same fixture.
- Existing tests updated from strings to the enum.
- On device: back up DBs (`scripts/backup_databases.py`), install on Pixel + Fold, check
  `PRAGMA user_version` = 76, `SELECT stationType, count(*)` shows only codes, Observations +
  Blend tabs unchanged with green official rows; desktop restarted, `user_version` = 29, same checks.

## Cost
`sqlite3` queries read `stationType = 1` instead of `'OFFICIAL'`; the enum's KDoc carries the
code table.

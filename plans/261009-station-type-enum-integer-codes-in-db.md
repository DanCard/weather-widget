# Station type: one shared enum, stored as INTEGER codes on both platforms

> **Update 2026-10-09:** desktop no longer stores `NWS_BLEND` and its stored rows were deleted
> (`plans/261009-desktop-stops-storing-nws-blend.md`). The DB column now holds only
> `UNKNOWN / OFFICIAL / PERSONAL / RAWS`, so `BLENDED` (code 4) and the `VIRTUAL` mapping below drop
> out of the DB codes; `BLENDED` survives only as the in-memory blend row's type. Revise before
> implementing. Not yet approved.

## Why
Station type travels as a free string end to end: `NwsApi.StationType` exists but `StationTypes`
turns it straight back into `.name`, and the string is what the DB, `ObservationReading`,
`BlendContribution` and the formatter carry. The formatter then re-encodes it as a *second* string
(`O`/`P`/`F`). Desktop's Blend tab compared that second string against the first (`== "OFFICIAL"`)
and official stations lost their tint — the compiler could not see it. User's decision
(2026-10-09): make it an enum in code **and** in the DB, stored as integer codes.

## Survey (2026-10-09)
Values actually stored:

| DB | OFFICIAL | PERSONAL | RAWS | BLENDED | VIRTUAL | UNKNOWN |
|---|---|---|---|---|---|---|
| Desktop | 14328 | 13351 | 468 | 398 | 82 | 31 |
| Pixel 7 Pro | 24414 | 20725 | 549 | – | – | – |
| Fold 4 | 6882 | 3138 | 172 | – | – | – |

- `BLENDED` = desktop's `NWS_BLEND` row (`NwsBlend.STATION_TYPE`).
- `VIRTUAL` = also `NWS_BLEND` (newest 2026-10-02); no current code writes it — legacy.
- `UNKNOWN` = entity default.
- Column lives only in `observations` (Android Room v75, desktop hand-written SQL v28).
- ~35 `StationType` references, ~20 raw `"OFFICIAL"/"PERSONAL"/"RAWS"/"BLENDED"` literals, two
  `UPDATE … SET stationType` statements (Android `ObservationDao`, desktop `DesktopWeatherDao`).

## Design
`:shared` `data.model.StationType` (moved out of `NwsApi`; Synoptic/METAR use it too):

| Enum | code | label (Blend tab) |
|---|---|---|
| UNKNOWN | 0 | `?` |
| OFFICIAL | 1 | `O` |
| PERSONAL | 2 | `P` |
| RAWS | 3 | `F` |
| BLENDED | 4 | `B` |

- **Explicit `code`, never `ordinal`** — reordering entries must not reinterpret rows.
- `fromCode(Int)` and `fromName(String)` both fall back to `UNKNOWN`: a value written by a
  future/past build must never crash an observation read (Room's built-in enum converter throws).
- `isDiscounted` becomes a property (PERSONAL, RAWS); `StationTypes` object deleted.
- `VIRTUAL` gets no entry: the migration maps it to `BLENDED` (same station, `NWS_BLEND`).
  Observations keep 10 days anyway.
- Display text for the Observations badge ("OFFICIAL (Web)") comes from `enum.name`, unchanged
  on screen.

Typed end to end: `ObservationEntity`, `DesktopObservationEntity`, `ObservationReading`,
`BlendContribution`, `BlendTableRow.stationType` (replaces the `type: String` cell and the
uncommitted `OFFICIAL_LABEL`), `NwsStationInfo.type`, mappers, both UIs.

## Migrations
- **Android Room v75 → v76**: rebuild `observations` with `stationType INTEGER NOT NULL DEFAULT 0`,
  copying with `CASE stationType WHEN 'OFFICIAL' THEN 1 WHEN 'PERSONAL' THEN 2 WHEN 'RAWS' THEN 3
  WHEN 'BLENDED' THEN 4 WHEN 'VIRTUAL' THEN 4 ELSE 0 END`; indices recreated; `@TypeConverter`
  `StationType ↔ Int` via the shared `code`/`fromCode`. Schema JSON exported.
- **Desktop v28 → v29**: same rebuild + `CASE` in `migrate()`; DAO reads `fromCode(rs.getInt)`,
  writes `.code`; `CREATE TABLE` for fresh installs updated.
- One shared `StationType.SQL_CASE_FROM_NAME` string so both migrations map identically.

## Tests
- `StationTypeTest`: codes are unique and pinned (1/2/3/4 — a test, so a reorder fails loudly);
  `fromCode`/`fromName` unknown → UNKNOWN; `isDiscounted`.
- Android `MigrationTestHelper` 75→76: rows of every stored string incl. `VIRTUAL` and garbage
  come out with the expected codes; row count preserved.
- Desktop migration test 28→29: same fixture.
- Existing blend / observations / Blend-table tests updated to the enum; Blend tab tints
  official on both platforms (formatter test asserts `stationType`, label derived).
- On device: back up DBs first (`scripts/backup_databases.py`), install on Pixel + Fold, check
  `PRAGMA user_version`, `SELECT stationType, count(*)` shows only 1–3, Observations + Blend tabs
  render unchanged with green official rows; desktop restarted, same checks, BLENDED row still
  excluded from the list.

## Cost noted
`sqlite3` queries now read `stationType = 1`; `StationType` KDoc carries the code table.

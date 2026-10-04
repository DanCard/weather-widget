# Do not store same-day high/low after their cutoff (hindcast gate)

Follow-up to [261004-forecast-overlay-frozen-at-extreme-time.md](261004-forecast-overlay-frozen-at-extreme-time.md)
and [261004-do-not-store-hindcast-forecasts.md](261004-do-not-store-hindcast-forecasts.md)
(earlier brainstorm, superseded by the fixed cutoffs below).

## Problem

APIs keep writing same-day high/low **after those values are no longer predictions**. The database
must not contain junk: forecasts that are actually hindcasts. Display is out of scope — if the DB
only holds real forecasts, the UI fixes itself.

## Rule (user, 2026-10-04)

> Ignore low temp forecasts on same day after 6 am. Ignore same day high temp forecast changes
> after 4 pm.

Local timezone, **per targetDate** (the forecast day, not the fetch day):

| Field | Accept writes while | After cutoff |
|---|---|---|
| `lowTemp` (same day) | `now < 06:00` on that date | **Ignore** — do not insert/update `lowTemp` |
| `highTemp` (same day) | `now < 16:00` on that date | **Ignore** — do not change `highTemp` |
| Both, `targetDate` in the **future** (tomorrow+) | always | never gated |
| `targetDate` in the **past** | already dropped (`date < today`) | unchanged |

Per-field, not per-row: after 6 am a batch may still update `highTemp`; after 4 pm it may still
update other columns (condition, precip, …) while high/low stay frozen. If both fields would be
ignored and nothing else changes, skip the row entirely (log `SNAPSHOT_SKIP_HINDCAST`).

"Ignore high temp forecast **changes**" = freeze the already-stored high. A first-ever write after
4 pm has no prior value — still do **not** write `highTemp` (it is a hindcast). Same for a first
low after 6 am. Do not let `newDataIsStrictlyBetter` (null→value) resurrect a frozen field.

## Design

### 1. Pure gate in `:shared`

```kotlin
object SameDayExtremeCutoff {
    /** Local wall-clock cutoffs for same-day forecast writes. */
    val LOW_CUTOFF: LocalTime = LocalTime.of(6, 0)
    val HIGH_CUTOFF: LocalTime = LocalTime.of(16, 0)

    /**
     * Filter same-day high/low for storage. [nowMs] is the fetch/write instant.
     * Future [targetDate] rows pass through unchanged.
     */
    fun filter(
        targetDate: LocalDate,
        highTemp: Float?,
        lowTemp: Float?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<Float?, Float?>
}
```

- `targetDate` after today's local date → return input unchanged.
- `targetDate` before today → unchanged (caller already drops these).
- Same day: `lowTemp` → null if `now >= 06:00`; `highTemp` → null if `now >= 16:00`.

### 2. Android — `ForecastSnapshotStore.saveForecastSnapshot`

Apply `filter` in the existing `forecastsToSave` mapping (next to `ForecastTempRounding.forStorage`).
When a field is frozen, keep the **existing stored value** for that field instead of the new one
(compare against `latestByDate` as today's `fieldsMatch` / upgrade logic already does). Log:

```
SNAPSHOT_SKIP_HINDCAST date=… source=… froze=low|high|both prior_high=… prior_low=…
```

Ensure the post-cutoff path cannot write a high/low via `newDataIsStrictlyBetter`.

### 3. Desktop — `DesktopWeatherRepository.persistForecastResult` → `upsertForecasts`

Same filter before `upsertForecasts` (keep the existing `date >= today` filter). When freezing,
merge with the row already in `forecasts` for that (targetDate, source, site) so the frozen field
keeps the pre-cutoff value. Log `FORECAST_SKIP_HINDCAST` at INFO (one line per day/source/batch).

### 4. History / settle / accuracy

- `ForecastOverlaySettle` unchanged — post-cutoff batches stop existing for new data; historical
  rows and its own tests (which write via DAO) stay green.
- `ForecastSnapshot` accuracy path (8 pm snapshot) unchanged.
- Hourly elapsed handling (`ElapsedForecastBackfill`) unchanged.

### 5. Existing junk (optional follow-up)

Leave historical hindcast rows; scrub only if accuracy still looks dirty after the writer fix.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `filter`: same-day low at 05:59 kept, at 06:00 nulled | unit |
| 2 | `filter`: same-day high at 15:59 kept, at 16:00 nulled | unit |
| 3 | `filter`: after 06:00 high still kept; after 16:00 low stays nulled | unit |
| 4 | `filter`: tomorrow's row untouched at any hour | unit |
| 5 | `filter`: timezone uses local zone (cutoff is local wall clock) | unit |
| 6 | Android save 05:30 → low+high stored; 07:00 → low frozen to prior, high updates | Robolectric |
| 7 | Android save 15:00 → high updates; 16:30 → high frozen to prior | Robolectric |
| 8 | First write at 17:00 → neither high nor low stored (`newDataIsStrictlyBetter` cannot resurrect) | Robolectric |
| 9 | Desktop save same cases (6 am / 4 pm) | integration |
| 10 | Tomorrow's forecast at 17:00 still stores high+low | unit + integration |

Mutation check each.

## Implementation notes

- Dual-platform parity: gate in `:shared`, wired in Android + desktop writers.
- Cutoffs are **local** `06:00` / `16:00` on the forecast's date — no DST special case beyond
  `ZonedDateTime`; document if a spring-forward 06:00 is skipped (then use the first instant ≥ cutoff).
- Do not change display, `daily_history` freeze windows, or settle.
- Commit message should reference this plan path.

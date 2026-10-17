# Daily view: tap zones fewer than drawn slots — Today taps open yesterday

## User Prompt

> I made a change last night to increase density, so get more columns on front cover of Fold 4.
> 6 columns instead of 5. Now I notice that when I click on the today column, 2/3rds of the time it
> goes to previous day.

## Evidence (Fold 4, `RFCT70G352Y`, backup `20261017_064153`)

- Every recent `CLICK_DAILY` is from **widget 345 — the inner (unfolded) screen**, not the cover
  widget 383: the logged dates (`col=2:date=2026-10-16`, `col=3:date=2026-10-17`) match 345's
  window `2026-10-14..10-25`; 383's window starts at `2026-10-16` as col 0.
- 06:29–06:39: four taps, three resolved to `col=2` (yesterday), one to `col=3` (today).
- Widget 345: `appWidgetMinWidth=662`, rows 5. `DAILY_RENDER widget=345 cols=12` (was `cols=11`
  before 7aea9eae — `(662+30)/60 = 11.5 → 12` slots, now `(662+30)/55 = 12.6 → 13` slots).
- The cover widget 383 renders `cols=6` → 7 slots, within the zone count; it is not affected.

## Root cause

`widget_weather.xml` has only **10** tap zones per row (`graph_dayN_top_zone`, `graph_dayN_zone`,
`graph_bottom_dayN_zone`, N = 1..10), equal-weight in a `LinearLayout`.
`DailyClickHandlerFactory.setupGraphZoneClickHandlers` makes `visibleSlotCount` zones visible, but
`zoneIds.getOrNull(...)` silently drops slots ≥ 10. With 13 drawn slots the 10 zones stretch over
the full width, so each zone is 1.3 slots wide and drifts left of the column it is wired to:

| Today = slots 3–4 (x ∈ [3, 5) slot units) | zone 2 = [2.6, 3.9) → **yesterday** | zone 3 = [3.9, 5.2) → today |

≈ 45 % of Today's width (its left half, where the big number sits) opens yesterday. Columns right of
Today drift further, and slots 10–12 have no zone at all. The pre-existing 12-slot layout was already
misaligned (1.2 slots per zone, ~30 % of Today), so 7aea9eae made a latent bug visible rather than
introducing it.

## Fix

1. **Layout**: add zones 11–16 to all three rows in `widget_weather.xml` (gone by default).
   16 covers the Fold inner screen (13 slots) with headroom; `(16 slots) ≈ 850 dp`.
2. **`DailyClickHandlerFactory`**: extend the three `zoneIds` lists to 16; expose the count as one
   constant (`DAILY_TAP_ZONE_COUNT`) shared by the three rows.
3. **Guard**: if `visibleSlotCount > zoneIds.size`, log `DAILY_TAP_ZONES_SHORT slots=… zones=…`
   (WARN, persisted) so a wider device shows up in `app_logs` instead of misrouting silently.
4. Night-rain grid (20 cells, bitmap-bound) is independent — no change.

No cap on the column count (user, 2026-10-17): a widget wider than 850 dp keeps all its days and
logs `DAILY_TAP_ZONES_SHORT` instead.

## Tests

- **Capacity** (`DailyTapZoneCoverageTest`): for every width 1..`MAX_SUPPORTED_WIDTH_DP` (850),
  overlay on and off, the slots the real policy draws ≤ `DAILY_TAP_ZONE_COUNT`. Catches a density
  change like 7aea9eae (at 60 dp with 10 zones it already failed at 662 dp).
- **Structural** (`DailyTapZoneCoverageTest`): inflate `widget_weather.xml`; each band's children
  are exactly the factory's zone list, in order, weight 1. Catches the constant / lists / XML
  drifting apart.
- **Behavioral** (`DailyViewGraphClickAlignmentTest`, 662 × 465 dp, the Fold inner widget): in all
  three bands, 13 visible zones of equal width; Today owns two adjacent zones, every other zone is
  its own consecutive day, the zone left of Today is yesterday.
- Mutation check: with the lists and constant cut back to 10, 4 of the 9 tests in the two classes fail.
- On device: install on `RFCT70G352Y`, tap Today on widget 345 (left and right halves) via
  `input tap`, confirm `CLICK_DAILY … col=3:date=<today>` both times.

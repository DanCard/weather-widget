# Today's fog faded once the foggy hours passed: use the provider's whole-morning answer at any time

User (2026-11-11): "fold-4: Meteo earlier today had fog, but it disappeared. I'm sad." Approved fix
(user: "store the earlier data and use it, regardless of time of day" → "proceed with combine").

## Evidence (Fold 4, RFCT70G352Y, backup 20261111_110254)

1. Screenshot 11:03: Thu shows a bright dense forecast ellipse; Today looks empty. A brightened crop
   shows a faint haze on Today: drawn, but lost over the grey Today panel.
2. logcat 11:01: `DAILY_FOG_DRAWN date=2026-11-11 level=2 top=56.0 vis=3.0` (Meteo widget). `vis=3.0`
   is the LIGHT stand-in → alpha ≈ 0.28 (dense = 0.90).
3. Open-Meteo's observation rows 04:00–07:30 say `Light Fog` (WMO 45) with no visibility → LIGHT.
4. Open-Meteo's newest hourly fetch (11:01) for those hours: visibility 0.00–0.12 mi. Its forecast row
   for today already holds `fogLevel=4 top=55.3 vis=0.0` (computed at persist over 00–12).

## Root cause

`DailyMorningFog.forColumn` for today used `ahead ?: row.takeIf { windowOpen }`. `ahead` covers only the
hours from now to noon. At 11:00 that is one clear hour → `NONE`, a real answer, so the row's dense
morning was never read. After noon the row was dropped, and past days read observed fog only. Each
foggy hour that elapsed therefore left the ellipse to the word-only observation.

## Fix (`:shared`, both platforms)

1. One rule for every column: `combine(observed, row ?: fromHourly())`.
   - The row is the newest fetch's whole 00–12 window (newest fetch wins), so a fetch after the morning
     is the provider's hindsight.
   - Future columns have no observations, so this reduces to today's future behavior.
   - Past columns gain the row (or the stored hours) next to observed fog.
2. Drop `ahead`, `windowOpen` and `isPast`/`isToday` from `forColumn`; delete the now-unused
   `ForecastMorningFog.aheadOf`.
3. `combine` keeps the stronger side, so observations cannot cancel forecast fog that never formed
   (accepted trade-off).

## Tests

- `FogPatchTest`: the Open-Meteo 2026-11-11 case: observed LIGHT 3 mi + row DENSE 0.0 mi at 11:00 →
  DENSE (fails on the old code, which returned the observation). Also past observed + row → combined,
  row null → hours, nothing → null.
- `./scripts/unit-tests.sh`.
- Fold 4: install; `DAILY_FOG ... date=2026-11-11 src=OPEN_METEO level=4` and a screenshot of Today.

## Docs

`arch/ARCHITECTURE.md` and `arch/daily-history-extremes.md`: the column fog rule.

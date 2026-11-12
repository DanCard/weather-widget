# NWS drew dense fog on a clear morning: observed "no fog" must cancel forecast fog for elapsed hours

User (2026-11-12): "Disappointed for NWS, has fog indicator this morning, but there is no fog."

## Evidence (Pixel 7 Pro 2A191FDH300PPW, backup 20261112_072826)

1. NWS `forecasts` row for 2026-11-12 (newest fetch 00:14): `fogLevel=4 top=52.0 vis=0.24` — DENSE.
2. NWS hourly rows that produced it: conditions say only "Mostly Cloudy", but the gridpoint visibility
   is 0.48 / 0.24 / 0.48 mi at 03:00 / 04:00 / 05:00. The `visibilityMi <= 0.62` rule made those hours
   foggy, and 0.24 mi → `FogLevel.fromVisibility` → DENSE (0.90 alpha).
3. What happened: KNUQ (3.8 km) every 20 min, 00:10–07:15, `00000KT 10SM CLR 10/09` — no FG, no BR,
   10 SM. KPAO 06:55 `10SM SKC`. KSJC 10SM CLR all night. (Also confirmed on aviationweather.gov.)
4. `daily_history` NWS 2026-11-12: `observedFogLevel=0` (NONE, a real answer), recomputed 07:09.

## Root cause

`DailyMorningFog.forColumn` returns `MorningFog.combine(observed, forecast)`, and `combine` returns the
other side when one is NONE. So observed NONE never cancels forecast fog. Yesterday's plan
(plans/261111-today-fog-whole-morning.md, "Fix" step 3) accepted that trade-off on purpose. This morning
is the case it accepted: NWS forecast fog that never formed stays on Today all day. From tomorrow it also
stays on the past column, because past columns now read the row too.

## Proposed fix (`:shared`, both platforms)

Treat observed NONE and observed fog differently:

1. **Observed foggy** → unchanged: `combine(observed, forecast)`. This keeps the Open-Meteo 2026-11-11
   case (observed LIGHT and forecast DENSE → DENSE).
2. **Observed NONE** → for the hours that have already passed, the stations win. Forecast fog counts only
   for the window hours still ahead of `now`:
   `ForecastMorningFog.forWindow(hours, max(now, windowStart), windowEnd)`. That gives NONE after noon,
   and NONE on past days.
3. **Observed null** (no station could speak to fog: future days, or no observations) → forecast alone,
   unchanged.

`forColumn` gets `nowMs` and the column's window. The stored row is a 00–12 summary and can't be split,
so for observed NONE we read the stored hours.

Trade-off: the cutoff is `now`, not "latest observation". If observations stall (phone asleep) and real
fog was forecast for an hour no station has reported yet, that hour is still vetoed. Storing an
observed-through timestamp would avoid this, but it needs a Room migration plus the matching desktop
schema change. I don't recommend it for now: the blend recompute runs on every observation fetch.

## Tests

- `FogPatchTest` (shared): the NWS 2026-11-12 case. Observed NONE and hourly 03–05 at 0.24–0.48 mi,
  at 07:30 → NONE. This fails on the current code, which returns DENSE. Also: observed NONE at 02:00
  → forecast DENSE (fog still ahead); observed LIGHT and row DENSE → DENSE (regression guard);
  observed null → forecast.
- `./scripts/unit-tests.sh`.
- Pixel: install. Check `DAILY_FOG ... date=2026-11-12 src=NWS` for level 0 and take a screenshot
  of Today. Restart the desktop app and check its NWS Today column.

## Docs

`arch/ARCHITECTURE.md` and `arch/daily-history-extremes.md`: update the column fog rule.

## Addendum: pre-dawn fog draws petite

User (2026-11-12): "predawn fog [should be] petite. Not very tall. Maybe a third height? Not very
wide either." This morning's NWS fog was 03:00–05:00; sunrise at KNUQ was about 06:45.

### Rule

- A morning's fog is **pre-dawn** when every foggy hour (forecast) or report (observed) is before that
  day's sunrise at the site (`SunPositionUtils.sunriseInstant`; forecast rows and observations both
  carry coordinates). Fog at or after sunrise draws exactly as it does now.
- `MorningFog` gets a fourth field, `predawn: Boolean`. `combine` keeps it only when **both** sides are
  pre-dawn, so any daylight fog makes the ellipse full size.
- `FogPatch.plan` for pre-dawn fog: **height ⅓** and **width ½** of what it would otherwise draw,
  minimum circle included. With the 1.3 column width, a short patch becomes about 0.8 column wide
  instead of 1.6. It still sits on the low. Opacity is unchanged; it still comes from visibility.
- The ½ width is my pick for "not very wide either". Easy to tune after a screenshot.

### Persistence (both platforms)

- New nullable column `fogPredawn INTEGER` on `forecasts` and `observedFogPredawn INTEGER` on
  `daily_history`. This needs a Room migration with exported schema, a matching
  `DesktopWeatherDatabase` migration, and migration tests on real SQLite. Null on old rows means
  "not pre-dawn", so stored fog keeps its current size until the next fetch or recompute rewrites it.
- `ForecastMorningFog` / `ObservedMorningFog` compute the flag, and `DailyHourlySummaries.carryForward`
  carries it with the other fog fields.

### Tests

- `ForecastMorningFogTest`: NWS 2026-11-12 hours (fog only at 03–05) → `predawn = true`.
  Fog at 05–09 → false.
- `ObservedMorningFogTest`: KPAO 2026-11-11 (fog past sunrise) → false.
  A 04:00-only FG report → true.
- `FogPatchTest`: the pre-dawn plan is ⅓ the height and ½ the width of the same fog when it is not
  pre-dawn. `combine` with one pre-dawn side and one daylight side gives full size.
- Migration tests, Android (emulator) and desktop.
- Screenshot on device: force pre-dawn fog through a debug-seeded row on the emulator, since today's
  fog is now vetoed by the stations. Desktop: same.

## Addendum: a saturation hint counts as no fog for past hours

Side-agent review: `forColumn` sent a HINT (saturated, calm air, no FG/BR) down the combine path, so a
hint-only morning would still draw NWS's 0.24 mi forecast at ~77 %. User (2026-11-12): "Should a hint of
fog only draw a hint of fog? In other words low opacity, very transparent?" → yes, approved.

- `forColumn`: observed level ≤ HINT takes the stations-win path. Forecast fog only from the current
  hour, combined with the hint. Past the forecast's foggy hours the column draws just the hint (5–15 %).
- Tests: `FogPatchTest` (hint + passed NWS row → the hint; hint + fog still ahead → DENSE),
  `DailyColumnFogTest` (hint at 07:30 → the hint).

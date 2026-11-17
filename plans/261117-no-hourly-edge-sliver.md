# False "No hourly forecast" when the view's edge clips one hour of the next day

## Evidence

- Desktop popup 2026-11-17 15:00:01:
  `HOURLY_PAN_CHECK action=NoDataMessage(date=2026-11-18) displaySource=GOOGLE_WEATHER
  days=2026-11-17..2026-11-18 rowsTotal=910 rowsBySource={GOOGLE_WEATHER=910}`.
- The desktop DB held all 24 distinct hours of 2026-11-18 for GOOGLE_WEATHER (fetched 13:24).
- Same false notice 2026-11-16 15:00:01 and 15:10:13 (NWS, for 11-17). Desktop zoom was WIDE
  (`zoomFactor` 0.2555 → 9 h back / 9 h forward).

## Root cause

At 15:00 the WIDE window becomes 06:00 → 00:00 next day. The pan check re-runs because the day set
gains tomorrow. `NoHourlyChecker.hasHourlyForDay` clips tomorrow to the view (one hour: 00:00) and
requires `MIN_DRAWABLE_HOURS` (2) distinct hours there. One hour can never pass, so the check
reports "no data" though the day is fully covered. The hour-in-view clip was added for the Fold 4
case in `4c9b6c944`.

## Fix (`:shared`, both platforms)

- `hasHourlyForDay`: require `min(MIN_DRAWABLE_HOURS, whole hours of the day inside the view)`.
  A one-hour sliver passes when that hour has a row (it continues the previous day's line).
  The Fold 4 case (18 view hours, one data hour) still fails.
- `emptyViewDay` is unchanged: it judges the whole view, never a sliver.

## Tests

- `NoHourlyCheckerTest`: a sliver with its hour stored is covered; a sliver without it is not;
  the Fold 4 case still fails.
- Integration (`HourlyOnDemand.panAction` + real `NoHourlyChecker.hasHourlyForDay`): the 15:00 WIDE
  window over fully stored data returns `Nothing`.

## Docs

- `arch/forecast-and-observation-cadence.md` coverage sentence: note the sliver rule.

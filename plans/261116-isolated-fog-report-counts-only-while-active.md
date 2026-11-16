# An isolated fog report counts only while it is active

## Prompt

> desktop: I don't like the heavy fog indicator based on a single fog incident. Brainstorm on ideas for
> reducing heavy fog indicator based on single station single temperal report.
>
> Can we make this kind of fog indicator temporal? Only while the station report is active?
>
> Make expiry 45 minutes instead of 75. proceed

## Evidence

Desktop DB, 2026-11-16: every source's `daily_history.observedFogLevel = 4` (DENSE), ⅛ SM, top 58.1 °F.
One KNUQ report drove it:

```
10:35  KNUQ AUTO 15006KT 8SM FEW044          14/12
10:55  KNUQ AUTO 20003KT M1/4SM HZ VV000     15/12   ← DENSE by the visibility-alone rule
11:15  KNUQ AUTO 14004KT 10SM CLR            14/11
```

KPAO (10:47) and KSJC (10:53) read 10 SM. Every one of the 34 sub-1 km reports stored for KNUQ/KPAO/KSJC
on 11-09 and 11-11 has a foggy report from the same station just before or just after it; this one has
none. Because it was the morning's last fog report it also set the ellipse's top (midway 59.0/57.2 °F).

## Root cause

`ObservedMorningFog.forDate` judges each report on its own. A single report's level sets the morning's
level (max), its visibility sets the opacity (min), and when it is the last foggy report it sets the
burn-off top.

## Fix (`:shared`, both platforms)

A foggy report (level above `FogLevel.HINT`) counts only when it is

1. **confirmed**: the same station's previous or next report (by distinct timestamp, among reports
   that can speak to fog) is also foggy above HINT; or
2. **active**: it is that station's latest report and `nowMs - timestamp < 45 min` (user: "Make expiry
   45 minutes instead of 75").

Otherwise it is a spike and is treated as no fog: it sets neither level, visibility nor top, and it can
serve as the first clear report after real fog. Hints are unchanged.

- `forDate(readings, date, zone, nowMs)`: `nowMs` is required, so every caller states its clock.
  Callers: `DailyActualsAssembler` (live today) and `DailyBlendRecomputeRun` (stored rows) both have
  `nowMs`. Neighbors are looked up across all the readings passed in, not only the morning window, so a
  clear report after noon still retracts an 11:55 spike.
- No storage change. Today's fog is recomputed live from observations, and `DailyBlendWrite` overwrites
  the stored fog, so when the next report arrives the next recompute drops the spike. 11-16 shows dense
  fog only from 10:55 to 11:15.
- The retracted day reads as observed NONE (or LIGHT from today's other reports), so the existing
  "observed no fog vetoes past forecast hours" rule applies unchanged.
- Log `FOG_SPIKE_DROPPED` (VERBOSE, from the recompute) with station, time and level, so a retraction
  can be seen in logcat and desktop logs.

Expected for 11-16: the 10:55 spike and the 09:35 `6SM BR` report (no foggy report either side) drop;
the 00:15–01:55 `-RA BR` run confirms itself and stays LIGHT. Mist during rain is a separate issue, not
changed here.

## Tests (`ObservedMorningFogTest`, pure JVM)

- 11-16 real KNUQ/KPAO/KSJC reports: DENSE while 10:55 is the latest report and < 45 min old; not foggy
  once 11:15 `10SM CLR` arrives; not foggy when 10:55 is the latest but 45+ min old.
- A spike that is the morning's last fog report does not set the top.
- Fog onset: a lone first FG report is active, then confirmed by the next FG report and kept after
  expiry.
- Existing 11-09/11-11 mornings keep their level, visibility and top.

## Docs

`arch/ARCHITECTURE.md` daily fog section: add the active/confirmed rule.

## Verification

`./scripts/unit-tests.sh`; restart the desktop app and confirm 11-16's dense ellipse is gone after the
recompute; `installDebug` on connected devices and check the daily view.

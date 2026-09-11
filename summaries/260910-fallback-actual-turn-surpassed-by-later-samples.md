# Fallback actual-turn labels no longer mark a dip the line has since fallen through

**Date:** 2026-09-10
**Plan:** `plans/260910-fallback-actual-turn-surpassed-by-later-samples.md`

## What happened

Emulator hourly view drew a pink `81.8°` at 16:15 on a steady afternoon descent (88.1° → 78.2° at
Now). Logcat showed it was `role=ACTUAL_LOW reason=PROMINENT_ACTUAL_TURN`: the day had no daily
actual low (coldest samples at the window edge and at Now, both edge-gated), so the fallback
labelled the coldest 0.75°F-hysteresis turn — a 0.8° wiggle the line then fell 3.6° through.

## What changed

- `LabelCandidateCollector.dropTurnsSurpassedLater` (new): before `mostExtremeTurn`, a fallback
  low warmer than any later observed sample (or high colder than one) up to `actualEndIndex` is
  dropped, with an `ActualTurnSurpassed` `Log.v` breadcrumb. Gated to observed lines that
  terminate on screen (`actualEndIndex < hours.lastIndex`); historical slices whose line runs off
  the window edge are unchanged.
- `TemperatureActualTurningPointLabelTest`: +3 cases (emulator descent → no low; same series with
  a rebound and a warmer Now → dip still labelled; fixture guard).

## Verification

`:shared:test` 1562/0. On the emulator all three prominent lows were dropped (85.6, 85.7, 81.8),
`88.1°` and the Now dot's `78.2°` still drawn, no `81.8°`.

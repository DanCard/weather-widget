# Session log — Today-column actual cutoffs + daily icon/label anchor

**Date:** 2026-06-16
**Branch:** main
**Theme:** A daily-view feature request (today column tracks actuals after time cutoffs) plus two
follow-up issues it surfaced: desktop icon/label ordering parity, and an Android icon-placement
regression the feature introduced.

---

## Overview

One feature and two bug fixes, all in the **daily forecast view**. The feature is implemented and
unit-tested; the two follow-ups were diagnosed from code + a live Samsung/desktop look and are
implemented + tested + (desktop) visually verified. Everything is **uncommitted**.

Plan: `plans/feature-request-for-daily-flickering-spindle.md`.
Core shared seam for all three: `shared/.../util/DailyDayValueResolver.kt`.

---

## Feature — Today column tracks actuals after time cutoffs

**Request:** in the daily view's *today* column, after **9am** show the **actual** observed low
instead of the forecast low, and after **5pm** show the **actual** observed high instead of the
forecast high (the overnight low / daytime high are effectively settled by then).

**Key realization:** the today column already shows three bars (yellow 24h-snapshot, red
observed/mercury + ghost, blue dashed forecast), but the two prominent **numbers** are forecast/actual
*blends*:
- High = `effectiveHighForLabel()` → `max(observed, forecastHigh, ghost)`
- Low = `min(observed low, forecastLow [, snapshotLow on desktop])`

So the feature is a **time-gated exclusion of the forecast term**, not a new computation.

**Decisions (confirmed with user):**
- Keep the blue dashed forecast comparison bar; only the **number** retargets to actual.
- Fall back to forecast when no actual exists yet (never blank).
- Cutoffs are device-local clock time; today column only.

**Implementation:**
- `DailyDayValueResolver.kt`: added `ACTUAL_LOW_CUTOFF_HOUR = 9`, `ACTUAL_HIGH_CUTOFF_HOUR = 17`;
  added `nowHour: Int? = null` to `effectiveHighForLabel` (past 17 → `max(observed, ghost)`, drop
  forecast unless no actual); added mirror `effectiveLowForLabel` (past 9 → observed low, drop
  forecast unless none). `nowHour == null` preserves all legacy callers/tests.
- Android: `DailyViewLogic.kt` gates `bottomStackLow` via `effectiveLowForLabel(now.hour)` and passes
  `nowHour` into the today `DayData`; `DailyForecastGraphRenderer.kt` adds `DayData.nowHour` threaded
  into `effectiveHigh()`.
- Desktop parity: `DesktopDailyForecastModel.kt` adds `DesktopDailyDay.nowHour`;
  `DailyForecastGraph.kt` passes `nowHour` to both the high (`effectiveHighForLabel`) and the new low
  (`effectiveLowForLabel`, folding `snapshotLow` into the forecast candidate so pre-cutoff value is
  unchanged).

---

## Issue 1 — Desktop icon/label ordering didn't match Android

**Symptom:** desktop daily column drew **bar → low label → icon**; Android draws
**bar → icon → label**. User prefers Android, with max code share.

**Fix** (`desktop/.../DailyForecastGraph.kt`): reordered the per-day draw so the icon sits directly
under the bar and the low number under the icon. Bottom reserve is a sum so vertical budget is
unchanged; re-derived the icon clamp (`iconTopMax`) since the label is now the bottom-most element.

---

## Issue 2 — Android weather icon placement regression (today + history)

**Symptom (user, Samsung):** today and history weather icons mis-placed; future days fine. Icon should
sit under the **lowest part of the bar**.

**Root cause:** a *conflation of two responsibilities in one value*. `bottomStackLow` originally =
`min(observed, forecast)` and served BOTH (a) the icon/label **position anchor** and (b) the printed
low **number**. The feature above retargeted it to the gated actual low for (b), silently breaking
(a) — the icon floated up to the actual low instead of staying under the lowest bar. History had a
**pre-existing** variant: its forecast-overlay bar could dip below the icon (anchor was observed-only).

**Fix — decouple geometry from value (shared):**
- `DailyDayValueResolver.iconAnchorLow(solidLow, forecastLow, snapshotLow)` = bottom of the lowest
  drawn bar. One pure `:shared` function, called by **both** platforms for positioning; each keeps
  its own platform draw calls and its own printed-value logic. This single-sources the placement rule
  so it can't drift again (this is exactly where the two platforms had diverged).
- Android `DailyForecastGraphRenderer.kt`: `resolveIconAnchorLow(day)` drives icon + low-label Y and
  `resolveLowLabelBaseline()` (rain-label collision); `resolveBottomStackLow()` retained for the
  printed value only.
- Desktop `DailyForecastGraph.kt`: icon/label anchored to `iconAnchorLow(...)`; text still uses the
  gated `lowForLabel`.

---

## Files touched

- `shared/.../util/DailyDayValueResolver.kt` — cutoffs, `effectiveLowForLabel`, `iconAnchorLow`
- `shared/.../util/DailyDayValueResolverTest.kt` — cutoff/fallback/legacy + anchor cases
- `app/.../widget/handlers/DailyViewLogic.kt` — gated `bottomStackLow`, pass `nowHour`
- `app/.../widget/DailyForecastGraphRenderer.kt` — `DayData.nowHour`, gated `effectiveHigh()`,
  `resolveIconAnchorLow()`, position/value split
- `desktop/.../DesktopDailyForecastModel.kt` — `DesktopDailyDay.nowHour`
- `desktop/.../DailyForecastGraph.kt` — gated high+low numbers, reorder, anchor

## Tests / verification

- `:shared:test` (new cutoff, fallback, legacy-null, and `iconAnchorLow` cases) — green.
- `:app` `DailyForecastGraphRenderer*` + `DailyActualsEstimator*` Robolectric suites — green
  (incl. `resolveLowLabelBaseline`-dependent rain-label tests).
- `:desktop:test` — green. Both modules compile.
- Installed fixed APK to Samsung + Pixel + emulator (non-destructive). Rebuilt + restarted desktop;
  captured the daily popup (history Fri/Sat): confirmed **bar → icon → low label** with the icon under
  the lower (pink/actual) bar. Same `drawDayColumn`/draw-loop serves today/history/future, so today's
  triple-bar gets the same anchoring.
- Not done: direct Android *daily-view* screenshot — Samsung widget was in hourly mode and the
  emulator has no weather widget placed; couldn't toggle a physical widget remotely. Logic is shared
  with the verified desktop path + covered by the Robolectric suite.

## Follow-ups

- Optional: place/configure a daily-mode widget on the emulator to screenshot the Android daily view
  directly (today triple-bar) for a belt-and-suspenders check.
- Commit pending (feature + both fixes uncommitted).
</content>

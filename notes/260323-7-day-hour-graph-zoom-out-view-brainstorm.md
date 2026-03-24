# Zoom / Panorama View Brainstorm
_2026-03-23_

## Current Zoom Architecture

`ZoomLevel` enum in `WidgetStateManager.kt` cycles NARROW ↔ WIDE:
- `NARROW(backHours=2, forwardHours=2, navJump=2, labelInterval=1)`
- `WIDE(backHours=12, forwardHours=12, navJump=6, labelInterval=4)`

All view handlers (`TemperatureViewHandler`, `PrecipViewHandler`, `CloudCoverViewHandler`) already use `zoom.backHours`/`zoom.forwardHours` for query windows, so any new zoom level is largely free at the infrastructure layer.

---

## Options

### Option A — 3rd `ZoomLevel` enum: `PANORAMA`
Add `PANORAMA(backHours=48, forwardHours=120)` (3-day) or `(backHours=0, forwardHours=168)` (7-day forward).
Cycle becomes NARROW → WIDE → PANORAMA → NARROW.

**Pros:** Low code change, reuses existing machinery.
**Cons:**
- 168 hourly points on a small widget is noisy — needs label thinning to every 12h or 24h.
- Hourly bars blur into noise; would likely need a different rendering style (per-day summary marks, not per-hour).
- Nav arrows become confusing with a 24h `navJump`.

---

### Option B — Daily view IS the 7-day view (status quo)
The existing daily bar view already covers ±5 days, and tapping a bar drills into that day's hourly graph. This is a clean two-level mental model that's already implemented.

**Gap:** WIDE at ±12h doesn't prominently show "tomorrow."

---

### Option C — Stretch WIDE to 48h (lowest friction)
Change `WIDE(backHours=12, forwardHours=36)` — shows today + tomorrow in one view. Labels at 6h intervals. No new gesture state, no new rendering complexity.

**Pros:** Meaningful "what's coming" story with minimal risk.
**Cons:** Slightly more crowded than the current WIDE. Navigation arrows would jump 6h (same as now), which still makes sense.

---

### Option D — `WEEKLY_SUMMARY` view mode (separate from zoom)
Add a new entry to the view cycle (TEMPERATURE → PRECIPITATION → CLOUD_COVER → **WEEKLY_SUMMARY**). Renders a 7-day sparkline of daily highs/lows — not hourly, just daily aggregates. Completely separate renderer, no impact on zoom logic.

**Pros:** Clean separation of concerns; can be designed specifically for the 7-day use case.
**Cons:** More code; adds a step to the view cycle.

---

## Recommendation

**Option C** is the lowest-friction improvement — stretching WIDE's forward window from 12h to 36h gives "today + tomorrow" context without new gestures, new enum values, or new renderers. If a full 7-day overview is eventually wanted, **Option D** (a dedicated weekly mode) is the right long-term approach rather than shoehorning it into the zoom system.

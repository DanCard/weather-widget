# Session log: Samsung hourly graph label oscillation — forecast query window starved the blend

**Date:** 2026-07-15 (03:20 → 04:35, overnight)
**Scope:** started as a feature request for a high-temp label on the NARROW hourly graph; became a
hunt for why the graph alternated between two curves ~1 min apart with no new data. Root cause:
`GraphDataLoader.buildGraphQueryWindow` sized its forecast query to the **visible** zoom span while
`ActualTemperatureSeriesBuilder` blends across a **72h/60h context** and extrapolates stations through
the forecast delta — so the interaction path handed the blend 7 forecasts, every extrapolating station
resolved to null and dropped out, and the observed curve flattened and lost its labels.

Device: Samsung `RFCT71FR9NT` (foldable, inner display id `4630946474867211650`), widget **345**
(`offset=-5`, NARROW, scrolled ~5h into the past). Pixel `2A191FDH300PPW` also connected.

**Outcome:** fixed and verified live — one `visibleHash` across 4 renders / 90s where there were
previously two alternating.

---

## Prompts (verbatim) and what each led to

### 1. "Samsung: narrow temperature graph: would be nice to know what the high temp is on the graph and or have a mid graph temperature label.  What do you think?  I'd even settle for left edge temp label."
- Pulled a screenshot instead of reasoning from source (foldable has 2 displays → `screencap` needs
  `-d <id>`, else it prepends a warning and the PNG is unreadable).
- Read it as: NARROW is `ZoomStage.NARROW(backHours=2, forwardHours=2)` = ±2h, so Tuesday's daily high
  is genuinely off-canvas; the label engine is extremum-anchored, so a flat window yields nothing.
  Recommended a **header** high/low readout over a curve label. That framing was right but became moot.

### 2. "Something happened and all of sudden a couple more labels appeared"
- `71.2°`/`69.1°` appeared between captures; current temp ticked 67.6 → 67.9.
- **WRONG CALL #1:** claimed a fetch had landed and "reshaped the curve from a plateau into a peak".

### 3. "with the other labels present, someting on left edge would feel like noise now."
- User withdrew the left-edge fallback. Agreed.

### 4. "samsung: two of the labels disappeared"
- Pixel-diffed the captures. **WRONG CALL #2:** described it as a "uniform vertical translation
  converging at the right edge". (It was neither a reshape nor a translation — see #16.)
- Real finding: only the pink observed line moved; forecast line, `73°`, axis were pixel-identical.
- Established the key property: **two states, each pixel-identical on repeat** (RMSE 0.0005 vs the
  0.086 between them). Not drift, not noise — two deterministic renders.

### 5. "yes, raise the throttle and capture it"
- Added `TEMP_ACTUALS_DUMP` (`Log.v`, logcat-only — never reaches `app_logs`).
- Kept `BLEND_DEBUG_THROTTLE_MS = 50` for the `app_logs` sample (zeroing it would have silently turned
  those 12 persisted rows from a time-spread sample into the first 12 consecutive points); added a
  separate unthrottled `allLines()` for logcat.

### 6. "I think should keep the logcat spam.  No harm done as far as I know"
- Made the trace permanent, using `Log.v` per the codebase's existing convention for high-frequency
  traces ([[verbose_level_for_high_frequency_logs]]).

### 7. "oscillated again, temp labels gone" / "Temperature graph change again and the labels are back"
- Confirmed the alternation continues unattended.

### 8. "while waiting should we fix the 68 and 68.1 label overlap?  I think 68.1 the higher tempature label should go above the curve."
- **WRONG CALL #3:** identified them as forecast `END` + observed `ACTUAL_END`, wrote a
  `computeRightEdgeEndOrdering` mirroring `computeLeftEdgeStartOrdering` (warmer above).
- First attempt broke 3 `ACTUAL_LOW` tests: `RIGHT_EDGE_ACTUAL_ROLES` was too broad, and `edgePairOrder`
  gates **four** behaviours (preferAbove, `placeActualHighAboveCurve` bypass, curve-avoidance exemption,
  `ACTUAL_LOW` tight-below). Narrowed to `ACTUAL_END` only → suite green.
- Then the new test failed at `assertNotNull(actualEnd)` — the engine deduped it. Pulled
  `TempLabelEngine` logs from the device:
  ```
  EngineInput: widthPx=567 fetchDotX=953.0 hardBounds=[]
  role=HIGH       idx=0   baseBounds=(0.0,  83.1, 40.0,113.7)   → the 73° label
  role=LOW        idx=30  baseBounds=(480.6,286.7,520.6,317.3)  → the grey 68
  role=ACTUAL_LOW idx=37  baseBounds=(505.0,283.5,567.0,314.0)  → the pink 68.1°
                          labelBlocker=true outcome=LABEL_OR_ICON_BLOCKED
  ```
  **No `END`/`ACTUAL_END` in that render at all.** The colliding pair is `LOW` vs `ACTUAL_LOW`.
  Reverted the change + its test. (`fetchDotX=953` vs `widthPx=567` also confirms the fetch dot is
  off-canvas — NOW is outside a scrolled-back window, as expected.)
- **Still open.** The engine already detects the collision (`labelBlocker=true`) and routes to the
  tight-below-trough fallback, which still overlaps on `x 505–520.6, y 286.7–314`.

### 9. "No, the previous changes happened without me scrolling" / "no scrolling"
- Killed the scroll-trigger theory. Flips are spontaneous.

### 10. "yes fix resolveStationValueAt"
- Found a **real** bug: a station's contribution to a *past* timestamp depended on whether it had a
  *later* reading. `after == null` → extrapolate (gap `before→target`, 63 min, passes);
  once a straggler lands → interpolation branch (gap `before→after`, 6h43m, fails) → `return null`,
  silently dropping the station from hours it already covered.
- Evidence was exact: KPAO's last reading `20:47`; the two renders diverged over `20:50–23:45` =
  `20:47 + MAX_EXTRAPOLATION_GAP_MS (3h) = 23:47`. To the minute.
- Fix: fall back to `extrapolateForward(before, …)` when the interpolation gap is too wide.
  `ActualsLateReadingIndependenceTest` (proven to fail without it; third test asserts non-vacuity).
- **This was NOT the flip.** Kept anyway — it's a genuine defect with a test.

### 11. "install it and let's see if the oscillation stops" → "flipped"
- Station retention rose (511 points at 6 stations vs 432/464) but flips continued.

### 12. "odd that the curve goes from flat to slight incline near 71.2 label"
- **The most diagnostic user observation of the session.** Side-by-side values showed State B sitting
  dead flat (`70.0,70.0,70.0,70.0`; `71.8,71.8`) where State A had real gradient. Flat ⇒ no interior
  extremum ⇒ nothing for `ACTUAL_HIGH`/`ACTUAL_LOW` to anchor to. The labels weren't blinking; the
  curve was losing its shape. Also killed WRONG CALL #2: the delta *tapered* (−1.20 → −0.10), a warp,
  not a translation.

### 13. "It seems to stay in this label shown mode for a while, but when scrolling back and forth: 1) it goes back to the no label mode. 2) then flips within a minute to the show label mode, and graph inclined"
- The reproducible sequence. This is what eventually cracked it — it named a **two-phase render**.

### 14. "yes, make the ordering total and install it"
- **WRONG CALL #4:** blamed non-total `ORDER BY timestamp` (several stations share timestamps; ties
  come back in arbitrary order; `sortedBy { it.timestamp }` is a *stable* sort so ties keep caller
  order, leaking into `groupBy` → `dominantByDay`'s `maxWith` tie-break and `anchorStation`).
- Made the DAO order total (`, stationId ASC`) and the blend sort
  `sortedWith(compareBy({ timestamp }, { stationId }))`.
- **`ActualsRowOrderDeterminismTest` passed WITHOUT the fix** → theory unproven. Later disproved
  outright: `inputOrderHash` was **identical** across a flip. Kept as correctness-by-inspection, but
  it is **unrelated to this bug** and should be described that way.

### 15. "It will flip within the next 2 minutes" / "it flipped"
- Built the digest instrument. Two false starts worth remembering:
  - **contextHash was too broad** — it hashes all 1042 context points over 72h, so it changes whenever
    *any* observation lands anywhere. Nearly reported "still diverging" off it. Split into
    `visibleHash` (drawn hours — the one to compare) + `contextHash` (colour only). Immediately proved
    itself: `contextHash 473437361 → 1430766121` while `visibleHash` held `743958645`.
  - **`sourceRows` count ≠ content.** Rows insert `onConflict=REPLACE` on PK `(stationId, timestamp)`,
    so a re-fetch overwrites temperatures with the count unchanged. Added `inputContentHash` (sorted,
    order-independent) + `inputOrderHash` (raw). I had been reading "same count" as "same data" for an
    hour. **WRONG CALL #5.**
  - Device caps logcat at 5 MiB (`-G` rejected, `setprop` blocked), so ~1040 lines/render burst was
    being truncated — a positional diff of two truncated captures looks exactly like divergence.
    One digest line per render is drop-proof.

### 16. "find the second loader"
- Caught the flip at one unchanged centre:
  ```
  04:24:18  offset=-5 center=…23:24:17.954  fcCount=7    → visible=1657342193  (no labels)
  04:26:43  offset=-5 center=…23:24:17.954  fcCount=226  → visible=1943784045  (labels + incline)
  ```
  `visibleHash` is a **function of `fcCount`** — it returns to byte-identical values when the count
  returns, *even though observations changed in between*. Two loaders, two curves.
- The two loaders:
  ```
  WeatherWidgetProvider  → WidgetRenderer      : now ± 72/168h  → 226  (07-12 05:00 → 07-21 14:00)
  WidgetIntentRouter     → GraphDataLoader     : centre ± zoom  →   7  (21:00 → 05:00)
  ```

### 17. "please make the fix"
- **Root cause:** `buildGraphQueryWindow` sized the query to `zoom.backHours/forwardHours` (display
  concern) while `ActualTemperatureSeriesBuilder` consumes forecasts across
  `contextLookback/LookaheadHours` = 72/60 (data concern) to extrapolate stations —
  `forecastTemperatureAt(series, before.ts) ?: return null`. 7 forecasts ⇒ null across the context ⇒
  every extrapolating station drops ⇒ flat curve, no extrema, no labels.
- Fix: `maxOf(zoom.backHours, HOURLY_LOOKBACK_HOURS)` / `maxOf(zoom.forwardHours,
  HOURLY_LOOKAHEAD_HOURS)`.
- `GraphQueryWindowCoversBlendContextTest` — proven to fail without it, **at every zoom** (`WIDE` fails
  too; this was never NARROW-specific). Needs `@Category(ShortDuration::class)` — app unit tests
  enforce exactly one bucket.

### 18. "The fix is working. Show label mode popped up after install, that didn't happen before."
- Flagged this as a **false positive** — I hadn't installed it; the restart just forced the wide-loader
  repaint. (User had in fact installed it themselves — see #19.)

### 19. "I installed it"
- Verified live, pid `18063`:
  ```
  04:29:59 offset=-5 fcCount=133 range=2026-07-11T23:00 → 2026-07-17T11:00   (= centre −72h/+60h)
  04:30:02 offset=-5 fcCount=133   ← stable
  04:30:05 offset=-5 fcCount=133
  ```
  Parked window, 4 renders / 90s → **one** `visibleHash` (`1943784045`), the label+incline state.
  Previously two, alternating within a minute. Live window likewise stable at `1593704405`.

---

## Fixes (none committed at time of writing)

| Change | Verdict |
|---|---|
| `GraphDataLoader.buildGraphQueryWindow` covers blend context | **The fix.** Test-proven, verified on device |
| `ActualTemperatureSeriesBuilder.resolveStationValueAt` extrapolation fallback | Real bug, test-proven, **unrelated to the flip** |
| Total ordering (`ObservationDao` + blend `sortedWith`) | Correct by inspection, **unrelated** — `inputOrderHash` was identical across flips |
| `TEMP_ACTUALS_DIGEST` / `TEMP_ACTUALS_DUMP` | Permanent diagnostics; what actually cracked it |

## Still open

- **`LOW` vs `ACTUAL_LOW` label overlap** (the grey `68` / pink `68.1°` smear). Correctly identified but
  unfixed. The engine sees it (`labelBlocker=true`, `outcome=LABEL_OR_ICON_BLOCKED`) and its
  tight-below-trough fallback still overlaps. User's steer stands: warmer above.
- **Residual loader mismatch.** `WeatherWidgetProvider` is `now`-centred (`now − 72h`); the blend wants
  `centre − 72h`. A widget scrolled far back (up to 30 days) can still be under-covered — same class of
  defect, much smaller. Not biting now (both loaders → `1943784045`).
- **Header high/low readout** — the original feature request, never built.

## Lessons

- **Screenshots lied twice** (reshape, translation); the per-point log had the answer written in its
  boundaries (`20:47 + 3h = 23:47`). Measure, don't eyeball.
- **Instrument the thing you're comparing.** contextHash (72h) vs visibleHash (drawn hours) — the wrong
  scope nearly produced a false "still broken". A count is not content.
- **"Two pixel-identical alternating states" means two code paths**, not noise. That property was
  visible in the first 10 minutes and I didn't read it correctly for hours.
- **Query windows must be sized by the CONSUMER, not the view.** Same class as
  [[generic_gap_long_term_only]] and the `PrecipGraphQueryWindowTest` 96h→168h fix.
- A test that passes without the fix is not a test. Both real fixes here were proven-to-fail first;
  the ordering "fix" was not, and that's exactly the one that turned out to be unrelated.

# Zoom-Dependent Rain Amount Threshold & Label Visibility Test Plan

**Date:** 2026-04-11
**Feature:** Rainfall amount annotations on the precipitation graph
**Problem:** Rainfall amount labels don't appear on the emulator despite seemingly plenty of space

---

## Root Cause Analysis

The rain amount label can be suppressed at **four** points:

| Stage | Code Location | Condition | Effect |
|-------|---------------|-----------|--------|
| 1. Data | `findHighProbRainPeriods()` L801-829 | `precipProbability < highProbThreshold` or `precipAmountMm` all null/zero | No RainPeriod found |
| 2. Amount check | `findHighProbRainPeriods()` L813 | `totalAmountMm <= 0f` | Period skipped even if prob >= threshold |
| 3. Label collision | Rendering L579 | `drawnLabelBounds.any { RectF.intersects(it, bounds) }` | Label blocked by existing label |
| 4. Vertical bounds | Rendering L580 | `bounds.top < graphTop \|\| bounds.bottom > graphBottom` | Label outside graph area |

The `onDebugLog` callback already emits `"rainAmountPlaced"` or `"rainAmountSkipped"` with overlap/bounds details.

**Most likely cause:** The user is viewing in WIDE mode (default), which uses threshold=99. No NWS hours currently reach 99% — the highest is 97% at 19:00. Only NARROW (threshold=97) would find that hour.

---

## Runtime Evidence Collection (do first)

```bash
# Screenshot
adb shell screencap /sdcard/screen.png && adb pull /sdcard/screen.png

# Watch for rain amount debug logs
adb logcat -s PrecipGraphRenderer | grep -E "rainAmount(Placed|Skipped)"

# Check zoom mode
adb shell "run-as com.weatherwidget cat /data/data/com.weatherwidget/shared_prefs/widget_state_prefs.xml"
```

---

## Test Plan

### Layer 1: Unit Tests (`PrecipitationGraphRendererTest`)

**1.1 `highProbThreshold=97 catches periods that 99 misses`**
- 5 hours at 97% w/ 1mm/h each, rest at 50%
- Assert: threshold=97 returns 1 period (totalMm=5.0); threshold=99 returns 0 periods

**1.2 `highProbThreshold=97 mixed block (97, 99, 98) merges correctly`**
- 3 hours at [97, 99, 98]% w/ 1mm/h each
- Assert: 1 period spanning all 3, totalMm=3.0

**1.3 `single hour at 97% produces rain amount label`**
- 5 hours, only index 2 at 97%, precipAmountMm=5.0f, rest at 50%
- `renderGraph(highProbThreshold=97)`  ->  debug log contains `"rainAmountPlaced"`, no dash in label

**1.4 `single hour at 97% does NOT produce label when highProbThreshold=99`**
- Same data as 1.3, `renderGraph(highProbThreshold=99)` -> no `"rainAmountPlaced"`

**1.5 `97% block skipped when label overlaps existing probability label`**
- 12 hours, 97%+ block at indices 3-5, small widthPx=300
- Assert: `"rainAmountSkipped"` with `overlaps=true`

**1.6 `97% block placed when no label overlap`**
- 12 hours, generous widthPx=1000, heightPx=400
- Assert: `"rainAmountPlaced"`

**1.7 `97% block placed at correct vertical position`**
- 12 hours varying probs, 97%+ block in middle
- Assert placed label is within graphTop..graphBottom

### Layer 2: Instrumented Tests (`RainAmountThresholdInstrumentedTest`, new file)

**2.1 `97% single hour produces rain amount label on device`**
- 24h NWS-like data, one hour at 97% (2.1mm), widthPx=1000
- Assert: `"rainAmountPlaced"` in debug logs

**2.2 `97% label not placed when highProbThreshold=99`**
- Same data, threshold=99 -> no `"rainAmountPlaced"`

**2.3 `97% label collision detectable on device`**
- Narrow graph (widthPx=300), many probability labels near the block
- Assert: `"rainAmountSkipped"` with `overlaps=true`

**2.4 `real NWS scenario end-to-end`**
- Exact current forecast: 19:00 at 97% with 2.1mm QPF
- `renderGraph(highProbThreshold=97, widthPx=1080, heightPx=400)`
- Assert: `"rainAmountPlaced"` for period including 19:00

---

## Files

| File | Action | Tests |
|------|--------|-------|
| `PrecipitationGraphRendererTest.kt` | EXTEND | 1.1-1.7 |
| `RainAmountThresholdInstrumentedTest.kt` (new) | CREATE | 2.1-2.4 |

No production code changes expected unless runtime evidence reveals a rendering bug.
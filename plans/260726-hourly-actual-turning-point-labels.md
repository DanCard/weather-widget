# Hourly Actual Turning-Point Labels

## Evidence

1. Emulator `emulator-5554` (Google API 36) shows the NWS hourly temperature graph for
   Saturday, July 25, 9 AM–1 PM.
2. The dashed forecast curve has `63°` and `73°` labels; the pink observed curve has none.
3. Runtime logs show 57 observed points. The visible observed minimum is the left boundary
   (`66.954796°F`) and the visible observed maximum is the right boundary (`73.8115°F`).
4. Current policy intentionally rejects boundary samples as unconfirmed actual extrema, so the
   pink series produces no label candidates even though it has visible interior turns.

## Goal

Label meaningful peaks and valleys on the observed curve without labeling window edges or
five-minute station/blending noise.

## Plan

1. Log visible observed turning points and their bilateral prominence at VERBOSE, then reproduce
   the current emulator graph to establish a threshold from the rendered data.
2. Extend the shared extrema model with prominent observed-local extrema, keeping current daily
   high/low behavior unchanged.
3. Feed those points into the shared label engine as actual-colored peak/valley candidates, with
   normal collision avoidance and candidate-count limits.
4. Add focused shared JVM tests for:
   - a historical slice whose only useful actual labels are an interior peak and valley;
   - rejection of small observed wiggles;
   - continued rejection of edge-only values.
5. Run targeted shared tests, install the debug APK only on the emulator, and verify the actual
   label text/color/placement from logcat and a screenshot.

# Label Collision: Temperature-Ordered Z-Stacking

**Date:** 2026-04-08
**Component:** TemperatureGraphRenderer.placeTemperatureLabels()

## Problem

When actual and forecast temperature labels collide at a valley or peak, the
current sort order places them in the wrong vertical stacking:

- **Valleys:** The colder of two colliding labels gets flipped above the warmer one.
  The user wants the colder (lower) label on the bottom.
- **Peaks:** The lower of two colliding peak labels gets the top position.
  The user wants the higher (warmer) peak label on top.

### Evidence (emulator log, 2026-04-08)

```
LOW         idx=60  52°   placed below  → Y=281-311
ACTUAL_LOW  idx=86  51.8° rejected below (collision), flipped above → Y=245-275
```

Result: 51.8° (colder) is above 52° (warmer). The warmer valley should be on top.

## Root Cause

The candidate sort in `placeTemperatureLabels()` (line ~961):

```kotlin
if (isPeak) it.rawTemperature else -it.rawTemperature
```

This sorts:
- **Peaks** ascending → lowest-temp peak placed first (gets preferred top position), higher peaks displaced below
- **Valleys** descending → highest-temp valley placed first (gets preferred below position), colder valleys flipped above

The sort should produce the *opposite*: first-placed = preferred position, and the
extremes with the most extreme temperatures should get the preferred position.

## Fix

Negate the sort key:

```kotlin
if (isPeak) -it.rawTemperature else it.rawTemperature
```

Result:
- **Peaks** descending → highest temp placed first → gets top position; lower peaks displaced below ✓
- **Valleys** ascending → lowest temp placed first → gets bottom position; warmer valleys displaced above ✓

### Expected behavior after fix (same data)

```
ACTUAL_LOW  idx=86  51.8° placed below (preferred) → bottom
LOW         idx=60  52°   placed below → collides → flips above → top
```

Lower temperature (51.8°) on the bottom, higher temperature (52°) on top. ✓

## Scope

Single sort-key change in `TemperatureGraphRenderer.kt` line ~961.
No new collision logic needed — the existing displacement/flip mechanism already
produces the right result once the ordering is correct.

## Risk

The sort also changes global ordering from valleys-before-peaks to
peaks-before-valleys. This is benign because peaks (top of graph) and valleys
(bottom) are spatially separated, and the collision detection + displacement
system handles any cross-group overlap.

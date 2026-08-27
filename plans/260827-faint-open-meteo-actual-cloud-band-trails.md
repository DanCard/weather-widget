# Faint Open-Meteo actual cloud-band trails

**Date:** 2026-08-27
**Status:** Approved for implementation
**Scope:** Shared glyph placement plus Android and desktop cloud-cover rendering

## Evidence

Open-Meteo is the only current source with dense total, low, middle, and high percentage values.
The live desktop database showed a useful transition on 2026-08-27: total cloud cover stayed near
100% while the contributing band changed from middle to high. The renderer already carries
Open-Meteo `actualBands` and already draws curves made from repeated `m` and `h` glyphs, but pink
actual-band glyphs are currently restricted to errors against a genuine frozen forecast. That gate
hides the actual layer shape when the user wants to see it directly.

## Decision

1. Draw actual middle/high band trails whenever those actual values exist; do not require a frozen
   forecast or a minimum forecast error.
2. Continue using repeated lowercase `m` and `h` characters as the dashed curve itself.
3. Use the existing actual-series pink and normal weight. The first emulator render showed that
   5.25 dp at the forecast's 13 dp spacing still produced too much ink on steep layer transitions;
   the first revision used 4.5 dp actual glyphs at 20 dp spacing.
4. Keep the existing 5% visibility floor, so 0% produces no glyphs.
5. Additionally suppress an actual glyph when its interpolated band and total both equal 100%.
   Apply this per glyph rather than deleting an entire point or segment, preserving adjacent
   non-extreme portions of the trail.
6. Do not change gray forecast trails, their frozen/live resolution, or the total forecast/actual
   curves.
7. Preserve the existing phase offsets, sibling nudges, total-curve collision handling, and label
   obstacle reporting on both platforms.

## Implementation

1. Extend `CloudLayerGlyphPlacer.place` with an opt-in endpoint-match suppression flag used only by
   actual band trails.
2. Add shared actual-glyph size and spacing constants smaller/sparser than the forecast treatment.
3. Feed raw actual middle/high bands into Android and desktop renderers rather than filtering them
   through `divergentActuals`.
4. Enable endpoint-match suppression for both actual trails.
5. Size actual paints/styles and their obstacle bounds from the smaller shared constant.
6. Update stale comments and diagnostics that describe actual trails as error-only.

## Visual review revision

The first Android and desktop runtime review still looked too noisy. The approved follow-up makes
forecast glyphs the same 4.5 dp size as actual glyphs and increases both existing trail spacings by
30%: forecast 13 dp -> 16.9 dp and actual 20 dp -> 26 dp. Forecast remains neutral and bold; actual
remains pink and normal weight.

## Verification

1. Shared tests: exact 100/100 suppression, 100/different-total retention, 99/99 retention, zero
   suppression, per-glyph continuity, and the smaller actual-glyph size contract.
2. Android Robolectric: agreeing and unfrozen actual bands now add pink glyph ink; endpoint matches
   do not add ink; actual glyph obstacle bounds remain valid.
3. Desktop Compose test: mirror the Android behavior and obstacle contract.
4. Run focused shared, Android, and desktop tests, followed by affected compile/build tasks and
   `git diff --check`.
5. Install/render on the running emulator and inspect a screenshot plus renderer diagnostics.
   Verify desktop rendering as well, using the same Open-Meteo data window where practical.

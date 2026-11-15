# Night rain amount: centre a ".45in" label; smaller lower-case "trace"

## User Prompt

> .45 inch night time rain label between tuesday and Wed. Can I get it more tucked near 42° label,
> so that it is more centered between two columns?
> The trace text between last fri and sat is too big for the info it conveys. Can we change that to
> lower case trace and reduce font size?

## Evidence (Fold 4 inner, NWS widget 392, 2026-11-15 07:41, 10 columns)

Screenshot: `.45in` sits low and right of Tue's `42°`; `Trace` between Fri and Sat is as large as the
amounts beside it.

```
nightRainLabel position: date=2026-11-17 text=".45in" home=(289.4,327.9) size=35.0x19.9
  resolved=(294.3,355.1) sideSlideDp=1.9 degreeDropDp=2.3 placement=down offsetDp=(0.0,8.0)
  obstacles=[…;238.8,334.0,272.8,370.9 (Tue "42");272.8,334.0,284.8,352.5 (Tue °);…]
nightRainLabel position: date=2026-11-13 text="Trace" size=38.0x17.9 resolved=(49.9,277.6)
```

## Root cause

1. **`.45in` off centre.** The search drops the label 8 dp into the pocket under Tue's °, centred on
   the gap (x = 289.4). Then `slideOffLeftNeighbor` (added this morning for "45°97%") finds the label's
   box 0.9 px inside the "42" digit box and slides it 1.9 dp right to open a 1.5 dp gap. But the
   label's box starts with the decimal point, a speck of ink at the label's baseline, below the ink
   of "42". At digit height the label's ink starts a dot-width further right, so the gap was already
   there; the slide adds a second one.
2. **`Trace`.** `PrecipTrace.LABEL = "Trace"`, drawn at the same font scale as a measured amount.

## Proposed change (all in `:shared`; Android and desktop follow)

1. `slideOffLeftNeighbor` takes a `leadingBlankPx`: the measured width of a leading "." (via
   `Host.measure(".", NIGHT, fontScale)`), counted as gap only when the dot's ink is below the left
   digits' ink bottom (box bottom less `DIGIT_BLANK_DESCENT_FRACTION`). Labels not starting with "."
   pass 0 and are unchanged. Expected here: slide 1.9 dp -> ~0.1 dp, so `.45in` moves ~1.8 dp left
   to the centre of the gap. No vertical change. Trace gains `leadingBlankDp=`.
2. `PrecipTrace.LABEL` = `"trace"` (every screen that prints it).
3. *(Claude proposal: the size)* a trace label in the daily view is drawn at 0.75x, the peak-hour
   label's scale (`RAIN_PEAK_FONT_SCALE`), day and night labels alike.

## Tests

- `DailyRainLabelLayoutTest`: today's Fold fixture for `.45in` (fails on current code: resolved x
  294.3, expected ~289.8); a `97%` fixture stays slid; a ".3in" level with the left digits' ink
  still slides.
- `DailyRainLabelsTest` / `RainAmountLabelPlacerTraceTest`: "trace" text; trace font scale 0.75x.

## Verification

- `./scripts/unit-tests.sh`.
- installDebug on the Fold and both emulators; diff every `resolved=` before/after per device (only
  labels starting with "." or reading "trace" may move); screenshots of the Fold inner widget.
- Desktop: rebuild, restart, check the same two labels.

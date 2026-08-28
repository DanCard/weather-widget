# Actual cloud line now reads max(low, mid, high) of the blended bands

**Date:** 2026-08-27

## User prompts

1. "The previous commit added cloud cover actuals to nws api based on cloud level: low, medium,
   high. I see it on my samsung phone but not on desktop."
2. "How does actual line work? I thought it was max of low, medium, and high cloud cover. That
   doesn't seem to be the case, because I see m above actual line."
3. "I see m above actual line, I don't understand your explanation. The m value is not zero, so
   that has no bearing. What is the m value at noon? What is the actual line value at noon?"
4. "change it so actual line = max(low, mid, high)" / "confirm"
5. "I see it working on desktop and samsung phone"

## Part 1 — the desktop simply had a stale binary

The layer-actuals work (commit `7eb5a3d7`, "Reserve sky-reporting station slots and draw all actual
cloud layers", 16:23) landed ~40 minutes after the last `createDistributable` (15:41); the desktop
app also wasn't running. The phone had the newer build, the desktop launcher did not. Fix: rebuild,
relaunch, verify. The obs DB then showed fresh `CUMULATIVE_LAYERS` rows (1,008; 970 with low, 96
with mid) and the graph drew the pink actual line plus the l/m layer glyphs.

Desktop window-management notes learned along the way: the daemon spawns the UI process only on a
`.show` trigger in `~/.local/share/weather-widget/` (the XFCE genmon click does the same);
`.ui-show` is daemon → UI, meaningful only once the UI exists.

## Part 2 — the m-above-the-line mystery

### The line and the glyphs voted differently

Per station, the total already IS `max(low, mid, high)` where no total column exists
(`VisibleCloudCover`; METAR stores no total by design). But `MetarCloudBlender.blend()` computed the
drawn line and the drawn band glyphs from different voter sets at each timestamp:

- Line = inverse-square-distance (IDW) blend of **every station's total** — a clear station votes 0.
- Each band = IDW blend of only the stations that **reported that band** — a band-silent station is
  silently absent (null = "not reported", never 0, by design from `ccee8f2a`).

### Measured at noon, 2026-08-27

| Station | Dist | Report | low | mid | Its total | IDW weight |
|---|---|---|---|---|---|---|
| KNUQ | 3.8 km | METAR 11:55, CLR | 0 | null | 0 | 69% |
| KPAO | 6.1 km | METAR 11:47, `BKN100` | null | 75 | 75 | 27% |
| KSJC | 15.9 km | METAR 11:53, deck at 10,000 ft | 44 | 75 | 75 | 4% |

- Line = IDW of totals ≈ (0×0.069 + 75×0.027 + 75×0.004) / 0.100 ≈ **23**
- m glyph = IDW of the mid column (KNUQ absent) = **75**

The blind-spot filter (`CeilometerBlindSpot`, commit `4c2c053c`) deliberately did NOT fire: the deck
base (~3,050 m) is inside the 3,658 m ASOS ceilometer ceiling, so KNUQ's clear was a trusted
measurement of real spatial variation — and even so it could only vote on the total, never on the
mid band. The asymmetry made layer glyphs systematically cloudier than the line whenever stations
disagreed.

## The fix — line = max(blended low, blended mid, blended high)

One change in `shared/.../actuals/MetarCloudBlender.blend()`: at each timestamp the point value is
now the max of the blended bands when any band exists; the blend of per-station totals remains only
as a fallback when no band exists at all (so total-only stations still draw a line). Band-silent
stations can no longer drag the line below what layer-reporters saw, and the drawn curve obeys the
same per-station rule it always claimed: max(low, mid, high). Single change in `:shared` so Android
and desktop stay identical; no renderer changes.

Consequences signed off by the user beforehand:

1. The line ascends to the highest blended band (noon: 23 → 75), so a *trusted* nearby clear no
   longer moderates the line when airports carry bands; the ceilometer blind-spot filter becomes
   mostly moot on the actual line.
2. The top band's glyph is suppressed by construction (the line already shows the controlling
   layer), so `m` disappears wherever the line rises to it.

### Tests updated (`:shared`)

- `CeilometerBlindSpotBlendIntegrationTest`: the SKC case renamed to "casts its clear vote on the
  low band while the line follows the max band" — asserts `ceilometerBlind=0`, low band = 0 (the
  SKC vote kept), line = 75.
- `ObservedCloudBandsReadTest`: line assertion is the max of blended bands (60) instead of the
  blended totals (70).

Suites green: `:shared:test`, `:desktop:test`, `:app:testByDurationDebugUnitTest`. Desktop rebuilt,
relaunched, screenshot-verified: noon reads 75 on the line, the stray `m` glyphs are gone, `l`
glyphs remain where the low band (44) trails the controlling mid band. User confirmed both desktop
and Samsung phone show the fix.

# Fog ellipse: height from the fog proper, density weighted to the bottom

User (2026-11-11): "Desktop: tomorrow.io, monday from two days ago. I'm wondering what options if any
exist to make the fog less tall. Only lasted a couple of hours but the height makes it look like it
last much more." Chose options **B + C**.

## Evidence (desktop DB, 2026-11-09)

- Bar 49.8° → 69.8°; stored `observedFogLevel=4`, `observedFogTopTemp=58.1`.
- Ellipse height = (58.1 − 49.8) × 1.15 ≈ 48 % of the bar.
- KNUQ: `FG` ≤ ¼ SM 05:55–09:15 (52–55°); `BR` 2½ SM 09:35 and 5 SM 09:55 (55–57°); clear 10:15 (59°).
  The 40 minutes of thinning mist moved the top from 55.4° to 58.1°.

## Root cause

Height is the low → burn-off temperature span. The burn-off is taken after the *last foggy report of
any level*, so trailing mist sets the height of a dense-fog morning. And the uniform nested layers make
the ellipse read as dense all the way to its outline.

## Fix

**B — height from the morning's fog proper (shared `MorningFog.heightLevel`).** The tier that sets the
top is `min(strongest level, FogLevel.FOG)`: on a morning with fog (FG, or ≤ 1 SM), only reports/hours
at FOG or above set the burn-off; mist and hints still count for the level and the visibility (opacity)
but not the height. A mist-only morning is measured by its mist, a hint-only one by its hints. The
"first clear" is the first report/hour after the last tier report that is below the tier.
Applied to both `ObservedMorningFog` and `ForecastMorningFog`.

Monday becomes 49.8 → 55.4 (midway 09:15 55.4° / 09:35 55.4°): ~32 % of the bar instead of ~48 %.
`WRITE_RULES_VERSION` 4 → 5 so stored past days are rewritten.

**C — bottom-weighted layers (`FogPatch`).** Each nested layer shrinks mostly from the top
(`TOP_SHRINK` 0.75 of the height at the innermost layer, `BOTTOM_SHRINK` 0.10), and layer alphas are
weighted to the inner layers (`ideal(k) = 1 − (1 − A)^((k/L)^2)`). Centre-line opacity at 10 % steps
of the height: 0, .65, .65, .60, .45, .39, .28, .18, .10, .04, 0 (was symmetric, .65 through 40–60 %).
Horizontal insets unchanged.

## Tests

- `ObservedMorningFogTest`: Nov 9 top 55.4; a mist-only morning still uses its mist.
- `ForecastMorningFogTest`: fog hours then mist hours → top from the fog hours.
- `FogPatchTest`: layers nested, combined centre alpha = target, inner layers' centres below the
  outer centre, top band fainter than bottom band.
- Desktop visual check of 2026-11-09 on Tomorrow.io.

## Docs

`arch/daily-history-extremes.md` (top rule), `arch/ARCHITECTURE.md` (FogPatch weighting).

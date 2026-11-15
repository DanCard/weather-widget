# Heavy rain amount replaces the chance % on future days and later nights

Date: 2026-11-15

## User prompts

1. "What is the rule for displaying rain fall amount vs chance of rain in daily forecast view?"
2. "For open meteo the amount of rain is very interesting, 2.6 inches. I wonder if we can blend the
   chance of rain with very interesting rain fall amount? 97% chance of rain is interesting, but 2.6
   inches is more interesting. Something greater than 1 inch starts to get interesting. What do you
   think?"
3. "O.k. if greater than 95% chance of rain and rain chance more than an inch, show show rain fall
   amount instead in the future."
4. "yes, same rule for night label. Write recap to summaries/ dir, don't append "-recap" to
   filename. commit when done"

## Rule before

- Future day: amount only at chance ≥ 99%, else chance %.
- Today / tonight: amount at chance ≥ 95%.
- Later nights: amount only at chance ≥ 99%.

## Change

In `DailyRainLabels` (`:shared`, so Android and desktop both get it):

1. New constants `HEAVY_RAIN_MIN_EXCLUSIVE_MM = 25.4f` (1 inch) and
   `FUTURE_HEAVY_AMOUNT_MIN_EXCLUSIVE_PERCENT = 95`.
2. `buildDailyRainLabel`, future days: the amount also shows when chance > 95% and the 8am–8pm
   forecast amount > 1 in.
3. `buildNightRainLabel`, later nights: the same, using the night forecast total.
4. Today, tonight, and past days/nights are unchanged. Both cutoffs are exclusive: exactly 95% or
   exactly 1.00 in keeps the %. Millimetre locales use the same physical cutoff (25.4 mm).

## Evidence

- Open-Meteo on the Fold (backup 2026-11-15 06:40): Wed Nov 18 has 2.62 in between 8am and 8pm
  (2.87 in all day) at 97%.
- After install, `DailyRainLabelLayout` logged `date=2026-11-18 text="2.6in"` (peak `8am`) on both
  `platform=android` and `platform=desktop`. Before the change this label read `97%`.

## Tests

- `DailyRainLabelsTest`: future day 97%/2.6 in → `2.6in`, 96%/1.5 in → `1.5in`; 95%/2.6 in →
  `95%`; 97%/1.0 in → `97%`; today unchanged; later night 96%/1.5 in → `1.5in`, 95% and 1.0 in
  boundaries keep the %.
- `./scripts/unit-tests.sh`: 7028 tests passed. Installed on the Fold and the emulator; desktop
  restarted.

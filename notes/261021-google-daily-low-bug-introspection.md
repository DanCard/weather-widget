# Introspection: how the Google daily-low bug happened, and how to avoid the next one

*Date: 2026-10-21*

## The bug

Emulator, Google, Wed 2026-10-21: the daily view's low was **51°**, the hourly graph's **57.5°**;
official stations measured **57.4°**. Google's day runs 07:00 → 07:00, and its `minTemperature` is
the minimum of that **whole** window — its own 07:00 included. After a cold morning (Tue 07:00
50.9°) and a mild night (57.5°), that minimum was Tuesday's morning, and
`GoogleWeatherApi.lowsFiledUnderTheirMorning` filed it under Wednesday. Wrong on 2 of 14 stored
days (10/16, 10/21), for 11 days.

Fix: `plans/261021-google-daily-low-from-hourly-morning.md` (commit `983c9ae4`).

## How it happened

1. **A semantic rule inferred from data that could not tell two readings apart.** The 2026-10-10
   fix (`plans/261010-google-daily-low-filed-under-the-morning-it-ends.md`) had to decide what
   `minTemperature` means. Two readings fit:
   - (a) the low of the night *after* the date;
   - (b) the lowest temperature anywhere in the 07:00 → 07:00 window, its own 07:00 included.

   Its evidence table had four days, all cooling nights — where (a) and (b) give the same number.
   The plan picked (a) and even wrote "almost always the next morning", but nothing handled the
   exception. A warming morning is exactly where they differ.

2. **The ground truth was in the same response and was never consulted.** Every Google fetch brings
   the daily minimum *and* the hourly temperatures for the same night. One comparison would have
   shown 57.5° beside 51°. The code chose an interpretation of a derived field instead of checking
   it against the primary series it already had.

3. **The tests encoded the assumption instead of testing it.** The fixtures were recorded on the same
   kind of days, so they agreed with the rule by construction. The integration fixture passed
   *with and without* the fix until its day minima were forced to 40°.

4. **Nothing checked at runtime that daily and hourly agree.** The app logs heavily, but no line
   said "daily low 51°, hourly morning minimum 57.5°" for the same fetch. It ran 11 days until a
   person happened to compare two screens.

5. **Repair depended on luck.** Correcting the stored rows afterwards needed hourly snapshots that
   had survived retention pruning. On the emulator the row that mattered had none; a snapshot 20
   minutes later stood in.

## What prevents it

1. **Daily-vs-hourly consistency log** — *done 2026-10-21.* `:shared` `DailyHourlyConsistency`
   compares each fetch's daily high/low with the same fetch's hourly, for every source on both
   platforms, and logs `DAILY_HOURLY_MISMATCH source=… <day>:low daily=51.0 hourly=57.5 day=57.5`
   (WARN, one line per fetch). Days after today with full 00:00–24:00 coverage only; highs flagged
   beyond 3 °F of the hourly maximum, lows only when beyond 3 °F of **both** the 00:00–09:00 and the
   whole-day minimum (sources differ on the low's window). A dry run over stored data flagged 2 of
   ~130 source-days on the desktop and 5 of ~140 on the Pixel — including this bug's 10/16 and two
   worth a look (Tomorrow.io 10/10 low 46° vs hourly 53–57°; NWS 10/16 high 86° vs hourly 91°).
   It would have fired on 10/16, the first wrong day.

2. **A scan script over device databases** — *not done.* Like `scripts/google_usage_today.py`:
   pull each device's DB and list the days where a stored daily extreme disagrees with the stored
   hourly. Finds the same class in data already stored.

3. **Plans that interpret provider fields must name the competing readings and show a case that tells
   them apart** — *saved as a memory.* If the recorded data has no such case, add a synthetic test
   that does. Evidence consistent with every reading proves nothing.

4. **Every bug-fix test must fail on the old code** — *saved with 3.* Revert the fix temporarily and
   confirm. In this session that caught two problems: the integration test that passed either way,
   and a first implementation that took today's low from elapsed `history/hours` (what happened,
   not a forecast).

## General lessons

- When a provider sends the same quantity two ways (daily extremes and an hourly series), their
  disagreement is information. Prefer the primary series; cross-check the derived one.
- Recorded fixtures capture the weather of the recording days. They test parsing well and weather
  patterns not at all; rules about patterns need synthetic cases.
- A rule that says "almost always" needs the exception written as a test, or it is a bug waiting for
  the right weather.

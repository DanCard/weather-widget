# Decide whether Tomorrow.io six-hour cloud history is an actual

Date: 2026-08-21

## Question

Does the six-hour lookback returned by Tomorrow.io's Timeline API represent source-native actual
cloud cover, or is it revised forecast/history data?

## Current evidence

The desktop database currently has only:

1. Seven `TOMORROW_IO_RECENT_HISTORY` hourly rows, spanning 09:00–15:00 on 2026-08-21.
2. One `TOMORROW_IO_REALTIME` sample, at 15:03 on 2026-08-21.

The seven history rows are mixed. Some cloud-cover values differ materially from forecasts archived
before their target hour, while others match the prior forecast exactly. This disproves neither
interpretation and is not enough data for a decision.

## Collection window

1. After 24–48 hours: make a preliminary assessment.
2. After three days: make an early decision if the signal is strong and consistent.
3. After seven days, around 2026-08-28: make the recommended application-level decision. This should
   provide roughly 168 unique Timeline-history hours plus the realtime samples accumulated during
   those hours, including a better range of cloud conditions.

## Evaluation

For every elapsed hour with overlapping data:

1. Compare the six-hour-history cloud percentage with the realtime samples collected during that
   same hour.
2. Compare both with the forecast snapshot archived before the target hour.
3. Calculate cloud-cover error between history and realtime, and between the prior forecast and
   realtime. History should be materially closer to realtime than the prior forecast if it is useful
   as an actual.
4. Check whether the Timeline value for an elapsed hour changes on subsequent fetches. Continued
   revisions would be evidence that it is a hindcast or revised forecast rather than a fixed actual.
5. Review temperature alongside cloud cover as a secondary consistency check, but decide cloud-cover
   provenance from cloud-cover results.

## Decision rule

Keep `TOMORROW_IO_RECENT_HISTORY` as an actual only if it consistently tracks the realtime product
and behaves like a fixed elapsed-hour value rather than a revision of the prior forecast. Otherwise,
delete the recent-history rows, disable Timeline backfill, and retain only accumulated
`TOMORROW_IO_REALTIME` actuals.

## Limitation

This comparison can determine whether Timeline history represents Tomorrow.io's realtime product.
It cannot prove that either product is a physical station observation; Tomorrow.io realtime may
itself be modeled or analyzed data.

Related plan:
`plans/260821-tomorrow-realtime-actuals-source-isolated-codex.md`

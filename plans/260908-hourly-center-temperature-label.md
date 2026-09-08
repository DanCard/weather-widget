# Hourly center temperature label

## Evidence

The live desktop temperature graph spans Monday 12 PM through 4 PM, placing 2 PM at its visual
midpoint. Its shared label pipeline only injects a midpoint when the surviving labels are the two
edges, so the existing interior extrema suppress the midpoint even though no temperature is shown
at 2 PM. The stored NWS data has both an observed series and a forecast value at that time.

## Change

1. Make the shared temperature-label collector add one center label on graphs wide enough to carry
   it, using the sample nearest the temporal midpoint of the visible window.
2. Use the actual value and actual styling when the actual line covers that sample; otherwise use
   the forecast value and forecast styling.
3. Give the center candidate first placement priority and replace any other candidate at the same
   sample, without drawing a vertical marker.
4. Use the shared behavior from Android and desktop, add focused pure-JVM tests, compile both
   platforms, then rebuild/restart and inspect the live desktop graph.

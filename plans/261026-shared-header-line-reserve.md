# Header buttons: one shared line, reserved against every visible source

## Symptom
Desktop (802×785, ×2.45), 2026-10-26: switching source slid every centre button and the date
(~20 px NWS → Meteo). Narrow-band air was also the 6dp floor, so the row read as clumped.

## Root cause
1. `HeaderButtonZone.airDp` preferred the 6dp floor below 360dp; desktop's square window reads as a
   ~327dp header (802 / 2.45).
2. Desktop's floating line (`HeaderFit.floatingSeamOffset`, `d154c61e`) was computed from the live
   temp / delta / rain % and source label, so a source tap moved it. Android used a fixed 70dp line,
   so the two platforms placed the row by different rules; Android's zones still squeeze on the live
   left cluster, and its date still fits against the live label.

Rejected on the way (user, 2026-10-26):
- Growing the zones into leftover room: content-dependent, so every button moved on a source tap.
- Reserving a full-size "100%" and "100.0°": dropped the daily date on the 802×785 window.

## Fix
1. `HeaderButtonZone`: < 360dp prefers 8dp air (floor stays 6).
2. `:shared` `HeaderLine` owns where the line goes on both platforms:
   - `HeaderContent.lineInputs` — each visible source's label and the rain % it would show now
     (`precipLabels`, the `build` rule).
   - `HeaderLine.place(measured, measure, inputs, nominalDate)` — reserve = icon + "00.0°" + "+0.0" +
     widest rain % on the left, widest source cluster on the right; the fit gets the widest source
     cluster, and the line is `floatingSeamOffset` with the reserve. Platforms that cannot move the
     row off the middle (Android < 31: `seamOffset == null`) are unchanged.
   - Platforms supply only `HeaderLine.Measure` (text widths at the drawn size) and the all-source
     hourly rows they already load (desktop: `ForecastSnapshot.allSourcesHourly`; Android: the
     graph loader's unfiltered rows).
3. Android API 31+ moves from the fixed 70dp line to the shared floating line (daily, temperature,
   precip, cloud headers).

## Tests
- `HeaderFitTest`: the reserved line does not move across a source tap (fails unreserved).
- `HeaderContentTest`: `precipLabels` equals what `build` shows each source.
- `DesktopHeaderFitTest`: dry and rainy NWS ↔ Open-Meteo switches move neither buttons nor date.
- `DesktopHeaderDateIntegrationTest`: the date stays on 802×785.
- Android Robolectric: a source switch with different rain % moves neither buttons nor date.

## Verification
Desktop screenshots cycling every source. Pixel 7 Pro (373dp daily ×3) and Fold (337/545/662dp,
daily + hourly): `HEADER_FIT` before/after per widget, screenshots, every source cycled on one widget.

## Result (2026-10-26)
- Desktop: all five sources cycled on the real window, buttons and date unmoved.
- Phones, current source, `HEADER_FIT` before → after: every widget shows the same items; the line
  moved 6–25 px; the Fold's 337dp widget now fits on the line instead of the middle.
- Robolectric probe (scale 1, NWS 5 % vs Meteo 70 %): 460–470dp now still (moved before); but
  480–530dp drop the daily date the fixed line kept, and ~340dp now moves. No widget on the test
  phones is in those bands.

## Out of scope / remaining
- Android's wide-widget header scale (1.35 vs 1) is chosen from live occupancy, so it can flip
  between sources.
- Hourly panned into the past: other sources may lack live rows, so their rain % is not reserved.

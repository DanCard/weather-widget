# 260907 — Lift the Today-overlay widget width requirement

## User prompt

> pixel pro 7: I enabled show dominate station in settings, but I'm not seeing it in daily forecast view, today column

> I started emulator and connected pixel. Issue exists on both devices.

> Lets remove the widget width requirement and see how that looks

> Write above recap to summaries/ dir

## Diagnosis (evidence-first)

1. **No `TODAY_OVERLAY` app_logs rows on either device** (Pixel: none ever; emulator: none since
   Sep 5, when widget 7 logged `dominantTemp=62.6° stationId=KNUQ` — feature worked then).
2. **Preference was correctly set** on both devices: `show_today_overlay_dominant_temp=true` and
   `show_today_overlay_delta=true` in `shared_prefs/widget_state_prefs.xml`.
3. **Pixel widgets are too small**: widget 79 renders `DAILY_RENDER … mode=TEXT` (1 row — no graph
   bitmap, and the overlay only paints on the graph); widget 86 is 373×310 dp.
4. **Emulator widget 7**: `DAILY_RENDER … cols=5 rows=3 mode=GRAPH` — below the policy minimums.
5. Root cause: the dominant-station row lives inside the **large Today overlay**, gated by
   `LargeTodayOverlayPolicy` — `ANDROID_WIDGET minColumns=10`, `MIN_ROWS=4`, graph mode, today
   visible. Every render today bailed at the policy before the overlay code ran.

## Change

- `shared/.../graph/LargeTodayOverlayPolicy.kt`: `ANDROID_WIDGET(minColumns = 10)` →
  `ANDROID_WIDGET(minColumns = 3)` — the floor where Today's doubled slot
  (`TODAY_SLOT_SPAN = 2`) still leaves at least one full day column. `MIN_ROWS=4`, graph mode,
  today-visible, and `DESKTOP minColumns=9` are unchanged.
- `shared/src/test/.../LargeTodayOverlayPolicyTest.kt`: added cases — 5 columns enables
  (`displayColumns=4`), 2 columns stays off.
- `app/src/test/.../DailyLargeTodayOverlayPolicyTest.kt`: below-threshold case updated
  (9 columns now enables; 2 columns used as the off case).

Tests: `:shared:testShortShared` and full `:app:testByDurationDebugUnitTest` pass.

## On-device verification (emulator-5554, debug install)

Widget 2 (7×4) previously showed nothing; with the change its Today column renders the overlay:

- **Open-Meteo**: `+0.0 fcst / 69.2°`; log `stationId=OPEN_METEO_MAIN dominantWeight=1.0`.
- **NWS** (cycled via header source-label taps): `-0.5 fcst / 69.8°`; log
  `stationId=KNUQ dominantWeight=0.61 rawTemp=69.8 dominantNullReason=null obsRows=87`.

Screenshots: `/tmp/wwdbg/emu_home.png`, `/tmp/wwdbg/emu_nws.png`, `/tmp/wwdbg/emu_nws2.png`.

## Notes / caveats

- The today-column overlay row shows **only the temperature** (plus reading age when enabled) —
  never the `knuq` callsign (`BlendTableFormatter.formatDominantTempAgeRows`). The station id
  appears only on the hourly graph's dominant-station label.
- Height gate still applies: 1-row text-mode widgets (Pixel 78/79) and sub-4-row widgets
  (emulator widget 7, Pixel 86) still show nothing. Pixel 86 needs one more row + graph mode.
- Forecast-only sources (Open-Meteo, Silurian) resolve a "dominant" row from their own synthetic
  blend — today-column content does not filter synthetic rows the way the hourly-graph label does
  (potential follow-up, not addressed here).
- Debug APK installed on the emulator only; **changes are uncommitted** pending user review.

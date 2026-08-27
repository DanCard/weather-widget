# Report total cloud cover, not the low layer

**Date:** 2026-08-27
**Plan:** [plans/260827-report-total-cloud-cover-not-low-layer.md](../plans/260827-report-total-cloud-cover-not-low-layer.md)
**Reverses:** the 2026-08-20 decision (`f9a05d26`) to prefer `cloudCoverLow` over `cloudCover`.

## What happened

The user reported that preferring the low layer "doesn't seem to be working as there is significant
none low cloud cover." Measured over the 720 stored Open-Meteo hours carrying both values:

| | hours | share |
|---|---|---|
| total exceeds low by >= 30 points | 125 | 17.4% |
| low < 20 but total >= 70 — a clear sky painted over a covered one | 93 | 12.9% |
| mean total − low | 15.5 points | — |
| max total − low | 100 points | — |

Live at 12:00 the low band read 1% while mid read 63%, having climbed 13 → 63 over two hours. The
graph called that a clear sky.

The original decision was measured and, on its own terms, right — total ran 83-99% on thin cirrus
while every surface station read 4-13%. What it missed is that the opposite error is larger, more
frequent, and now has a better remedy: the `m`/`h` glyph trails, which did not exist in August, say
*which layer* the total is made of. Low-preference was standing in for information the graph can now
show directly.

On station data the preference cost almost nothing — over 1,247 replayed METAR reports,
`max(low, mid, high)` exceeds low on 2.2% and badly on 0.5%. This was overwhelmingly a
forecast-side problem.

## The correction this uncovered

Commit `26d04efc`, earlier the same day, said Open-Meteo had "silently withdrawn"
`cloud_cover_low_previous_day1`. That was wrong, and the surviving rows proved it: all 765
`OPEN_METEO_PRIOR24` rows carry their value on `cloudCover`, not `cloudCoverLow`.

- last successful write: **2026-08-20 20:26**
- `f9a05d26`, which switched the request to the low variable: **2026-08-20 21:56**

The writes stopped 90 minutes *before* the commit that changed the variable. Open-Meteo has never
populated the low variant, so the frozen forecast curve never worked once after that switch — a
self-inflicted regression at a known commit, not a server-side change. Corrected in `OpenMeteoApi`,
`ForecastFetchCoordinator`, `PriorDayBandForecast` and the session memory. `26d04efc` still states
it the old way and was left unamended.

So the reversal does not merely restore the frozen curve; it removes what broke it.

## What changed

**One resolver.** `VisibleCloudCover.of(total, low, mid, high)` — the total where present, else the
maximum of whatever bands exist, else null. Null stays "not reported"; a zero total is a report and
wins over any band. All fourteen read sites go through it, including three found late: the station
blend's actual value extraction (`MetarCloudBlender.blend`), the widget's text-mode percentage, and
two stale doc comments. Twelve sites had each spelled `cloudCoverLow ?: cloudCover` by hand.

**Station rows get a real total.** NWS/METAR/Synoptic store no `cloudCover` by design, so their
total is `max(low, mid, high)` — exactly what `MetarSkyCover.totalPercent` computes from the same
cumulative layers. Without it the forecast curve would show total while the actual curve showed low,
and the accuracy claim the graph makes would be comparing two different questions.

**Write side.** `PREVIOUS_RUNS_VARIABLE` → `cloud_cover_previous_day1`; both platforms file the
prior-run value on `cloudCover`. Old rows keep their value on `cloudCoverLow` and are still found
through the band fallback, so nothing needed migrating. Desktop also gained the `PRIOR_CLOUD_EMPTY`
log Android received in `26d04efc`.

**Glyph coincidence** (requested mid-session). Once the main curve draws the total, a band within
`COINCIDENT_DELTA` of it overprints the curve. The rule is three-way:

- `SUPPRESS` when a band BELOW this one also coincides — a low deck already explains the overcast,
  so the glyph is duplicate ink (22 of 90 `h` hours).
- `NUDGE` otherwise — measured 2026-08-27, **89 of the 90** hours where `high` coincides with the
  total have low < 20. That is the thin-cirrus day, and since the low band is no longer drawn, the
  `h` trail is the only mark explaining why the curve reads 100 under a blue sky. Deleting it there
  removes the explanation, not redundant ink.
- The user's mirror case (band at 0% under a total of 0%) needed no code: `MIN_COVER = 5` already
  silences it, and all 203 such hours fall under that floor.

Forecast trails are measured against the forecast curve and observed trails against the actual
curve; measuring either against the other would suppress on a coincidence never drawn.

## Verification

All eight rows of the plan's table pass: `:shared` 2583, `:desktop` 555, `:app` 3159, zero failures.

Six existing tests encoded the old preference and were restated individually rather than
blanket-edited — each re-read, renamed where its name asserted the old rule, and given a comment
saying why the expectation moved. One of them,
`OpenMeteoLowCloudViewParityIntegrationTest`, had been written that same morning with a docstring
saying it existed "so a future total-first change fails at the visible graph boundary." It did
exactly that, and caught two read sites. Renamed to `OpenMeteoTotalCloudViewParityIntegrationTest`:
its parity claim (hourly graph and daily bar agree for one sky) was always the load-bearing half.

**Visible on device.** After the restart the desktop cloud graph's own label diagnostics show the
curve reaching `100%` on hours it previously drew at `1%`–`3%`:

```
PLACED "1%"   idx=7  ... reason=valley
PLACED "3%"   idx=15 ... reason=other
PLACED "18%"  idx=28 ... reason=peak
PLACED "100%" idx=38 ... reason=peak
PLACED "100%" idx=47 ... reason=end
```

**Outstanding:** `OPEN_METEO_PRIOR24` writes resuming — the fetch is throttled to once an hour per
process and had not fired within six minutes of the restart; a poller is watching. The Android
emulator screenshot is also outstanding.

## Risk accepted

The failure the original decision prevented returns: on a thin-cirrus day the curve reads high while
it looks clear outside. That is a deliberate, user-made trade, and unlike in August the graph now
carries `m`/`h` trails that say which layer is responsible.

# Rain-start alerts and "Rain starting in 10 minutes" nowcasting

2026-10-21. Notes from a question: could the widget notify when it has started raining, and how do
other apps say "Rain starting in 10 minutes"? No code written; this is background for a possible plan.

## 1. A "started raining" alert in this app

Feasible. The pattern already exists: `DominantTempChangeNotifier` (Android) is a notification the
user turns on, checked after each observation fetch, with its decision logic in `:shared`
(`DominantTempWatch`) so desktop can reuse it.

**What it would detect from:** observation rows already store the station's `condition` text (e.g.
"Light Rain"), `precipAmountMm` and `rawMetar`. Rain starting = a dry reading followed by a wet one
at a nearby official station.

**How fast it could notice:** the app only learns about rain when it fetches observations, and that
cadence (`:shared` `CurrentTempCadence`) depends on battery:

| Device state | Observation fetch | Worst-case delay |
|---|---|---|
| Charging | 10 min (30 with screen off) | ~10–30 min |
| Battery ≥ 70 %, screen on | ~20 min | ~20 min |
| Battery ≥ 70 %, screen off | 45 min | ~45 min |
| Battery 66–69 %, screen off; or ≤ 65 % | none | no alert |

On top of that, stations often report only once an hour. Airport stations send an extra report
when rain starts; personal stations don't.

**Two ways to build it:**

1. **Observed rain** ("It has started raining"): reliable, but always behind, by the station's delay
   plus our fetch cadence. Silent on low battery unless we add extra checks.
2. **Forecast rain** ("Rain likely starting around 2:40 pm"): use the next few hours of hourly rain
   chance from data already stored, so no extra requests. Warns before the rain, but can be wrong.

Recommended: both. A forecast heads-up when the next 1–2 hours cross the rain threshold, then a
confirmation once a station reports rain. Behind a setting, off by default, at most once per rain
event, current location and displayed source, reusing fetches the app already makes.

## 2. How "Rain starting in 10 minutes" is made: radar nowcasting

Minute-by-minute forecasting for the next hour or two, made by projecting the current radar picture
forward. A different technique from the forecasts this app uses.

1. **Take recent radar frames.** Radar scans every 2–10 min. In the US, NOAA's MRMS combines all the
   radars into one national picture every 2 minutes.
2. **Measure how the rain is moving.** Comparing the last 30–60 min of frames (optical flow) gives a
   speed and direction for each patch of rain.
3. **Project it forward.** Slide the current radar image along those motion vectors, minute by minute.
   Better systems also model rain growing or fading, and blend in a fast-updating model (HRRR) for
   the second hour.
4. **Read off the location.** Convert radar strength to a rain rate and find the first minute it
   passes a threshold (~0.1 mm/h). "Starting in 10 minutes" = that minute minus now.
5. **Push it to the phone.** The app usually isn't polling: the provider's **server** watches the
   user's location every few minutes and sends a push (Google's / Apple's push service) when the
   forecast crosses the threshold. That is how the phone can sleep and still get a timely alert.

Accuracy: good for ~30–60 min for steady rain moving through; poor for pop-up showers and
thunderstorms, which form in place, so radar has nothing to project until they appear.

"Starting in N minutes" is the most reliable thing a nowcast says (an existing band moving at a
measured speed). "Stopping in N minutes" is harder: it depends on where the band ends.

## 3. Where minute-level data comes from

| Source | What it offers | Notes |
|---|---|---|
| Apple WeatherKit (forecast for the next hour) | Minute-by-minute rain, built from radar | Apple's service; only in some countries |
| Tomorrow.io | Short time steps; the app already uses its 5-minute steps for recent history (`TomorrowIoApi`) | Whether 1-minute forecasts are on our plan needs checking |
| OpenWeatherMap One Call 3.0 | `minutely` rain for 1 hour | Separate subscription; the app uses the free 2.5 endpoints |
| Open-Meteo `minutely_15` | 15-minute rain; the app already requests this block for recent history (`OpenMeteoApi.MINUTELY_15_VARIABLES`) | Weather model, not radar: free, but coarser timing |
| Do it ourselves | Radar frames → motion → projection | Doable over a small area, but the most work |

## 4. What this means for this app

The hard part is step 5: there is no server, so nothing can push to the phone. A useful "in 10
minutes" alert needs a check every ~5 minutes, which breaks the battery rules except on a charger
or while the user is looking at the screen. The countdown is mostly a delivery problem, not a
forecasting one.

Options:

- **Cheap:** whenever the app fetches anyway, check the next hour of Open-Meteo `minutely_15` (or
  Tomorrow.io) and notify "Rain likely around 2:45 pm". Rough timing, no extra cost.
- **Faster:** while rain is predicted within ~2 h, check every 5–10 minutes until it starts or the
  risk passes. Extra battery only when it matters; this is what makes such alerts feel on time.
- **The real thing:** a small server that watches radar and pushes. A separate service to run, and
  the user's location would have to be sent to it (privacy trade-off).

Suggested starting point: the faster option on Open-Meteo `minutely_15` (free, no new API key,
already parsed), with the shared decision logic in `:shared` so desktop gets the same alert.

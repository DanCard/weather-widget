# Why desktop showed 14% and Android showed 15% for last night's rain chance

**Date:** 2026-07-06

## Answer: not a bug — the two apps froze the value at slightly different moments

Traced through both databases:

- For the **2026-07-04 night** (NWS, identical location 37.417,-122.089), Android's `daily_history`
  has `forecastNightPrecipChance = 15`, desktop has `14`.
- But the **current** hourly data in *both* databases is identical — 15% across the whole night
  window (8pm→8am). So the live computation would give 15 on both today.

The night rain chance is a **frozen snapshot**: while the day is still live, each app captures the
max hourly precip-probability over the night window (8pm–8am) and writes it into `daily_history`,
then locks it once the window closes at 8am the next day. Android and desktop have **separate
databases and fetch independently**, so when NWS revised that night's probability from 14% → 15% at
some point on the 4th, desktop had already snapshotted `14` and Android caught `15`. Once frozen,
the column never updates — which is why they still disagree even though live hourly now agrees at 15.

The window-max math itself is shared code
(`DailyRainLabels.calculateDayNightPrecipProbabilities`), so given the same input both compute the
same number. The divergence is fetch *timing*, not logic.

## Evidence

```
desktop daily_history (2026-07-04 NWS): forecastNightPrecipChance = 14
android daily_history (2026-07-04 NWS): forecastNightPrecipChance = 15

both DBs, current hourly_forecasts NWS, night window 8pm 07-04 → 8am 07-05:
  ...03:00Z 0, 04:00Z 0, 05:00Z 0, 06:00Z 0, 07:00Z 15, 08:00Z 15 ... 14:00Z 15   (max = 15)
```

Current hourly agrees at 15 on both; only the frozen history column diverges → froze at different
times straddling the NWS revision.

## Logging added (since it wasn't provable after the fact)

Nothing recorded what each app saw at freeze time — the current hourly had already moved to 15,
erasing the 14 desktop must have captured. Closed that gap on both platforms; fires on-change while
a window is open:

- **Android** — `appLogDao.log("FREEZE_RAIN_CHANCE", …)` in `ForecastRepository` (persists to
  `app_logs`).
- **Desktop** — `Log.d("DesktopWeatherRepository", "freezeRainChance: …")` in
  `DesktopWeatherRepository` (`Log.d` persists to the DB log; `Log.v` would not).

Each line records the resolved hourly-window-max **input**, the `before→after` day/night values, the
window-open flags, and a timestamp. Next time two installs disagree, pull `app_logs` from both and
compare which value each captured and when.

## How to inspect the data next time

- Desktop DB: `~/.local/share/weather-widget/weather.db`
- Android DBs: `python3 scripts/backup_databases.py`, then the emulator backup's
  `databases/weather_database` (table `daily_history`).
- `daily_history.date` is UTC-midnight epoch millis; compare `(date, source, locationLat,
  locationLon)` rows across the two DBs.

## Conclusion

**No code fix warranted.** The freeze behaving as a point-in-time snapshot is correct; occasional
±1% cross-device drift is the expected cost of independent fetching. Both builds deployed (Android
installed, desktop rebuilt/restarted); a memory note was saved so this doesn't get re-derived.

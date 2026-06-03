# Big Panel Temperature via XFCE genmon (pseudo plan — DEFERRED)

> Status: **deferred** until the desktop app has a real database to read.
> Supersedes the square/elongated tray-icon experiments in
> `260602-opus-trying-to-make-tray-font-bigger.md` (those failed — XFCE forces tray icons square).

## Context / Problem

On the Linux desktop port the current temperature is drawn as a Dorkbox **tray icon**
(`createTemperatureTrayImage` in `desktop/.../Main.kt`). In the user's **vertical** XFCE panel it
renders as a tiny rotated "61.0" in a black square — much smaller than the adjacent clock/date.

Root cause: XFCE's notification area (`libsystray.so`) forces tray icons into a fixed **square** slot
and downscales anything else (confirmed: an elongated bitmap got squished back to square). The clock
is large only because it is a panel *plugin*, not a tray icon — so no amount of icon trickery wins.

Goal: show the temperature as **big native panel text, like the clock**, keeping full precision
(e.g. `61.0°`), NWS-only.

## Approach: xfce4-genmon-plugin + self-contained script

`xfce4-genmon-plugin` (`libgenmon.so` v4.3.0) is already installed; panel is xfce4-panel 4.20.
genmon runs any command/executable on an interval and renders its stdout as panel text. `<txt>`
accepts **Pango markup**, so font size/weight/color are fully controllable. No XFCE source patching.

Decided constraints (from user):
- Implementation: a **Python script / executable** (stdlib only — urllib/json; no pip).
- Content: **temp only**, one decimal, e.g. `61.0°`.
- Source: **NWS only**.
- Click: **keep the existing Dorkbox tray icon** for click→popup; genmon is display-only (no IPC).

## BLOCKER — why deferred

User wants the script to read the **desktop database**. There is none yet: `DesktopWeatherService.kt`
fetches NWS in-memory and never persists; only `~/.config/weather-widget/config.json` (lat/lon) is on
disk. Resume once the desktop app gains a DB. Two implementation variants depending on that DB:

- **Variant A (DB exists, preferred by user):** script opens the desktop SQLite DB and reads the most
  recent NWS current temp / latest hourly row. Fast, offline, no API hits.
- **Variant B (no DB / fallback):** script reads config for lat/lon and calls NWS directly (logic
  below). Works today but hits the network.

## Pseudocode — `scripts/genmon-weather.py`

```
#!/usr/bin/env python3
CONFIG   = ~/.config/weather-widget/config.json     # {lat, lon, ...}
CACHE    = ~/.cache/weather-widget/genmon_temp.json  # {temp_f, ts}
MAX_AGE  = 900  # seconds; genmon re-runs every tick, so cache to be polite to NWS
UA       = "WeatherWidget/1.0 (contact@weatherwidget.app)"   # NWS REQUIRES User-Agent

def main():
    temp = read_fresh_cache(CACHE, MAX_AGE)          # -> float | None
    if temp is None:
        try:
            temp = fetch_temp()                      # Variant A: read DB | Variant B: NWS
            write_cache(CACHE, temp)
        except Exception:
            temp = read_any_cache(CACHE)             # stale-but-better-than-nothing
    print(render(temp))

def fetch_temp():                                    # === Variant B (no DB yet) ===
    lat, lon = load_latlon(CONFIG)                   # fallback Google HQ 37.4220,-122.0841
    pts   = GET(f"https://api.weather.gov/points/{lat},{lon}", UA).json
    st    = GET(pts.properties.observationStations, UA).json
    sid   = st.features[0].properties.stationIdentifier        # app uses first station
    try:
        obs = GET(f".../stations/{sid}/observations/latest", UA).json
        c   = obs.properties.temperature.value                  # Celsius
        return c * 1.8 + 32
    except (missing/null temperature):
        hr  = GET(pts.properties.forecastHourly, UA).json       # fallback
        p0  = hr.properties.periods[0]
        return p0.temperature if p0.temperatureUnit=="F" else p0.temperature*1.8+32

def render(temp):                                    # genmon XML on stdout
    if temp is None: body = "--"
    else:            body = f"{temp:.1f}°"
    return (f"<txt><span font='Sans Bold 18' foreground='#FFD500'>{body}</span></txt>"
            f"<tool>Weather Widget (NWS)</tool>")
```

Notes:
- `fetch_temp()` mirrors `DesktopWeatherService.fetchNwsForecast` exactly (obs °C→°F, hourly fallback).
- Keep stdlib-only so the user can `chmod +x` and point genmon straight at the file, or use
  `python3 /abs/path/genmon-weather.py` as the command.
- Tune `font='Sans Bold 18'` to taste once visible next to the clock.

## App-side work (only for Variant A, when DB lands)

- Whatever DB the desktop app adopts must store the NWS current temp (and/or latest hourly) with a
  timestamp the script can query read-only. Document the table/column + file path in this plan when
  the schema exists. No new IPC needed (display-only; tray icon keeps the click).

## Setup steps (manual, one-time)

1. Right-click panel → Panel → Add New Items → **Generic Monitor**.
2. Properties → Command = `python3 /home/dcar/projects/weather-widget/scripts/genmon-weather.py`
   (or the chmod+x path); Period = e.g. 300s; clear the label.
3. Drag it next to the clock; the tiny tray icon can stay (for clicks) or be removed.

## Verification

1. Run the script standalone — confirm it prints valid `<txt>…</txt>` and a sane temp.
2. Add genmon; confirm big yellow `61.0°` appears, comparable in size to the clock, undistorted.
3. Kill the network → confirm it shows the last cached value, not blank/error.
4. Confirm the Dorkbox tray icon still opens the popup on click.
```

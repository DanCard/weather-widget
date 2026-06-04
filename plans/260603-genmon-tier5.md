# Tier 5 — Big Panel Temperature via XFCE genmon (reads the DB)

> The original request that started this whole effort, now fully unblocked: Tiers 1–4 gave the desktop
> a populated SQLite DB (`weather.db`) with real observations + hourly data and a resilient refresh
> loop that keeps it fresh. **Supersedes** `260602-genmon-panel-temp-text.md` (which was deferred
> "until the desktop app has a DB") and its Variant B network path — Variant A (read the DB) is now
> the approach.

## Context / Goal

The XFCE tray icon is locked to a small square (see `260602-opus-trying-to-make-tray-font-bigger.md`).
`xfce4-genmon-plugin` (installed) renders a script's stdout as **native panel text**, full-size like
the clock, with Pango markup for font/color. Goal: a self-contained script that reads `weather.db`
and prints the current temperature big, NWS-only, keeping full precision (e.g. `64.4°`).

Verified the DB already has what's needed:
- `observations` — latest `KNUQ 64.4°F @ 10:15` (real measured, fresh).
- `hourly_forecasts` — `65.0°@10:00`, `68.0°@11:00` (for interpolation between hours).
- `~/.config/weather-widget/config.json` — `lat 37.4169, lon -122.0890`.

**No network needed** — the running app populates the DB; the script just reads it. This is far
simpler than the old Variant B (which hit NWS directly and needed caching).

## Decided constraints (from earlier discussion)

- **Python script / executable**, stdlib only (`sqlite3`, `json`, `datetime`) — `chmod +x` or
  `python3 /path`.
- **Temp only**, one decimal (no rounding).
- **NWS only.**
- **Click stays on the existing Dorkbox tray icon**; genmon is display-only (no app↔script IPC).

## Approach: `scripts/genmon-weather.py`

Reads the DB **read-only** (WAL allows concurrent reads while the app writes; set `busy_timeout`),
and replicates the app's current-temp logic (`DesktopWeatherService` + `TemperatureInterpolator`):

```
#!/usr/bin/env python3
DB   = $XDG_DATA_HOME/weather-widget/weather.db  (else ~/.local/share/...)
CFG  = ~/.config/weather-widget/config.json      # lat/lon to filter rows
FRESH_MS = 30*60*1000

def current_temp_f(conn, lat, lon, now_ms):
    # 1. fresh measured observation wins (matches app's isFreshObservation)
    row = conn.execute(
        "SELECT temperature, timestamp FROM observations "
        "WHERE locationLat=? AND locationLon=? ORDER BY timestamp DESC LIMIT 1", (lat, lon)).fetchone()
    if row and now_ms - row[1] <= FRESH_MS:
        return row[0], row[1]            # °F, observed-at
    # 2. else interpolate hourly_forecasts at 'now' (linear, minute fraction) — mirrors interpolator
    hrs = conn.execute(
        "SELECT dateTime, temperature FROM hourly_forecasts "
        "WHERE locationLat=? AND locationLon=? AND source='NWS' ORDER BY dateTime", (lat, lon)).fetchall()
    return interpolate(hrs, now_ms), now_ms   # bracket now between two hours; fallback nearest

def render(tempF, age_ms):
    if tempF is None: body, color = "--", "#888888"
    else:
        stale = age_ms > 2*3600*1000          # app likely not running
        color = "#888888" if stale else "#FFD500"
        body  = f"{tempF:.1f}°"
    print(f"<txt><span font='Sans Bold 18' foreground='{color}'>{body}</span></txt>")
    print(f"<tool>Weather Widget (NWS) — updated {fmt_age(age_ms)}</tool>")

# open: sqlite3.connect(f"file:{DB}?mode=ro", uri=True); PRAGMA busy_timeout=2000
```

- `interpolate` ports the ~10-line linear-between-surrounding-hours logic from
  `TemperatureInterpolator` (find the hour bucket ≤ now and the next; blend by `minute/60`).
- **Staleness:** if the newest data is old (app stopped), gray the text instead of showing a stale
  number as if live. Keeps it honest without IPC.
- Open **read-only** + `busy_timeout` so it never blocks or corrupts the app's writes.

### Optional exact-parity enhancement (note, don't require)

To guarantee the genmon number is byte-identical to the tray's, the app could persist its computed
`currentTemp` to a `meta(key,value)` row each UI update and the script read that single value —
removing any Python/Kotlin interpolation drift. Skipped by default to honor "script reads the DB and
figures out the temp" and keep the app unchanged; revisit only if drift is visible.

## Setup (manual, one-time)

1. `chmod +x scripts/genmon-weather.py`.
2. Panel → Add New Items → **Generic Monitor**.
3. Properties → Command = `python3 /home/dcar/projects/weather-widget/scripts/genmon-weather.py`;
   Period = ~120s; clear the label. Drag it beside the clock. Keep the tray icon for clicks.

## Files

- `scripts/genmon-weather.py` *(new)* — the only artifact; no app/`:shared`/`:desktop` code changes
  (unless the optional meta-row enhancement is taken).

## Verification

1. Run standalone: `python3 scripts/genmon-weather.py` → prints valid `<txt>…</txt>` and a sane temp
   matching the latest `observations` row (e.g. `64.4°`).
2. Cross-check against the tray number while the app runs — they should agree (fresh obs path).
3. Add genmon to the panel → big yellow `64.4°` beside the clock, comparable glyph size, undistorted.
4. Stop the app, wait > 2h of staleness (or temporarily lower the threshold) → text grays out rather
   than showing a stale value as live.
5. Confirm read-only access doesn't disturb the app (no "database is locked" in `app_logs`).

## Reuse / alignment

- Mirrors `DesktopWeatherService.fetchNwsForecast` current-temp precedence (fresh obs → interpolate →
  hourly) and `TemperatureInterpolator` math.
- DB is WAL with `busy_timeout` (Tier 1/2) — safe for a concurrent reader.
- Honors [[nws-observations-fractional-seconds]] is N/A here (no NWS calls); relies on the app's
  populated tables instead. See [[genmon-tray-big-text-deferred]] for the full backstory.

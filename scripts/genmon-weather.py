#!/usr/bin/env python3
"""Generic Monitor (xfce4-genmon-plugin) data source for the Weather Widget.

Prints the current temperature as big Pango-markup panel text, read entirely from the desktop app's
SQLite database (`weather.db`). No network: the running desktop app keeps the DB fresh; this script
just reads it. Configure genmon's command as:

    python3 /home/dcar/projects/weather-widget/scripts/genmon-weather.py

The desktop app's tray icon stays responsible for click/popup; this is display-only.

Current-temp precedence mirrors the app (DesktopWeatherService + DesktopTemperatureInterpolator):
  1. a fresh measured observation (<= 30 min old)
  2. else linear interpolation of the hourly forecast at "now"
  3. else the nearest hourly point
When the newest data is stale (app not running) the text is grayed rather than shown as live.
"""

import json
import os
import shlex
import sqlite3
import sys
import time

# Tunable: panel font for the temperature. Bump the number for larger glyphs on HiDPI panels.
FONT = "Sans Bold 20"
# Tunable: Pango `line_height` factor (<1.0 shrinks the reserved line box). The temperature has no
# descenders, so the font's reserved descent renders as ~18px of empty padding below the digits; in a
# short vertical panel the oversized box gets centered and clips the digit tops. Shrinking the box to
# below the panel's row height lets genmon center the glyphs without clipping. 0.6 → ~22px box, fits a
# 26px row. (Requires Pango ≥1.50; this system has 1.57.)
LINE_HEIGHT = 0.6
LIVE_COLOR = "#FFD500"   # high-contrast yellow, matches the tray icon
STALE_COLOR = "#888888"  # grayed when the app appears to have stopped updating

FRESH_OBS_MS = 30 * 60 * 1000          # prefer a measured obs newer than this (matches the app)
STALE_MS = 2 * 60 * 60 * 1000          # gray the text if newest data is older than this


def data_home() -> str:
    return os.environ.get("XDG_DATA_HOME") or os.path.join(os.path.expanduser("~"), ".local", "share")


def config_home() -> str:
    return os.environ.get("XDG_CONFIG_HOME") or os.path.join(os.path.expanduser("~"), ".config")


def db_path() -> str:
    return os.path.join(data_home(), "weather-widget", "weather.db")


def show_trigger_path() -> str:
    # The running app polls this file's mtime and opens its popup when it changes.
    return os.path.join(data_home(), "weather-widget", ".show")


def touch_show_trigger():
    p = show_trigger_path()
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "a"):
        os.utime(p, None)


def load_latlon():
    """Returns (lat, lon) from the app config, or None if unavailable."""
    cfg = os.path.join(config_home(), "weather-widget", "config.json")
    try:
        with open(cfg) as f:
            d = json.load(f)
        return float(d["lat"]), float(d["lon"])
    except Exception:
        return None


def open_db_readonly(path: str) -> sqlite3.Connection:
    # Read-only URI so we can never disturb the app's writes; WAL allows concurrent reads, and
    # busy_timeout waits out a momentary write lock instead of erroring.
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=2.0)
    conn.execute("PRAGMA busy_timeout = 2000")
    return conn


def latest_observation(conn, lat, lon):
    """(temperatureF, timestampMs) of the most recent observation, location-filtered when possible."""
    if lat is not None:
        row = conn.execute(
            "SELECT temperature, timestamp FROM observations "
            "WHERE locationLat = ? AND locationLon = ? ORDER BY timestamp DESC LIMIT 1",
            (lat, lon),
        ).fetchone()
        if row:
            return row
    # Fallback: newest observation regardless of location (e.g. location changed).
    return conn.execute(
        "SELECT temperature, timestamp FROM observations ORDER BY timestamp DESC LIMIT 1"
    ).fetchone()


def hourly_points(conn, lat, lon):
    if lat is not None:
        rows = conn.execute(
            "SELECT dateTime, temperature FROM hourly_forecasts "
            "WHERE locationLat = ? AND locationLon = ? AND source = 'NWS' ORDER BY dateTime",
            (lat, lon),
        ).fetchall()
        if rows:
            return rows
    return conn.execute(
        "SELECT dateTime, temperature FROM hourly_forecasts WHERE source = 'NWS' ORDER BY dateTime"
    ).fetchall()


def interpolate(points, now_ms):
    """Linear interpolation of the hourly curve at now_ms; nearest point at the edges."""
    if not points:
        return None
    prev = next_ = None
    for dt, temp in points:
        if dt <= now_ms:
            prev = (dt, temp)
        elif next_ is None:
            next_ = (dt, temp)
            break
    if prev and next_ and next_[0] != prev[0]:
        frac = (now_ms - prev[0]) / (next_[0] - prev[0])
        return prev[1] + (next_[1] - prev[1]) * frac
    if prev:
        return prev[1]
    if next_:
        return next_[1]
    return min(points, key=lambda p: abs(p[0] - now_ms))[1]


def current_temp(conn, lat, lon, now_ms):
    """Returns (temperatureF | None, newest_obs_ms | None, method) following the app's precedence.

    method is "measured" when a fresh observation is used, "interpolated" otherwise.
    """
    obs = latest_observation(conn, lat, lon)
    newest_ms = obs[1] if obs else None

    if obs and now_ms - obs[1] <= FRESH_OBS_MS:
        return obs[0], newest_ms, "measured"

    temp = interpolate(hourly_points(conn, lat, lon), now_ms)
    return temp, newest_ms, "interpolated"


def fmt_age(age_ms):
    if age_ms is None:
        return "unknown"
    m = age_ms // 60000
    if m < 1:
        return "just now"
    if m < 60:
        return f"{m}m ago"
    if m < 1440:
        return f"{m // 60}h ago"
    return f"{m // 1440}d ago"


def click_command() -> str:
    # genmon runs the click command in a shell when the panel item is clicked. Re-invoke this script
    # in --show mode (same interpreter) to signal the running app to open its popup.
    return f"{shlex.quote(sys.executable)} {shlex.quote(os.path.abspath(__file__))} --show"


def emit(body, color, tooltip):
    # genmon reads <txt>/<tool>/<txtclick> from stdout each tick; <txt> supports Pango markup.
    # NOTE: the click handler MUST be <txtclick> (fires on clicking the *text*), not <click>
    # (which only fires on clicking an <img>, which we don't emit). See genmon README.
    print(f"<txt><span font='{FONT}' foreground='{color}' line_height='{LINE_HEIGHT}'>{body}</span></txt>")
    print(f"<tool>{tooltip}</tool>")
    print(f"<txtclick>{click_command()}</txtclick>")


def main():
    # --show: clicked in the panel — signal the running app to open its popup, then exit.
    if "--show" in sys.argv[1:]:
        touch_show_trigger()
        return

    path = db_path()
    if not os.path.exists(path):
        emit("--", STALE_COLOR, "Weather Widget: no database yet")
        return

    now_ms = int(time.time() * 1000)
    latlon = load_latlon()
    lat, lon = latlon if latlon else (None, None)

    try:
        conn = open_db_readonly(path)
        try:
            temp, newest_ms, method = current_temp(conn, lat, lon, now_ms)
        finally:
            conn.close()
    except Exception as e:
        emit("--", STALE_COLOR, f"Weather Widget: read error ({e})")
        return

    if temp is None:
        emit("--", STALE_COLOR, "Weather Widget: no temperature data")
        return

    # Staleness reflects app activity (is the DB still being updated?), gauged by the newest
    # observation. The method line makes clear whether the number is measured or interpolated, so the
    # "obs N ago" age can't be misread as the displayed value's timestamp.
    age_ms = (now_ms - newest_ms) if newest_ms is not None else None
    stale = age_ms is not None and age_ms > STALE_MS
    color = STALE_COLOR if stale else LIVE_COLOR
    detail = "measured" if method == "measured" else "interpolated, latest obs"
    tooltip = f"Weather Widget (NWS) — {detail} {fmt_age(age_ms)}"
    if stale:
        tooltip += " — stale (app not updating?)"
    emit(f"{temp:.1f}°", color, tooltip)


if __name__ == "__main__":
    sys.exit(main())

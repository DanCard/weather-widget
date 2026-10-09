#!/usr/bin/env python3
"""Google Weather requests for one Pacific day, across desktop and every connected adb device.

Google's quotas (and the Cloud Console's "today") run per Pacific calendar day, whatever zone the
device is in, so the window here is America/Los_Angeles midnight to midnight.

Two counts per platform:
  logged   `GOOGLE_REQUEST` rows in app_logs inside the PT window (one per response; a send that
           threw, or a transport retry underneath, leaves no row).
  counted  `api_usage_stats` GOOGLE_WEATHER rows filed under that day (counts every send, retries
           and failures included). Google rows are filed by Pacific day
           (`ApiUsageClassifier.usageDayMs`); builds before 2026-10-08 filed by the device's day.

Estimated forecast/hours: per platform, the largest of three lower bounds — the summed
`GOOGLE_HOURS_PAGES pages=N` lines (one per Google fetch, every build since 2026-10-07; the paging
loop is the only caller of forecast/hours; a fetch that throws logs none), the forecast/hours
`GOOGLE_REQUEST` rows (logged), and the forecast/hours api_usage_stats row (counted; builds from 2026-10-08).

Estimated forecast/days: the same, with the fetch count (number of `GOOGLE_HOURS_PAGES` lines) as
the first bound. Every fetch that returns makes exactly one forecast/days call, hourly-limited
ones included, except when the daily quota was already refused (it is then skipped, which the
line does not show; this undercounts a fetch that threw).

Usage: scripts/google_usage_today.py [--date YYYY-MM-DD] [--no-devices] [--no-desktop]
"""
import argparse
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from collections import defaultdict
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

PACKAGE = "com.weatherwidget"
PACIFIC = ZoneInfo("America/Los_Angeles")
DESKTOP_DB = os.path.expanduser("~/.local/share/weather-widget/weather.db")
DB_FILES = ("weather_database", "weather_database-wal", "weather_database-shm")


def adb_bin():
    return os.environ.get("ADB_BIN") or shutil.which("adb") or "/home/dcar/.Android/Sdk/platform-tools/adb"


def devices():
    out = subprocess.run([adb_bin(), "devices"], capture_output=True, text=True, timeout=30).stdout
    return [line.split()[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]


def pull_device_db(serial, dest_dir):
    """Copies the Room DB and its WAL so uncheckpointed writes are included. Returns the path or None."""
    for name in DB_FILES:
        with open(os.path.join(dest_dir, name), "wb") as f:
            subprocess.run(
                [adb_bin(), "-s", serial, "exec-out", "run-as", PACKAGE, "cat", f"databases/{name}"],
                stdout=f, stderr=subprocess.DEVNULL, timeout=60,
            )
    path = os.path.join(dest_dir, DB_FILES[0])
    return path if os.path.getsize(path) > 0 else None


def device_label(serial):
    model = subprocess.run(
        [adb_bin(), "-s", serial, "shell", "getprop", "ro.product.model"],
        capture_output=True, text=True, timeout=15,
    ).stdout.strip()
    return f"{model or serial} ({serial})"


def window_ms(day):
    start = datetime.combine(day, time.min, PACIFIC)
    end = datetime.combine(day + timedelta(days=1), time.min, PACIFIC)
    return int(start.timestamp() * 1000), int(end.timestamp() * 1000)


def query(db_path, day):
    """({endpoint: [log_ok, log_err, table_calls, table_errs, table_429]}, hours pages, fetches) for one database."""
    rows = defaultdict(lambda: [0, 0, 0, 0, 0])
    pages = 0
    fetches = 0
    start, end = window_ms(day)
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        for msg, in conn.execute(
            "SELECT message FROM app_logs WHERE tag = 'GOOGLE_REQUEST' AND timestamp >= ? AND timestamp < ?",
            (start, end),
        ):
            fields = dict(p.split("=", 1) for p in msg.split() if "=" in p)
            status = int(fields.get("status", "0") or 0)
            rows[fields.get("endpoint", "?")][0 if status < 400 else 1] += 1
        for msg, in conn.execute(
            "SELECT message FROM app_logs WHERE tag = 'GOOGLE_HOURS_PAGES' AND timestamp >= ? AND timestamp < ?",
            (start, end),
        ):
            fields = dict(p.split("=", 1) for p in msg.split() if "=" in p)
            pages += int(fields.get("pages", "0") or 0)
            # An on-demand day fetch is forecast/hours alone; only full fetches call forecast/days.
            if fields.get("trigger") != "on_demand_day":
                fetches += 1
        # The day key is that day's UTC-midnight epoch ms (LocalDate.toEpochDay() * 86_400_000).
        key = (day - date(1970, 1, 1)).days * 86_400_000
        try:
            for endpoint, calls, errs, refused in conn.execute(
                "SELECT endpoint, callCount, errorCount, quotaRefusedCount FROM api_usage_stats "
                "WHERE apiSource = 'GOOGLE_WEATHER' AND date = ?",
                (key,),
            ):
                r = rows[endpoint or "(no endpoint)"]
                r[2] += calls
                r[3] += errs
                r[4] += refused
        except sqlite3.OperationalError:
            pass  # older desktop schema without api_usage_stats
    finally:
        conn.close()
    return rows, pages, fetches


def estimate(rows, endpoint, fetch_bound):
    """(estimate, fetch-log bound, request-log count, table count) for one endpoint."""
    r = rows.get(endpoint, [0, 0, 0, 0, 0])
    log, table = r[0] + r[1], r[2]
    return max(fetch_bound, log, table), fetch_bound, log, table


def estimates_for(rows, pages, fetches):
    return {"forecast/hours": estimate(rows, "forecast/hours", pages),
            "forecast/days": estimate(rows, "forecast/days", fetches)}


FOOTER = """
Columns
  logged ok     GOOGLE_REQUEST lines in app_logs inside the Pacific day, status < 400.
  logged err    The same lines with status >= 400. One line per response: a send that got no
                response, or a transport retry underneath, leaves none.
  counted       api_usage_stats.callCount for the day: every request sent, retries and failures
                included. The closest to what Google counts.
  counted err   Of those, HTTP >= 400 or a send that failed (api_usage_stats.errorCount).
  counted 429   Of those, quota refusals (api_usage_stats.quotaRefusedCount).
  Logged and counted differ when counting started later than logging (builds before
  2026-10-08), or when a request was retried or failed without a response.

Estimates
  estimate      The largest of the bracketed lower bounds: the GOOGLE_HOURS_PAGES lines (pages
                summed for forecast/hours, fetches counted for forecast/days), logged, counted.

Devices that are offline or not attached (e.g. a stopped emulator) are not included."""


def print_platform(label, rows, totals):
    print(f"\n== {label}")
    if not rows:
        print("   (no Google requests)")
        return
    print(f"   {'endpoint':<22}{'logged ok':>11}{'logged err':>12}{'counted':>9}{'counted err':>13}{'counted 429':>13}")
    for endpoint in sorted(rows):
        r = rows[endpoint]
        print(f"   {endpoint:<22}{r[0]:>11}{r[1]:>12}{r[2]:>9}{r[3]:>13}{r[4]:>13}")
        for i, v in enumerate(r):
            totals[endpoint][i] += v


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--date", help="Pacific date YYYY-MM-DD (default: today in PT)")
    parser.add_argument("--no-devices", action="store_true")
    parser.add_argument("--no-desktop", action="store_true")
    args = parser.parse_args()

    day = date.fromisoformat(args.date) if args.date else datetime.now(PACIFIC).date()
    start, end = window_ms(day)
    print(f"Google requests for Pacific day {day} "
          f"({datetime.fromtimestamp(start / 1000, timezone.utc):%Y-%m-%d %H:%M}Z – "
          f"{datetime.fromtimestamp(end / 1000, timezone.utc):%Y-%m-%d %H:%M}Z)")

    totals = defaultdict(lambda: [0, 0, 0, 0, 0])
    estimates = []
    if not args.no_desktop:
        if os.path.exists(DESKTOP_DB):
            rows, pages, fetches = query(DESKTOP_DB, day)
            print_platform("desktop", rows, totals)
            estimates.append(("desktop", estimates_for(rows, pages, fetches)))
        else:
            print(f"\n== desktop: {DESKTOP_DB} not found")
    if not args.no_devices:
        for serial in devices():
            with tempfile.TemporaryDirectory() as tmp:
                db = pull_device_db(serial, tmp)
                if db is None:
                    print(f"\n== {serial}: could not read database (not a debug build?)")
                    continue
                label = device_label(serial)
                rows, pages, fetches = query(db, day)
                print_platform(label, rows, totals)
                estimates.append((label, estimates_for(rows, pages, fetches)))

    print_platform("TOTAL", dict(totals), defaultdict(lambda: [0, 0, 0, 0, 0]))
    for endpoint, bound in (("forecast/hours", "pages log"), ("forecast/days", "fetches log")):
        print(f"\nEstimated {endpoint} requests, Pacific day {day}:")
        print(f"   {'platform':<38}{'estimate':>9}   ({bound} / logged / counted)")
        for label, by_endpoint in estimates:
            est, fetch_bound, log, table = by_endpoint[endpoint]
            print(f"   {label:<38}{est:>9}   ({fetch_bound} / {log} / {table})")
        print(f"   {'TOTAL':<38}{sum(e[endpoint][0] for _, e in estimates):>9}")

    print(FOOTER)


if __name__ == "__main__":
    sys.exit(main())

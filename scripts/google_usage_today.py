#!/usr/bin/env python3
"""Google Weather requests for one Pacific day, across desktop and every connected adb device.

Google's quotas (and the Cloud Console's "today") run per Pacific calendar day, whatever zone the
device is in, so the window here is America/Los_Angeles midnight to midnight.

Two counts per platform:
  log    `GOOGLE_REQUEST` rows in app_logs inside the PT window (one per response; a send that
         threw, or a transport retry underneath, leaves no row).
  table  `api_usage_stats` GOOGLE_WEATHER rows filed under that day (counts every send, retries
         and failures included). Google rows are filed by Pacific day
         (`ApiUsageClassifier.usageDayMs`); builds before 2026-10-08 filed by the device's day.

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
    """{endpoint: [log_ok, log_err, table_calls, table_errs, table_429]} for one database."""
    rows = defaultdict(lambda: [0, 0, 0, 0, 0])
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
    return rows


def print_platform(label, rows, totals):
    print(f"\n== {label}")
    if not rows:
        print("   (no Google requests)")
        return
    print(f"   {'endpoint':<22}{'log ok':>8}{'log err':>9}{'table':>8}{'t.err':>7}{'t.429':>7}")
    for endpoint in sorted(rows):
        r = rows[endpoint]
        print(f"   {endpoint:<22}{r[0]:>8}{r[1]:>9}{r[2]:>8}{r[3]:>7}{r[4]:>7}")
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
    if not args.no_desktop:
        if os.path.exists(DESKTOP_DB):
            print_platform("desktop", query(DESKTOP_DB, day), totals)
        else:
            print(f"\n== desktop: {DESKTOP_DB} not found")
    if not args.no_devices:
        for serial in devices():
            with tempfile.TemporaryDirectory() as tmp:
                db = pull_device_db(serial, tmp)
                if db is None:
                    print(f"\n== {serial}: could not read database (not a debug build?)")
                    continue
                print_platform(device_label(serial), query(db, day), totals)

    print_platform("TOTAL", dict(totals), defaultdict(lambda: [0, 0, 0, 0, 0]))
    print("\nlog = GOOGLE_REQUEST rows in the PT window (responses only).  "
          "table = api_usage_stats (every send, incl. retries/failures).")
    print("Devices that are offline or not attached (e.g. a stopped emulator) are not counted.")


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Copy the daily_history days one device is missing from another, for one site.

Each source keeps its own `daily_history` rows, so a phone that started a source later (or was off)
has a shorter record than another phone at the same site. This copies the missing (source, date)
rows — whole rows: blended and station actuals, frozen forecasts, rain chances — from SOURCE to
TARGET. Both apps must be debug builds (`run-as`) on the same schema version.

Only the target's site is copied (rows within LocationMatch.SAME_SITE_TOLERANCE_DEG of it): that is
what its daily and months views read. Dates before today only; a (source, date) the target already
has at the site is never touched. One row per (source, date): the source's fragment nearest the site.

Dry run by default. With --apply the target app is force-stopped, its live database pulled,
merged locally, integrity-checked, pushed back (WAL files removed), verified by checksum, and the
app started again (a force-stopped app stays stopped until launched).

Usage:
  python3 scripts/copy_daily_history.py --from 2A191FDH300PPW --to RFCT70G352Y            # dry run
  python3 scripts/copy_daily_history.py --from 2A191FDH300PPW --to RFCT70G352Y --apply
  ... --source NWS --source SILURIAN   # limit sources (default: all)
  ... --lat 37.416817 --lon -122.08901 # site (default: the target's active_weather_location prefs)
"""
import argparse
import datetime
import hashlib
import os
import re
import sqlite3
import subprocess
import sys
import tempfile

PACKAGE = "com.weatherwidget"
DB = "weather_database"
SAME_SITE_DEG = 0.002  # LocationMatch.SAME_SITE_TOLERANCE_DEG
DAY_MS = 86_400_000


def adb(serial, *args, stdin=None, check=True):
    return subprocess.run(["adb", "-s", serial, *args], input=stdin, capture_output=True, check=check)


def pull_db(serial, out_dir):
    """Pulls the database and its WAL files via run-as, then checkpoints the copy into one file."""
    os.makedirs(out_dir, exist_ok=True)
    for suffix in ("", "-wal", "-shm"):
        res = adb(serial, "exec-out", "run-as", PACKAGE, "cat", f"databases/{DB}{suffix}", check=False)
        path = os.path.join(out_dir, DB + suffix)
        if res.returncode == 0 and res.stdout and not res.stdout.startswith(b"cat:"):
            with open(path, "wb") as f:
                f.write(res.stdout)
        elif suffix == "":
            sys.exit(f"[{serial}] could not read {DB}: {res.stderr.decode().strip()}")
    path = os.path.join(out_dir, DB)
    con = sqlite3.connect(path)
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    return path


def target_site(serial):
    res = adb(serial, "exec-out", "run-as", PACKAGE, "cat", "shared_prefs/active_weather_location.xml", check=False)
    text = res.stdout.decode(errors="replace")
    lat = re.search(r'<float name="latitude" value="([-0-9.]+)"', text)
    lon = re.search(r'<float name="longitude" value="([-0-9.]+)"', text)
    if not (lat and lon):
        sys.exit(f"[{serial}] no latitude/longitude in active_weather_location.xml; pass --lat/--lon")
    return float(lat.group(1)), float(lon.group(1))


def site_rows(con, lat, lon, before_ms, sources):
    sql = (
        "SELECT * FROM daily_history WHERE ABS(locationLat - ?) <= ? AND ABS(locationLon - ?) <= ? "
        "AND date < ?"
    )
    params = [lat, SAME_SITE_DEG, lon, SAME_SITE_DEG, before_ms]
    if sources:
        sql += " AND source IN (%s)" % ",".join("?" * len(sources))
        params += sources
    cur = con.execute(sql, params)
    cols = [d[0] for d in cur.description]
    return cols, [dict(zip(cols, r)) for r in cur.fetchall()]


def integrity(path):
    con = sqlite3.connect(path)
    result = con.execute("PRAGMA integrity_check").fetchone()[0]
    con.close()
    return result


def md5(path):
    with open(path, "rb") as f:
        return hashlib.md5(f.read()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--from", dest="src", required=True, help="serial to copy from")
    ap.add_argument("--to", dest="dst", required=True, help="serial to copy into")
    ap.add_argument("--source", action="append", default=[], help="weather source id(s); default all")
    ap.add_argument("--lat", type=float)
    ap.add_argument("--lon", type=float)
    ap.add_argument("--apply", action="store_true", help="write the target (default: dry run)")
    args = ap.parse_args()

    work = tempfile.mkdtemp(prefix="copy_daily_history_")
    lat, lon = (args.lat, args.lon) if args.lat is not None and args.lon is not None else target_site(args.dst)
    today = datetime.date.today()
    before_ms = (today - datetime.date(1970, 1, 1)).days * DAY_MS
    print(f"[*] site {lat}, {lon} (±{SAME_SITE_DEG}°), days before {today}, work dir {work}")

    if args.apply:
        print(f"[{args.dst}] force-stopping {PACKAGE} so nothing writes mid-copy")
        adb(args.dst, "shell", "am", "force-stop", PACKAGE)

    src_path = pull_db(args.src, os.path.join(work, "from"))
    dst_path = pull_db(args.dst, os.path.join(work, "to"))
    src = sqlite3.connect(src_path)
    dst = sqlite3.connect(dst_path)

    versions = (src.execute("PRAGMA user_version").fetchone()[0], dst.execute("PRAGMA user_version").fetchone()[0])
    if versions[0] != versions[1]:
        sys.exit(f"schema versions differ (from {versions[0]}, to {versions[1]}); update one app first")
    src_cols, src_rows = site_rows(src, lat, lon, before_ms, args.source)
    dst_cols, dst_rows = site_rows(dst, lat, lon, before_ms, args.source)
    if src_cols != dst_cols:
        sys.exit("daily_history columns differ between the two databases")

    have = {(r["source"], r["date"]) for r in dst_rows}
    best = {}
    for r in src_rows:
        key = (r["source"], r["date"])
        if key in have:
            continue
        dist = (r["locationLat"] - lat) ** 2 + (r["locationLon"] - lon) ** 2
        if key not in best or dist < best[key][0]:
            best[key] = (dist, r)
    to_copy = [r for _, r in best.values()]

    by_source = {}
    for r in to_copy:
        by_source.setdefault(r["source"], []).append(r["date"])
    print(f"[*] schema v{versions[0]}; {len(to_copy)} rows missing on {args.dst}:")
    for source, dates in sorted(by_source.items()):
        first = datetime.date(1970, 1, 1) + datetime.timedelta(days=min(dates) // DAY_MS)
        last = datetime.date(1970, 1, 1) + datetime.timedelta(days=max(dates) // DAY_MS)
        print(f"    {source:18} {len(dates):4} days  {first} .. {last}")

    if not args.apply:
        print("[*] dry run — nothing written. Re-run with --apply.")
        return
    if not to_copy:
        print("[*] nothing to copy")
        adb(args.dst, "shell", "am", "start", "-n", f"{PACKAGE}/.ui.MainActivity")
        return

    placeholders = ",".join("?" * len(dst_cols))
    with dst:
        dst.executemany(
            f"INSERT OR IGNORE INTO daily_history ({','.join(dst_cols)}) VALUES ({placeholders})",
            [tuple(r[c] for c in dst_cols) for r in to_copy],
        )
    dst.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    dst.close()
    src.close()
    check = integrity(dst_path)
    if check != "ok":
        sys.exit(f"integrity_check failed on the merged copy: {check}; target untouched (app stopped)")

    print(f"[{args.dst}] pushing merged database")
    with open(dst_path, "rb") as f:
        data = f.read()
    adb(args.dst, "exec-in", "run-as", PACKAGE, "sh", "-c", f"cat > databases/{DB}.copytmp", stdin=data)
    adb(args.dst, "shell", "run-as", PACKAGE, "sh", "-c",
        f"'mv databases/{DB}.copytmp databases/{DB} && rm -f databases/{DB}-wal databases/{DB}-shm'")

    back = adb(args.dst, "exec-out", "run-as", PACKAGE, "cat", f"databases/{DB}").stdout
    if hashlib.md5(back).hexdigest() != md5(dst_path):
        sys.exit("pushed database does not match the merged copy — restore from backups/ before starting the app")
    print(f"[{args.dst}] verified ({len(back)} bytes); starting the app")
    adb(args.dst, "shell", "am", "start", "-n", f"{PACKAGE}/.ui.MainActivity")
    print(f"[*] copied {len(to_copy)} rows")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Delete same-day NWS forecast snapshots captured after 6 PM on all attached devices.

The script logs each row deleted to a timestamped file.
"""

import argparse
import csv
import datetime as dt
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import List, Tuple

PACKAGE_NAME = "com.weatherwidget"
DB_RELATIVE_PATH = f"/data/data/{PACKAGE_NAME}/databases/weather_database"


def run(cmd: List[str], check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(cmd, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {' '.join(cmd)}\n"
            f"stdout: {result.stdout}\nstderr: {result.stderr}"
        )
    return result


def adb(serial: str, args: List[str], check: bool = True) -> subprocess.CompletedProcess:
    return run(["/home/dcar/.Android/Sdk/platform-tools/adb", "-s", serial] + args, check=check)


def list_attached_devices() -> List[str]:
    result = run(["/home/dcar/.Android/Sdk/platform-tools/adb", "devices"])
    serials: List[str] = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def app_installed(serial: str) -> bool:
    result = adb(serial, ["shell", "pm", "list", "packages", PACKAGE_NAME], check=False)
    return result.returncode == 0 and PACKAGE_NAME in result.stdout


def pull_db(serial: str, out_path: Path) -> None:
    # Force-stop to reduce DB churn while we copy and replace.
    adb(serial, ["shell", "am", "force-stop", PACKAGE_NAME], check=False)
    with out_path.open("wb") as f:
        proc = subprocess.run(
            [
                "/home/dcar/.Android/Sdk/platform-tools/adb",
                "-s",
                serial,
                "exec-out",
                "run-as",
                PACKAGE_NAME,
                "cat",
                DB_RELATIVE_PATH,
            ],
            stdout=f,
            stderr=subprocess.PIPE,
        )
    if proc.returncode != 0 or out_path.stat().st_size == 0:
        raise RuntimeError(
            f"Failed to pull DB from {serial}: {proc.stderr.decode(errors='replace')}"
        )


def push_db(serial: str, db_path: Path, remote_tmp: str) -> None:
    adb(serial, ["push", str(db_path), remote_tmp])
    adb(serial, ["shell", "run-as", PACKAGE_NAME, "cp", remote_tmp, DB_RELATIVE_PATH])
    adb(serial, ["shell", "run-as", PACKAGE_NAME, "rm", "-f", DB_RELATIVE_PATH + "-wal"], check=False)
    adb(serial, ["shell", "run-as", PACKAGE_NAME, "rm", "-f", DB_RELATIVE_PATH + "-shm"], check=False)
    adb(serial, ["shell", "rm", "-f", remote_tmp], check=False)


def fetch_rows_to_delete(conn: sqlite3.Connection, cutoff: str) -> List[Tuple]:
    sql = """
    SELECT
        rowid,
        targetDate,
        forecastDate,
        locationLat,
        locationLon,
        highTemp,
        lowTemp,
        condition,
        source,
        fetchedAt,
        datetime(fetchedAt/1000, 'unixepoch', 'localtime') AS fetchedLocal
    FROM forecast_snapshots
    WHERE source = 'NWS'
      AND targetDate = forecastDate
      AND time(fetchedAt/1000, 'unixepoch', 'localtime') >= ?
    ORDER BY fetchedAt ASC, targetDate ASC, forecastDate ASC
    """
    return conn.execute(sql, (cutoff,)).fetchall()


def delete_rows(conn: sqlite3.Connection, cutoff: str) -> int:
    sql = """
    DELETE FROM forecast_snapshots
    WHERE source = 'NWS'
      AND targetDate = forecastDate
      AND time(fetchedAt/1000, 'unixepoch', 'localtime') >= ?
    """
    cur = conn.execute(sql, (cutoff,))
    conn.commit()
    return cur.rowcount


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cutoff", default="18:00:00", help="Local-time cutoff HH:MM:SS (default: 18:00:00)")
    parser.add_argument("--apply", action="store_true", help="Apply deletion and push DB back to devices")
    parser.add_argument("--log-file", default="", help="Optional explicit log file path")
    args = parser.parse_args()

    now = dt.datetime.now()
    logs_dir = Path("logs")
    logs_dir.mkdir(parents=True, exist_ok=True)
    log_path = Path(args.log_file) if args.log_file else logs_dir / f"device_delete_nws_same_day_after_6pm_{now.strftime('%Y%m%d_%H%M%S')}.log"

    devices = list_attached_devices()
    if not devices:
        print("No attached devices found.")
        return 1

    total_candidates = 0
    total_deleted = 0

    with log_path.open("w", encoding="utf-8", newline="") as log_file:
        log_file.write(f"run_started={now.isoformat()}\n")
        log_file.write(f"mode={'apply' if args.apply else 'dry-run'}\n")
        log_file.write(f"cutoff={args.cutoff}\n")
        log_file.write(f"devices={','.join(devices)}\n\n")

        writer = csv.writer(log_file)
        writer.writerow([
            "device",
            "rowid",
            "targetDate",
            "forecastDate",
            "locationLat",
            "locationLon",
            "highTemp",
            "lowTemp",
            "condition",
            "source",
            "fetchedAt",
            "fetchedLocal",
        ])

        for serial in devices:
            print(f"[{serial}] Processing...")
            if not app_installed(serial):
                print(f"[{serial}] Skipped: {PACKAGE_NAME} not installed")
                log_file.write(f"\n[{serial}] status=skipped reason=app_not_installed\n")
                continue

            with tempfile.TemporaryDirectory(prefix=f"cleanup_{serial}_") as tmp_dir:
                tmp_db = Path(tmp_dir) / "weather_database"
                try:
                    pull_db(serial, tmp_db)
                except Exception as exc:
                    print(f"[{serial}] Failed to pull DB: {exc}")
                    log_file.write(f"\n[{serial}] status=error stage=pull error={exc}\n")
                    continue

                # Keep a local safety backup copy before mutation.
                backup_copy = logs_dir / f"{now.strftime('%Y%m%d_%H%M%S')}_{serial}_weather_database.bak"
                shutil.copy2(tmp_db, backup_copy)

                try:
                    conn = sqlite3.connect(str(tmp_db))
                    rows = fetch_rows_to_delete(conn, args.cutoff)
                    total_candidates += len(rows)

                    for row in rows:
                        writer.writerow([serial, *row])

                    print(f"[{serial}] Candidate rows: {len(rows)}")
                    log_file.write(f"\n[{serial}] backup_copy={backup_copy}\n")
                    log_file.write(f"[{serial}] candidate_rows={len(rows)}\n")

                    if args.apply and rows:
                        deleted = delete_rows(conn, args.cutoff)
                        remaining = len(fetch_rows_to_delete(conn, args.cutoff))
                        if remaining != 0:
                            raise RuntimeError(f"Post-delete verification failed: remaining={remaining}")

                        remote_tmp = f"/data/local/tmp/weather_database.cleaned.{serial}"
                        push_db(serial, tmp_db, remote_tmp)
                        total_deleted += deleted
                        print(f"[{serial}] Deleted rows: {deleted}")
                        log_file.write(f"[{serial}] deleted_rows={deleted}\n")
                    elif args.apply:
                        log_file.write(f"[{serial}] deleted_rows=0\n")

                except Exception as exc:
                    print(f"[{serial}] Error: {exc}")
                    log_file.write(f"[{serial}] status=error stage=mutate_or_push error={exc}\n")
                finally:
                    conn.close()

        log_file.write("\n")
        log_file.write(f"total_candidate_rows={total_candidates}\n")
        log_file.write(f"total_deleted_rows={total_deleted if args.apply else 0}\n")

    print(f"Log written: {log_path}")
    if args.apply:
        print(f"Total deleted rows: {total_deleted}")
    else:
        print(f"Dry-run candidate rows: {total_candidates}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

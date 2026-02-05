#!/usr/bin/env python3

import sqlite3
import os
import sys
import datetime
import glob


def analyze_database(db_path, folder_name):
    try:
        conn = sqlite3.connect(db_path)
        cursor = conn.cursor()

        # Parse folder name (YYYYMMDD_HHMMSS_model_serial)
        parts = folder_name.split("_")
        if len(parts) >= 3:
            timestamp = f"{parts[0]}_{parts[1]}"
            device_id = "_".join(parts[2:])
            display_name = f"{device_id} ({timestamp})"
        else:
            display_name = folder_name

        print("\n" + "=" * 85)
        print(f"Device: {display_name}")
        print(f"Path: {db_path}")
        print("=" * 85)

        # Use the current date instead of hardcoded 2026-02-04
        today = datetime.date.today()

        # NWS has separate fetch types; Meteo (All) includes both forecast and history in one call.
        print(
            f"{'Date':<12} | {'NWS Fcst':<10} | {'NWS Obs':<10} | {'Meteo (All)':<12} | {'Gap Data':<8} | {'Hourly':<8}"
        )
        print("-" * 85)

        for i in range(7, -1, -1):
            date = today - datetime.timedelta(days=i)
            start_ts = int(
                datetime.datetime.combine(date, datetime.time.min).timestamp() * 1000
            )
            end_ts = int(
                datetime.datetime.combine(date, datetime.time.max).timestamp() * 1000
            )

            # NWS Forecast sessions
            cursor.execute(
                """
                SELECT COUNT(DISTINCT fetchedAt) FROM forecast_snapshots 
                WHERE source = 'NWS' AND fetchedAt >= ? AND fetchedAt <= ?
            """,
                (start_ts, end_ts),
            )
            nws_fcst = cursor.fetchone()[0]

            # NWS Observation sessions (one session triggers 8 API calls)
            cursor.execute(
                """
                SELECT COUNT(DISTINCT fetchedAt) FROM weather_data 
                WHERE source = 'NWS' AND stationId IS NOT NULL AND fetchedAt >= ? AND fetchedAt <= ?
            """,
                (start_ts, end_ts),
            )
            nws_obs = cursor.fetchone()[0]

            # Open-Meteo sessions (Combined: one API call for forecast + 7 days history)
            cursor.execute(
                """
                SELECT COUNT(DISTINCT fetchedAt) FROM forecast_snapshots 
                WHERE source = 'OPEN_METEO' AND fetchedAt >= ? AND fetchedAt <= ?
            """,
                (start_ts, end_ts),
            )
            meteo_count = cursor.fetchone()[0]

            # Gap Data fetches
            cursor.execute(
                """
                SELECT COUNT(DISTINCT fetchedAt) FROM weather_data 
                WHERE isClimateNormal = 1 AND fetchedAt >= ? AND fetchedAt <= ?
            """,
                (start_ts, end_ts),
            )
            gap_count = cursor.fetchone()[0]

            # Hourly fetches
            cursor.execute(
                """
                SELECT COUNT(DISTINCT fetchedAt) FROM hourly_forecasts 
                WHERE fetchedAt >= ? AND fetchedAt <= ?
            """,
                (start_ts, end_ts),
            )
            hourly_count = cursor.fetchone()[0]

            date_str = date.strftime("%Y-%m-%d")
            print(
                f"{date_str:<12} | {nws_fcst:<10} | {nws_obs:<10} | {meteo_count:<12} | {gap_count:<8} | {hourly_count:<8}"
            )

        conn.close()
    except Exception as e:
        print(f"Error analyzing {device_name}: {e}")


def should_run_backup(backup_dir):
    """Check if last backup is more than 1 hour old or doesn't exist."""
    if not os.path.exists(backup_dir):
        return True

    # Get all backup folders (only those starting with a year '202')
    backup_folders = [
        f for f in os.listdir(backup_dir) 
        if os.path.isdir(os.path.join(backup_dir, f)) and f.startswith("202")
    ]

    if not backup_folders:
        return True

    # Find the most recent backup folder
    backup_folders.sort(reverse=True)
    latest_backup = backup_folders[0]
    backup_path = os.path.join(backup_dir, latest_backup)

    # Get folder modification time
    backup_time = os.path.getmtime(backup_path)
    current_time = datetime.datetime.now().timestamp()
    hours_diff = (current_time - backup_time) / 3600

    if hours_diff > 1:
        print(f"Last backup was {hours_diff:.1f} hours ago. Running backup...")
        return True

    print(
        f"Last backup was {hours_diff:.1f} hours ago (recent enough). Skipping backup."
    )
    return False


def run_backup():
    """Run the backup script."""
    import subprocess

    backup_script = "/home/dcar/projects/weather-widget/scripts/backup_databases.py"
    if os.path.exists(backup_script):
        try:
            result = subprocess.run(
                [sys.executable, backup_script], capture_output=True, text=True
            )
            print(result.stdout)
            if result.stderr:
                print("Backup warnings/errors:", result.stderr, file=sys.stderr)
            return result.returncode == 0
        except Exception as e:
            print(f"Error running backup: {e}", file=sys.stderr)
            return False
    else:
        print(f"Backup script not found: {backup_script}", file=sys.stderr)
        return False


def main():
    backup_dir = "/home/dcar/projects/weather-widget/backups"

    # Check if we need to run backup
    if should_run_backup(backup_dir):
        print()
        if not run_backup():
            print(
                "Warning: Backup failed or skipped. Continuing with existing data...",
                file=sys.stderr,
            )
        print()

    db_pattern = os.path.join(backup_dir, "*/databases/weather_database")

    databases = glob.glob(db_pattern)

    for db_path in sorted(databases):
        parts = db_path.split("/")
        if len(parts) >= 3:
            folder_name = parts[-3]
            analyze_database(db_path, folder_name)


if __name__ == "__main__":
    main()

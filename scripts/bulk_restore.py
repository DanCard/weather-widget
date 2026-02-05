#!/usr/bin/env python3
import subprocess
import os
import sqlite3
import shutil

PACKAGE_NAME = "com.weatherwidget"
DB_NAME = "weather_database"
SERIAL = "RFCT71FR9NT"
TMP_DIR = "tmp_restore"
CURRENT_DB = os.path.join(TMP_DIR, "current_weather.db")
HEALTHY_DB = os.path.join(TMP_DIR, "healthy_weather.db")

def run_adb(args):
    cmd = ["adb", "-s", SERIAL] + args
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.stdout.strip(), result.returncode

def pull_db():
    print(f"[*] Pulling database from {SERIAL}...")
    if os.path.exists(TMP_DIR):
        shutil.rmtree(TMP_DIR)
    os.makedirs(TMP_DIR, exist_ok=True)
    for ext in ["", "-wal", "-shm"]:
        local = f"{CURRENT_DB}{ext}"
        with open(local, "wb") as f:
            subprocess.run(["adb", "-s", SERIAL, "exec-out", "run-as", PACKAGE_NAME, "cat", f"databases/{DB_NAME}{ext}"], stdout=f)
    print("    [+] Done.")

def push_db():
    print(f"[*] Stopping {PACKAGE_NAME}...")
    run_adb(["shell", f"am force-stop {PACKAGE_NAME}"])
    
    print(f"[*] Removing old journal files from {SERIAL}...")
    run_adb(["shell", f"run-as {PACKAGE_NAME} rm databases/{DB_NAME}-wal databases/{DB_NAME}-shm"])

    print(f"[*] Pushing healthy database back to {SERIAL}...")
    tmp_remote = f"/data/local/tmp/{DB_NAME}"
    run_adb(["push", HEALTHY_DB, tmp_remote])
    run_adb(["shell", f"run-as {PACKAGE_NAME} sh -c 'cat {tmp_remote} > databases/{DB_NAME}'"])
    run_adb(["shell", f"rm {tmp_remote}"])
    print("    [+] Done.")

def get_backups():
    backups = []
    if os.path.exists("backups"):
        for d in os.listdir("backups"):
            if SERIAL in d and os.path.isdir(os.path.join("backups", d)):
                db_path = os.path.join("backups", d, "databases", DB_NAME)
                if os.path.exists(db_path):
                    backups.append(db_path)
    return sorted(backups)

def pipe_restore(src_path, target_db, table, columns):
    temp_b = os.path.join(TMP_DIR, "temp_b.db")
    if os.path.exists(temp_b): os.remove(temp_b)
    
    try:
        shutil.copy2(src_path, temp_b)
        subprocess.run(["sqlite3", temp_b, "PRAGMA journal_mode=delete;"], capture_output=True)
        
        sql_cmd = f".mode insert {table}\nSELECT {columns} FROM {table};"
        res = subprocess.run(["sqlite3", "-batch", temp_b], input=sql_cmd, capture_output=True, text=True)
        
        if res.stderr:
            print(f"    [!] Error reading {table}: {res.stderr.strip()}")
            
        if res.stdout:
            sql = res.stdout.replace(f"INSERT INTO {table}", f"INSERT OR IGNORE INTO {table} ({columns})")
            sql = sql.replace(f"INSERT INTO \"{table}\"", f"INSERT OR IGNORE INTO \"{table}\" ({columns})")
            
            with subprocess.Popen(["sqlite3", target_db], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) as proc:
                stdout, stderr = proc.communicate(sql)
                if stderr:
                    print(f"    [!] Error applying SQL: {stderr.strip()}")
                return True
    except Exception as e:
        print(f"    [!] Restore exception: {e}")
    finally:
        if os.path.exists(temp_b): os.remove(temp_b)
    return False

def init_healthy_db():
    print("[*] Initializing a fresh healthy database schema...")
    schema = """
    CREATE TABLE IF NOT EXISTS weather_data (date TEXT NOT NULL, locationLat REAL NOT NULL, locationLon REAL NOT NULL, locationName TEXT NOT NULL, highTemp INTEGER, lowTemp INTEGER, currentTemp INTEGER, condition TEXT NOT NULL, isActual INTEGER NOT NULL, isClimateNormal INTEGER NOT NULL, source TEXT NOT NULL, stationId TEXT, fetchedAt INTEGER NOT NULL, PRIMARY KEY(date, source));
    CREATE INDEX IF NOT EXISTS index_weather_data_locationLat_locationLon ON weather_data (locationLat, locationLon);
    CREATE TABLE IF NOT EXISTS forecast_snapshots (targetDate TEXT NOT NULL, forecastDate TEXT NOT NULL, locationLat REAL NOT NULL, locationLon REAL NOT NULL, highTemp INTEGER, lowTemp INTEGER, condition TEXT NOT NULL, source TEXT NOT NULL, fetchedAt INTEGER NOT NULL, PRIMARY KEY(targetDate, forecastDate, locationLat, locationLon, source, fetchedAt));
    CREATE INDEX IF NOT EXISTS index_forecast_snapshots_locationLat_locationLon ON forecast_snapshots (locationLat, locationLon);
    CREATE TABLE IF NOT EXISTS hourly_forecasts (dateTime TEXT NOT NULL, locationLat REAL NOT NULL, locationLon REAL NOT NULL, temperature REAL NOT NULL, condition TEXT NOT NULL, source TEXT NOT NULL, fetchedAt INTEGER NOT NULL, PRIMARY KEY(dateTime, source, locationLat, locationLon));
    CREATE INDEX IF NOT EXISTS index_hourly_forecasts_locationLat_locationLon ON hourly_forecasts (locationLat, locationLon);
    CREATE TABLE IF NOT EXISTS app_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, tag TEXT NOT NULL, message TEXT NOT NULL, level TEXT NOT NULL);
    PRAGMA user_version = 14;
    """
    if os.path.exists(HEALTHY_DB): os.remove(HEALTHY_DB)
    with sqlite3.connect(HEALTHY_DB) as conn:
        conn.executescript(schema)

def main():
    pull_db()
    init_healthy_db()
    
    tables = [
        ("weather_data", "date, locationLat, locationLon, locationName, highTemp, lowTemp, currentTemp, condition, isActual, isClimateNormal, source, stationId, fetchedAt"),
        ("forecast_snapshots", "targetDate, forecastDate, locationLat, locationLon, highTemp, lowTemp, condition, source, fetchedAt"),
        ("hourly_forecasts", "dateTime, locationLat, locationLon, temperature, condition, source, fetchedAt")
    ]
    
    print("[*] Salvaging data from current device database...")
    for table, cols in tables:
        pipe_restore(CURRENT_DB, HEALTHY_DB, table, cols)
        
    backups = get_backups()
    print(f"[*] Processing {len(backups)} backups into healthy database...")
    for b in backups:
        print(f"[*] Backup: {b}")
        for table, cols in tables:
            pipe_restore(b, HEALTHY_DB, table, cols)
            
    res = subprocess.run(["sqlite3", HEALTHY_DB, "SELECT count(*) FROM forecast_snapshots;"], capture_output=True, text=True)
    count = res.stdout.strip()
    print(f"\n[*] Finished consolidating data. Total snapshots: {count}")
    
    if int(count) > 0:
        push_db()
    else:
        print("[!] Something went wrong, no snapshots restored. Aborting push.")

if __name__ == "__main__":
    main()

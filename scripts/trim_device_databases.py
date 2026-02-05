#!/usr/bin/env python3
import subprocess
import os
import sys
import time
import sqlite3
import datetime
import re

PACKAGE_NAME = "com.weatherwidget"
DB_NAME = "weather_database"
REMOTE_PATH = f"/data/data/{PACKAGE_NAME}/databases/{DB_NAME}"
LOCAL_TMP_DIR = "tmp_trim"

def run_command(cmd, timeout=60):
    try:
        result = subprocess.run(cmd, timeout=timeout, capture_output=True, text=True)
        return result.stdout.strip(), result.returncode
    except Exception as e:
        return str(e), 1

def run_adb(args, serial=None):
    cmd = ["adb"]
    if serial: cmd.extend(["-s", serial])
    cmd.extend(args)
    return run_command(cmd)

def get_devices():
    stdout, _ = run_adb(["devices"])
    return [l.split()[0] for l in stdout.splitlines() if "device" in l and "List" not in l]

def trim_db(local_path):
    try:
        conn = sqlite3.connect(local_path)
        cursor = conn.cursor()
        target_date = datetime.date.today().strftime("%Y-%m-%d")
        
        cursor.execute("SELECT COUNT(*) FROM forecast_snapshots WHERE forecastDate = ?", (target_date,))
        before = cursor.fetchone()[0]
        if before == 0:
            conn.close()
            return 0
            
        cursor.execute("""
            DELETE FROM forecast_snapshots 
            WHERE forecastDate = ?
            AND fetchedAt NOT IN (
                SELECT MIN(fetchedAt) FROM forecast_snapshots WHERE forecastDate = ?
                GROUP BY targetDate, source
                UNION
                SELECT MAX(fetchedAt) FROM forecast_snapshots WHERE forecastDate = ?
                GROUP BY targetDate, source
            )
        """, (target_date, target_date, target_date))
        conn.commit()
        
        cursor.execute("SELECT COUNT(*) FROM forecast_snapshots WHERE forecastDate = ?", (target_date,))
        after = cursor.fetchone()[0]
        cursor.execute("VACUUM")
        conn.close()
        return before - after
    except Exception as e:
        print(f"    [!] SQL Error: {e}")
        return 0

def process_device(serial):
    print(f"\n[*] Processing {serial}...")
    
    # 1. Check if app is installed
    pkgs, _ = run_adb(["shell", "pm", "list", "packages", PACKAGE_NAME], serial=serial)
    if PACKAGE_NAME not in pkgs:
        print(f"    [!] App {PACKAGE_NAME} not found. Skipping.")
        return

    # 2. Determine access method
    method = "run-as"
    test_ls, _ = run_adb(["shell", f"run-as {PACKAGE_NAME} ls databases/{DB_NAME}"], serial=serial)
    if DB_NAME not in test_ls:
        test_ls, _ = run_adb(["shell", f"su -c 'ls {REMOTE_PATH}'"], serial=serial)
        if DB_NAME in test_ls:
            method = "su"
        else:
            print(f"    [!] Cannot access database on {serial}. Skipping.")
            return

    # 3. Pull database
    os.makedirs(LOCAL_TMP_DIR, exist_ok=True)
    local_db = os.path.join(LOCAL_TMP_DIR, f"{serial}_{DB_NAME}")
    
    for ext in ["", "-wal", "-shm"]:
        remote = f"{REMOTE_PATH}{ext}"
        local = f"{local_db}{ext}"
        if method == "run-as":
            with open(local, "wb") as f:
                subprocess.run(["adb", "-s", serial, "exec-out", "run-as", PACKAGE_NAME, "cat", f"databases/{DB_NAME}{ext}"], stdout=f)
        else:
            with open(local, "wb") as f:
                subprocess.run(["adb", "-s", serial, "exec-out", "su", "-c", f"cat {remote}"], stdout=f)

    # 4. Trim
    removed = trim_db(local_db)
    if removed == 0:
        print(f"    [+] No redundant records found for today.")
        return
    print(f"    [+] Removed {removed} records from local copy.")

    # 5. Push back
    for ext in ["", "-wal", "-shm"]:
        local = f"{local_db}{ext}"
        if not os.path.exists(local): continue
        
        tmp_remote = f"/data/local/tmp/{DB_NAME}{ext}"
        run_adb(["push", local, tmp_remote], serial=serial)
        
        if method == "run-as":
            run_adb(["shell", f"run-as {PACKAGE_NAME} sh -c 'cat {tmp_remote} > databases/{DB_NAME}{ext}'"], serial=serial)
        else:
            run_adb(["shell", f"su -c 'cp {tmp_remote} {REMOTE_PATH}{ext}'"], serial=serial)
            run_adb(["shell", f"su -c 'chmod 660 {REMOTE_PATH}{ext}'"], serial=serial)
            uid, _ = run_adb(["shell", f"dumpsys package {PACKAGE_NAME} | grep userId="], serial=serial)
            uid_match = re.search(r"userId=(\d+)", uid)
            if uid_match:
                u = uid_match.group(1)
                run_adb(["shell", f"su -c 'chown {u}:{u} {REMOTE_PATH}{ext}'"], serial=serial)

        run_adb(["shell", f"rm {tmp_remote}"], serial=serial)

    print(f"    [+] Trimmed database pushed back to {serial}.")

def main():
    devices = get_devices()
    if not devices:
        print("[!] No devices found.")
        return

    print(f"=== Trimming Forecast Snapshots on {len(devices)} Devices ===")
    for serial in devices:
        process_device(serial)
    
    if os.path.exists(LOCAL_TMP_DIR):
        import shutil
        shutil.rmtree(LOCAL_TMP_DIR)
    print("\n[+] All devices processed.")

if __name__ == "__main__":
    main()
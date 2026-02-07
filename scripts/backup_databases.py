#!/usr/bin/env python3
import subprocess
import os
import sys
import time
import re
from datetime import datetime
import json
import sqlite3
import concurrent.futures
import threading

PACKAGE_NAME = "com.weatherwidget"
TIMESTAMP = datetime.now().strftime("%Y%m%d_%H%M%S")
BACKUP_ROOT = "backups"
LOAD_THRESHOLD = 10.0
EMULATOR_PATH = "/home/dcar/.Android/Sdk/emulator/emulator"
FILE_COPY_TIMEOUT = 30
LOGCAT_TIMEOUT = 30

# Global lock for clean printing
print_lock = threading.Lock()

def log(serial, message):
    with print_lock:
        print(f"[{serial}] {message}")

def run_command(cmd, timeout=30, capture_output=True):
    try:
        result = subprocess.run(cmd, timeout=timeout, capture_output=capture_output, text=True)
        return result.stdout.strip(), result.returncode
    except subprocess.TimeoutExpired:
        return "", 124
    except Exception as e:
        return str(e), 1

def run_adb(args, serial=None, timeout=30):
    cmd = ["adb"]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(args)
    return run_command(cmd, timeout=timeout)

def run_adb_to_file(args, out_file, serial=None, timeout=30):
    cmd = ["adb"]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(args)
    try:
        subprocess.run(cmd, stdout=out_file, stderr=subprocess.DEVNULL, timeout=timeout)
        return 0
    except subprocess.TimeoutExpired:
        return 124
    except Exception:
        return 1

def get_devices():
    stdout, _ = run_adb(["devices"])
    devices = []
    # Known adb device states
    states = ["device", "offline", "unauthorized", "recovery", "sideload", "bootloader"]
    state_pattern = "|".join(states)
    
    for line in stdout.splitlines():
        if "List of devices" in line or not line.strip():
            continue
        
        # Match everything from start until a known state followed by whitespace or end of line
        match = re.search(f"^(.*?)\\s+({state_pattern})(?:\\s+|$)", line)
        if match:
            serial = match.group(1).strip()
            state = match.group(2)
            if state == "device":
                devices.append(serial)
    return devices

def get_load_average(serial):
    stdout, code = run_adb(["shell", "uptime"], serial=serial, timeout=10)
    if code != 0:
        return None
    match = re.search(r"load average:\s+([\d.]+)", stdout)
    if match:
        return float(match.group(1))
    return None

def get_avd_name(serial):
    stdout, _ = run_adb(["shell", "getprop", "ro.boot.qemu.avd_name"], serial=serial, timeout=10)
    return stdout.strip()

def restart_emulator(serial, avd_name):
    log(serial, f"Restarting (AVD: {avd_name})...")
    run_adb(["emu", "kill"], serial=serial)
    time.sleep(3)
    
    subprocess.Popen([EMULATOR_PATH, f"@{avd_name}", "-no-snapshot-load"], 
                     stdout=subprocess.DEVNULL, 
                     stderr=subprocess.DEVNULL, 
                     preexec_fn=os.setpgrp)
    
    start_time = time.time()
    while time.time() - start_time < 120:
        stdout, _ = run_adb(["shell", "getprop", "sys.boot_completed"], timeout=5)
        if stdout.strip() == "1":
            time.sleep(5)
            return True
        time.sleep(5)
    return False

def backup_device(serial):
    try:
        # Check load
        load = get_load_average(serial)
        if load is not None and load > LOAD_THRESHOLD and serial.startswith("emulator-"):
            avd_name = get_avd_name(serial)
            if avd_name:
                restart_emulator(serial, avd_name)

        # Get model info
        model, _ = run_adb(["shell", "getprop", "ro.product.model"], serial=serial)
        model = model.replace(" ", "_").lower()
        safe_id = serial.replace(".", "").replace(":", "").replace("/", "_")
        folder_name = f"{TIMESTAMP}_{model}_{safe_id}"
        dest_dir = os.path.join(BACKUP_ROOT, folder_name)
        os.makedirs(dest_dir, exist_ok=True)
        
        # Check package
        pkgs, code = run_adb(["shell", "pm", "list", "packages", PACKAGE_NAME], serial=serial, timeout=15)
        if code == 124 or PACKAGE_NAME not in pkgs:
            log(serial, "Skipped: App not found or unresponsive.")
            return False
        
        log(serial, f"Backing up (Load: {load if load else '?'})")

        def copy_subdir(subfolder, target_name):
            os.makedirs(os.path.join(dest_dir, target_name), exist_ok=True)
            files_str, _ = run_adb(["shell", f"run-as {PACKAGE_NAME} ls /data/data/{PACKAGE_NAME}/{subfolder}/"], serial=serial)
            method = "run-as"
            if not files_str:
                files_str, _ = run_adb(["shell", f"su -c 'ls /data/data/{PACKAGE_NAME}/{subfolder}/'"], serial=serial)
                method = "su"
                
            if not files_str:
                return 0, 0
                
            files = files_str.split()
            total_size = 0
            copied = 0
            for f in files:
                f = f.strip()
                local_path = os.path.join(dest_dir, target_name, f)
                remote_path = f"/data/data/{PACKAGE_NAME}/{subfolder}/{f}"
                with open(local_path, "wb") as out_f:
                    if method == "run-as":
                        code = run_adb_to_file(
                            ["exec-out", "run-as", PACKAGE_NAME, "cat", remote_path],
                            out_f,
                            serial=serial,
                            timeout=FILE_COPY_TIMEOUT,
                        )
                    else:
                        code = run_adb_to_file(
                            ["exec-out", "su", "-c", f"cat {remote_path}"],
                            out_f,
                            serial=serial,
                            timeout=FILE_COPY_TIMEOUT,
                        )
                if code == 124:
                    log(serial, f"Timeout copying {subfolder}/{f}; skipping.")
                    continue
                if code != 0:
                    log(serial, f"Failed copying {subfolder}/{f}; skipping.")
                    continue
                if os.path.exists(local_path):
                    total_size += os.path.getsize(local_path)
                    copied += 1
            return copied, total_size

        db_count, db_size = copy_subdir("databases", "databases")
        pref_count, pref_size = copy_subdir("shared_prefs", "shared_prefs")
        
        # DB stats
        db_path = os.path.join(dest_dir, "databases", "weather_database")
        if os.path.exists(db_path):
            try:
                conn = sqlite3.connect(db_path)
                cursor = conn.cursor()
                stats = {}
                for table in ["weather_data", "forecast_snapshots", "hourly_forecasts"]:
                    try:
                        cursor.execute(f"SELECT count(*) FROM {table}")
                        stats[table] = cursor.fetchone()[0]
                    except:
                        pass
                with open(os.path.join(dest_dir, "stats.txt"), "w") as f:
                    for k, v in stats.items():
                        f.write(f"{k}: {v}\n")
                conn.close()
            except:
                pass

        # Logcat
        logcat_path = os.path.join(dest_dir, "logcat.txt")
        with open(logcat_path, "w") as f:
            code = run_adb_to_file(["logcat", "-d", "-t", "1000"], f, serial=serial, timeout=LOGCAT_TIMEOUT)
            if code == 124:
                log(serial, "Timeout capturing logcat; wrote partial output.")
            elif code != 0:
                log(serial, "Failed capturing logcat.")

        # Metadata
        metadata = {"timestamp": TIMESTAMP, "serial": serial, "model": model, "load": load}
        with open(os.path.join(dest_dir, "metadata.json"), "w") as f:
            json.dump(metadata, f, indent=4)
        
        total_kb = (db_size + pref_size + os.path.getsize(logcat_path)) // 1024
        log(serial, f"Done: {db_count} DBs, {pref_count} Prefs. Total: {total_kb} KB")
        return True
    except Exception as e:
        log(serial, f"Unexpected error: {e}")
        return False

def main():
    print(f"=== Weather Widget Backup Tool ({TIMESTAMP}) ===")
    devices = get_devices()
    if not devices:
        print("[!] No devices found.")
        sys.exit(1)
        
    print(f"[*] Backing up {len(devices)} devices in parallel...")
    processed = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(devices)) as executor:
        futures = {executor.submit(backup_device, s): s for s in devices}
        for future in concurrent.futures.as_completed(futures):
            if future.result(): processed += 1
            
    print(f"[*] Summary: {processed}/{len(devices)} devices backed up to {BACKUP_ROOT}/")

if __name__ == "__main__":
    main()

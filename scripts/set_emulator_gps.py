#!/usr/bin/env python3
import subprocess
import sys
import re

ADB_PATH = "/home/dcar/.Android/Sdk/platform-tools/adb"

def run_command(cmd):
    try:
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10)
        if result.returncode == 0:
            return result.stdout.strip()
        else:
            return None
    except Exception as e:
        print(f"Error running {' '.join(cmd)}: {e}")
        return None

def get_devices():
    output = run_command([ADB_PATH, "devices"])
    if not output:
        return []
    devices = []
    lines = output.split('\n')
    for line in lines[1:]:
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 2:
            serial, state = parts[0], parts[1]
            if state == 'device':
                devices.append(serial)
    return devices

def get_device_property(serial, prop):
    val = run_command([ADB_PATH, "-s", serial, "shell", "getprop", prop])
    return val.strip() if val else ""

def is_emulator(serial):
    if serial.startswith("emulator-"):
        return True
    qemu = get_device_property(serial, "ro.kernel.qemu")
    return qemu == "1"

def get_samsung_device(devices):
    for serial in devices:
        if is_emulator(serial):
            continue
        manufacturer = get_device_property(serial, "ro.product.manufacturer").lower()
        if "samsung" in manufacturer:
            return serial
    # Fallback to hardcoded Samsung serial from rules if not found dynamically
    if "RFCT71FR9NT" in devices:
        return "RFCT71FR9NT"
    return None

def parse_gps_coordinates(dumpsys_output):
    # Regex matching: Location[fused 37.416824,-122.088962 ... or Location[gps 37.416797,-122.089000 ...
    pattern = re.compile(r'Location\[(\w+)\s+(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)')
    
    locations = []
    for line in dumpsys_output.split('\n'):
        match = pattern.search(line)
        if match:
            provider = match.group(1)
            lat = float(match.group(2))
            lon = float(match.group(3))
            locations.append((provider, lat, lon))
            
    # Prefer gps, then fused, then others
    gps_loc = [loc for loc in locations if loc[0] == 'gps']
    if gps_loc:
        return gps_loc[0][1], gps_loc[0][2]
    
    fused_loc = [loc for loc in locations if loc[0] == 'fused']
    if fused_loc:
        return fused_loc[0][1], fused_loc[0][2]
        
    if locations:
        return locations[0][1], locations[0][2]
    return None

def main():
    devices = get_devices()
    if not devices:
        print("No connected ADB devices found.")
        sys.exit(1)
        
    print(f"Connected devices: {devices}")
    
    samsung_serial = get_samsung_device(devices)
    if not samsung_serial:
        print("No Samsung device found.")
        sys.exit(1)
        
    model = get_device_property(samsung_serial, "ro.product.model")
    print(f"Found Samsung device: {samsung_serial} ({model})")
    
    # Dump location
    dumpsys = run_command([ADB_PATH, "-s", samsung_serial, "shell", "dumpsys", "location"])
    if not dumpsys:
        print(f"Failed to dump location from Samsung device {samsung_serial}.")
        sys.exit(1)
        
    coords = parse_gps_coordinates(dumpsys)
    if not coords:
        print(f"Could not parse GPS coordinates from Samsung location dump.")
        sys.exit(1)
        
    lat, lon = coords
    print(f"Retrieved location from Samsung device: Lat = {lat}, Lon = {lon}")
    
    # Find all running emulators
    emulators = [d for d in devices if is_emulator(d)]
    if not emulators:
        print("No running emulators found to update.")
        sys.exit(1)
        
    print(f"Found running emulators: {emulators}")
    
    for emu in emulators:
        print(f"Setting location of {emu} to {lat}, {lon}...")
        
        # 1. Traditional geo fix
        print("  - Sending 'emu geo fix' command...")
        res = subprocess.run([ADB_PATH, "-s", emu, "emu", "geo", "fix", str(lon), str(lat)], 
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode != 0:
            print(f"    Warning: 'emu geo fix' returned non-zero code. Stderr: {res.stderr.strip()}")
            
        # 2. Modern mock location provider configuration
        print("  - Injecting mock coordinates via system provider...")
        run_command([ADB_PATH, "-s", emu, "shell", "appops", "set", "2000", "android:mock_location", "allow"])
        
        # GPS provider
        run_command([ADB_PATH, "-s", emu, "shell", "cmd", "location", "providers", "add-test-provider", "gps"])
        run_command([ADB_PATH, "-s", emu, "shell", "cmd", "location", "providers", "set-test-provider-enabled", "gps", "true"])
        run_command([ADB_PATH, "-s", emu, "shell", "cmd", "location", "providers", "set-test-provider-location", "gps", "--location", f"{lat},{lon}"])
        
        # Fused provider
        run_command([ADB_PATH, "-s", emu, "shell", "cmd", "location", "providers", "add-test-provider", "fused"])
        run_command([ADB_PATH, "-s", emu, "shell", "cmd", "location", "providers", "set-test-provider-enabled", "fused", "true"])
        run_command([ADB_PATH, "-s", emu, "shell", "cmd", "location", "providers", "set-test-provider-location", "fused", "--location", f"{lat},{lon}"])
        
        print(f"Successfully updated location for {emu}.")
            
if __name__ == "__main__":
    main()

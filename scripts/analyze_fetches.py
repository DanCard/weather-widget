import sqlite3
import os
import datetime
import glob

def analyze_database(db_path, device_name):
    try:
        conn = sqlite3.connect(db_path)
        cursor = conn.cursor()
        
        print("\n" + "="*85)
        print(f"Device: {device_name}")
        print(f"Path: {db_path}")
        print("="*85)
        
        # We'll look at the last 7 days from Feb 4, 2026
        today = datetime.date(2026, 2, 4)
        
        # NWS has separate fetch types; Meteo (All) includes both forecast and history in one call.
        print(f"{'Date':<12} | {'NWS Fcst':<10} | {'NWS Obs':<10} | {'Meteo (All)':<12} | {'Gap Data':<8} | {'Hourly':<8}")
        print("-" * 85)
        
        for i in range(7, -1, -1):
            date = today - datetime.timedelta(days=i)
            start_ts = int(datetime.datetime.combine(date, datetime.time.min).timestamp() * 1000)
            end_ts = int(datetime.datetime.combine(date, datetime.time.max).timestamp() * 1000)
            
            # NWS Forecast sessions
            cursor.execute("""
                SELECT COUNT(DISTINCT fetchedAt) FROM forecast_snapshots 
                WHERE source = 'NWS' AND fetchedAt >= ? AND fetchedAt <= ?
            """, (start_ts, end_ts))
            nws_fcst = cursor.fetchone()[0]
            
            # NWS Observation sessions (one session triggers 8 API calls)
            cursor.execute("""
                SELECT COUNT(DISTINCT fetchedAt) FROM weather_data 
                WHERE source = 'NWS' AND stationId IS NOT NULL AND fetchedAt >= ? AND fetchedAt <= ?
            """, (start_ts, end_ts))
            nws_obs = cursor.fetchone()[0]
            
            # Open-Meteo sessions (Combined: one API call for forecast + 7 days history)
            cursor.execute("""
                SELECT COUNT(DISTINCT fetchedAt) FROM forecast_snapshots 
                WHERE source = 'OPEN_METEO' AND fetchedAt >= ? AND fetchedAt <= ?
            """, (start_ts, end_ts))
            meteo_count = cursor.fetchone()[0]
            
            # Gap Data fetches
            cursor.execute("""
                SELECT COUNT(DISTINCT fetchedAt) FROM weather_data 
                WHERE isClimateNormal = 1 AND fetchedAt >= ? AND fetchedAt <= ?
            """, (start_ts, end_ts))
            gap_count = cursor.fetchone()[0]

            # Hourly fetches
            cursor.execute("""
                SELECT COUNT(DISTINCT fetchedAt) FROM hourly_forecasts 
                WHERE fetchedAt >= ? AND fetchedAt <= ?
            """, (start_ts, end_ts))
            hourly_count = cursor.fetchone()[0]
            
            date_str = date.strftime("%Y-%m-%d")
            print(f"{date_str:<12} | {nws_fcst:<10} | {nws_obs:<10} | {meteo_count:<12} | {gap_count:<8} | {hourly_count:<8}")
            
        conn.close()
    except Exception as e:
        print(f"Error analyzing {device_name}: {e}")

def main():
    backup_dir = "/home/dcar/projects/weather-widget/backups"
    db_pattern = os.path.join(backup_dir, "*/databases/weather_database")
    
    databases = glob.glob(db_pattern)
    
    for db_path in sorted(databases):
        parts = db_path.split('/')
        if len(parts) >= 3:
            device_name = parts[-3]
            analyze_database(db_path, device_name)

if __name__ == "__main__":
    main()

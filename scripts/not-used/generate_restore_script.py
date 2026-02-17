import sqlite3
import os

# Source is the full backup from 16:41
SOURCE_DB = "backups/20260204_164159_sm-f936u1_RFCT71FR9NT/databases/weather_database"
# Current is the depleted backup from 18:10
CURRENT_DB = "backups/20260204_181022_sm-f936u1_RFCT71FR9NT/databases/weather_database"
OUTPUT_SQL = "restore_missing_history.sql"

def generate_restore_script():
    if not os.path.exists(SOURCE_DB):
        print(f"Error: Source DB not found at {SOURCE_DB}")
        return

    conn = sqlite3.connect(SOURCE_DB)
    cursor = conn.cursor()
    
    # Attach current DB to compare
    cursor.execute(f"ATTACH '{CURRENT_DB}' AS current")
    
    with open(OUTPUT_SQL, "w") as f:
        f.write("-- Surgical History Restoration Script\n")
        f.write("-- Generated to restore only missing historical records\n\n")
        
        # 1. Restore missing Forecast Snapshots
        f.write("-- 1. Restoring missing forecast snapshots (the evolution curve data)\n")
        cursor.execute("""
            SELECT targetDate, forecastDate, locationLat, locationLon, highTemp, lowTemp, condition, source, fetchedAt 
            FROM main.forecast_snapshots 
            WHERE (targetDate, forecastDate, source, fetchedAt) NOT IN (
                SELECT targetDate, forecastDate, source, fetchedAt FROM current.forecast_snapshots
            )
        """)
        rows = cursor.fetchall()
        for row in rows:
            # Handle nulls and escaping
            vals = []
            for v in row:
                if v is None: vals.append("NULL")
                elif isinstance(v, str): vals.append("'" + v.replace("'", "''") + "'")
                else: vals.append(str(v))
            f.write(f"INSERT OR IGNORE INTO forecast_snapshots VALUES ({', '.join(vals)});\n")
        
        f.write(f"\n-- Restored {len(rows)} snapshots\n\n")
        
        # 2. Restore missing Weather Observations (Actual records)
        f.write("-- 2. Restoring missing actual weather observations\n")
        # Schema: date, lat, lon, name, high, low, current, condition, isActual, isClimateNormal, source, stationId, fetchedAt
        cursor.execute("""
            SELECT date, locationLat, locationLon, locationName, highTemp, lowTemp, currentTemp, condition, isActual, isClimateNormal, source, stationId, fetchedAt
            FROM main.weather_data 
            WHERE isActual = 1 AND (date, source) NOT IN (
                SELECT date, source FROM current.weather_data
            )
        """)
        rows = cursor.fetchall()
        for row in rows:
            vals = []
            for v in row:
                if v is None: vals.append("NULL")
                elif isinstance(v, str): vals.append("'" + v.replace("'", "''") + "'")
                else: vals.append(str(v))
            f.write(f"INSERT OR IGNORE INTO weather_data VALUES ({', '.join(vals)});\n")
            
        f.write(f"\n-- Restored {len(rows)} weather records\n")

    print(f"Successfully generated {OUTPUT_SQL}")
    conn.close()

if __name__ == "__main__":
    generate_restore_script()
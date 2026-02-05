import sqlite3
import os
import glob
import datetime

def trim_database(db_path):
    try:
        conn = sqlite3.connect(db_path)
        cursor = conn.cursor()
        
        # Use today's date
        target_date = datetime.date.today().strftime("%Y-%m-%d")
        
        # Count before
        cursor.execute("SELECT COUNT(*) FROM forecast_snapshots WHERE forecastDate = ?", (target_date,))
        before = cursor.fetchone()[0]
        
        if before == 0:
            conn.close()
            return
            
        print(f"Trimming {db_path}...")
        print(f"  Rows before: {before}")
        
        # Strategy: For each targetDate and source, keep only the earliest and latest fetchedAt
        # This preserves the first prediction of the day and the final corrected one.
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
        
        # Count after
        cursor.execute("SELECT COUNT(*) FROM forecast_snapshots WHERE forecastDate = ?", (target_date,))
        after = cursor.fetchone()[0]
        print(f"  Rows after:  {after}")
        print(f"  Removed:     {before - after}")
        
        # Vacuum to reclaim space
        cursor.execute("VACUUM")
        conn.close()
    except Exception as e:
        print(f"Error trimming {db_path}: {e}")

def main():
    backup_dir = "/home/dcar/projects/weather-widget/backups"
    db_pattern = os.path.join(backup_dir, "*/databases/weather_database")
    databases = glob.glob(db_pattern)
    
    for db_path in databases:
        trim_database(db_path)

if __name__ == "__main__":
    main()
#!/bin/bash
set -euo pipefail

ADB_BIN="/home/dcar/.Android/Sdk/platform-tools/adb"
PACKAGE_NAME="com.weatherwidget"
DB_PATH="/data/data/${PACKAGE_NAME}/databases/weather_database"
CUTOFF="18:00:00"
MODE="dry-run"
LOG_DIR="logs"

usage() {
    cat <<USAGE
Usage: $0 [--apply] [--cutoff HH:MM:SS]

Options:
  --apply           Apply deletion on attached devices (default: dry-run)
  --cutoff <time>   Local-time cutoff (default: 18:00:00)
  -h, --help        Show help
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply)
            MODE="apply"
            shift
            ;;
        --cutoff)
            CUTOFF="${2:-}"
            if [[ -z "$CUTOFF" ]]; then
                echo "Error: --cutoff requires HH:MM:SS"
                exit 1
            fi
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Error: Unknown argument: $1"
            usage
            exit 1
            ;;
    esac
done

mkdir -p "$LOG_DIR"
RUN_TS=$(date +%Y%m%d_%H%M%S)
LOG_FILE="$LOG_DIR/device_delete_nws_same_day_after_6pm_${RUN_TS}.log"
CSV_FILE="$LOG_DIR/device_delete_nws_same_day_after_6pm_${RUN_TS}.csv"

echo "run_started=$(date -Iseconds)" > "$LOG_FILE"
echo "mode=$MODE" >> "$LOG_FILE"
echo "cutoff=$CUTOFF" >> "$LOG_FILE"
echo >> "$LOG_FILE"

echo "device,rowid,targetDate,forecastDate,locationLat,locationLon,highTemp,lowTemp,condition,source,fetchedAt,fetchedLocal" > "$CSV_FILE"

mapfile -t DEVICES < <("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "No attached devices found."
    exit 1
fi

echo "devices=${DEVICES[*]}" | tee -a "$LOG_FILE"

TOTAL_CANDIDATES=0
TOTAL_DELETED=0

for SERIAL in "${DEVICES[@]}"; do
    echo "[$SERIAL] Processing..." | tee -a "$LOG_FILE"

    if ! "$ADB_BIN" -s "$SERIAL" shell pm list packages "$PACKAGE_NAME" | grep -q "$PACKAGE_NAME"; then
        echo "[$SERIAL] Skipped: app not installed" | tee -a "$LOG_FILE"
        echo >> "$LOG_FILE"
        continue
    fi

    TMP_DIR=$(mktemp -d "/tmp/nws_cleanup_${SERIAL}_XXXX")
    LOCAL_DB="$TMP_DIR/weather_database"
    LOCAL_DB_BACKUP="$LOG_DIR/${RUN_TS}_${SERIAL}_weather_database.bak"

    if ! "$ADB_BIN" -s "$SERIAL" exec-out run-as "$PACKAGE_NAME" cat "$DB_PATH" > "$LOCAL_DB" 2>/dev/null; then
        echo "[$SERIAL] Error: failed to pull database via run-as" | tee -a "$LOG_FILE"
        rm -rf "$TMP_DIR"
        echo >> "$LOG_FILE"
        continue
    fi

    if [[ ! -s "$LOCAL_DB" ]]; then
        echo "[$SERIAL] Error: pulled database is empty" | tee -a "$LOG_FILE"
        rm -rf "$TMP_DIR"
        echo >> "$LOG_FILE"
        continue
    fi

    cp "$LOCAL_DB" "$LOCAL_DB_BACKUP"
    echo "[$SERIAL] local_backup=$LOCAL_DB_BACKUP" | tee -a "$LOG_FILE"

    DETAILS_SQL="
        SELECT
          '$SERIAL' AS device,
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
          AND time(fetchedAt/1000, 'unixepoch', 'localtime') >= '$CUTOFF'
        ORDER BY fetchedAt ASC;
    "

    COUNT_SQL="
        SELECT COUNT(*)
        FROM forecast_snapshots
        WHERE source = 'NWS'
          AND targetDate = forecastDate
          AND time(fetchedAt/1000, 'unixepoch', 'localtime') >= '$CUTOFF';
    "

    CANDIDATES=$(sqlite3 "$LOCAL_DB" "$COUNT_SQL" 2>/dev/null || echo "ERR")
    if [[ "$CANDIDATES" == "ERR" ]]; then
        echo "[$SERIAL] Error: sqlite query failed" | tee -a "$LOG_FILE"
        rm -rf "$TMP_DIR"
        echo >> "$LOG_FILE"
        continue
    fi

    TOTAL_CANDIDATES=$((TOTAL_CANDIDATES + CANDIDATES))
    echo "[$SERIAL] candidate_rows=$CANDIDATES" | tee -a "$LOG_FILE"

    if [[ "$CANDIDATES" -gt 0 ]]; then
        sqlite3 -csv "$LOCAL_DB" "$DETAILS_SQL" >> "$CSV_FILE"
    fi

    if [[ "$MODE" == "apply" && "$CANDIDATES" -gt 0 ]]; then
        DELETE_SQL="
            DELETE FROM forecast_snapshots
            WHERE source = 'NWS'
              AND targetDate = forecastDate
              AND time(fetchedAt/1000, 'unixepoch', 'localtime') >= '$CUTOFF';
        "

        if ! sqlite3 "$LOCAL_DB" "$DELETE_SQL" 2>/dev/null; then
            echo "[$SERIAL] Error: delete failed in local DB copy" | tee -a "$LOG_FILE"
            rm -rf "$TMP_DIR"
            echo >> "$LOG_FILE"
            continue
        fi

        REMAINING=$(sqlite3 "$LOCAL_DB" "$COUNT_SQL" 2>/dev/null || echo "ERR")
        if [[ "$REMAINING" == "ERR" || "$REMAINING" -ne 0 ]]; then
            echo "[$SERIAL] Error: post-delete verification failed (remaining=$REMAINING)" | tee -a "$LOG_FILE"
            rm -rf "$TMP_DIR"
            echo >> "$LOG_FILE"
            continue
        fi

        REMOTE_TMP="/data/local/tmp/weather_database.cleaned.${SERIAL}"
        "$ADB_BIN" -s "$SERIAL" push "$LOCAL_DB" "$REMOTE_TMP" >/dev/null
        "$ADB_BIN" -s "$SERIAL" shell run-as "$PACKAGE_NAME" cp "$REMOTE_TMP" "$DB_PATH"
        "$ADB_BIN" -s "$SERIAL" shell run-as "$PACKAGE_NAME" rm -f "${DB_PATH}-wal" >/dev/null 2>&1 || true
        "$ADB_BIN" -s "$SERIAL" shell run-as "$PACKAGE_NAME" rm -f "${DB_PATH}-shm" >/dev/null 2>&1 || true
        "$ADB_BIN" -s "$SERIAL" shell rm -f "$REMOTE_TMP" >/dev/null 2>&1 || true

        TOTAL_DELETED=$((TOTAL_DELETED + CANDIDATES))
        echo "[$SERIAL] deleted_rows=$CANDIDATES" | tee -a "$LOG_FILE"
    elif [[ "$MODE" == "apply" ]]; then
        echo "[$SERIAL] deleted_rows=0" | tee -a "$LOG_FILE"
    fi

    rm -rf "$TMP_DIR"
    echo >> "$LOG_FILE"
done

echo "total_candidate_rows=$TOTAL_CANDIDATES" | tee -a "$LOG_FILE"
if [[ "$MODE" == "apply" ]]; then
    echo "total_deleted_rows=$TOTAL_DELETED" | tee -a "$LOG_FILE"
else
    echo "total_deleted_rows=0" | tee -a "$LOG_FILE"
fi

echo "log_file=$LOG_FILE"
echo "csv_file=$CSV_FILE"

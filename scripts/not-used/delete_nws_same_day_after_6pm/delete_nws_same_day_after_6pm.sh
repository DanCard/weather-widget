#!/bin/bash
set -uo pipefail

# Delete same-day NWS forecast snapshots captured after 6 PM local time.
# Default mode is dry-run. Use --apply to execute deletes.
#
# Examples:
#   ./scripts/delete_nws_same_day_after_6pm.sh
#   ./scripts/delete_nws_same_day_after_6pm.sh --apply
#   ./scripts/delete_nws_same_day_after_6pm.sh --db backups/20260205_221534_pixel_7_pro_2A191FDH300PPW/databases/weather_database --apply

MODE="dry-run"
DB_PATH=""
CUTOFF="18:00:00"
BACKUP_DIR="/tmp/weather-widget-db-backups"

usage() {
    cat <<USAGE
Usage: $0 [--apply] [--db <path>] [--cutoff HH:MM:SS]

Options:
  --apply           Execute DELETE statements (default is dry-run)
  --db <path>       Target a single database file
  --cutoff <time>   Local time cutoff (default: 18:00:00)
  -h, --help        Show this help
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply)
            MODE="apply"
            shift
            ;;
        --db)
            DB_PATH="${2:-}"
            if [[ -z "$DB_PATH" ]]; then
                echo "Error: --db requires a path"
                exit 1
            fi
            shift 2
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

mkdir -p "$BACKUP_DIR"

TOTAL_MATCHED=0
TOTAL_DELETED=0

run_for_db() {
    local DB="$1"

    if [[ ! -f "$DB" ]]; then
        echo "Skipping missing DB: $DB"
        return
    fi

    MATCHED=$(sqlite3 "$DB" "
        SELECT COUNT(*)
        FROM forecast_snapshots
        WHERE source = 'NWS'
          AND targetDate = forecastDate
          AND time(fetchedAt/1000, 'unixepoch', 'localtime') >= '$CUTOFF';
    " 2>/dev/null) || {
        echo "DB: $DB"
        echo "  Warning: failed to read/query database (possibly malformed). Skipping."
        return
    }

    TOTAL_MATCHED=$((TOTAL_MATCHED + MATCHED))

    echo "DB: $DB"
    echo "  Matched rows: $MATCHED"

    if [[ "$MODE" == "apply" && "$MATCHED" -gt 0 ]]; then
        TS=$(date +%Y%m%d_%H%M%S)
        DB_SAFE=$(echo "$DB" | sed 's#[/ ]#_#g')
        SNAPSHOT="$BACKUP_DIR/${TS}${DB_SAFE}.bak"
        cp "$DB" "$SNAPSHOT"

        sqlite3 "$DB" "
            DELETE FROM forecast_snapshots
            WHERE source = 'NWS'
              AND targetDate = forecastDate
              AND time(fetchedAt/1000, 'unixepoch', 'localtime') >= '$CUTOFF';
        " 2>/dev/null || {
            echo "  Warning: delete failed (possibly malformed). Leaving DB unchanged."
            return
        }
        REMAINING=$(sqlite3 "$DB" "
            SELECT COUNT(*)
            FROM forecast_snapshots
            WHERE source = 'NWS'
              AND targetDate = forecastDate
              AND time(fetchedAt/1000, 'unixepoch', 'localtime') >= '$CUTOFF';
        " 2>/dev/null) || {
            echo "  Warning: post-delete verification failed."
            return
        }
        VERIFIED_DELETED=$((MATCHED - REMAINING))

        TOTAL_DELETED=$((TOTAL_DELETED + VERIFIED_DELETED))

        echo "  Backup: $SNAPSHOT"
        echo "  Deleted rows: $VERIFIED_DELETED"
        echo "  Remaining matches: $REMAINING"
    fi
}

if [[ -n "$DB_PATH" ]]; then
    run_for_db "$DB_PATH"
else
    FOUND_ANY=0
    while IFS= read -r -d '' DB; do
        FOUND_ANY=1
        run_for_db "$DB"
    done < <(find backups -type f -path "*/databases/weather_database" -print0 2>/dev/null)

    if [[ "$FOUND_ANY" -eq 0 ]]; then
        echo "No database files found."
        exit 1
    fi
fi

echo "------------------------------------------------------------"
echo "Mode: $MODE"
echo "Cutoff: $CUTOFF"
echo "Total matched rows: $TOTAL_MATCHED"
if [[ "$MODE" == "apply" ]]; then
    echo "Total deleted rows: $TOTAL_DELETED"
    echo "Backups written under: $BACKUP_DIR"
else
    echo "Dry run only. Re-run with --apply to delete rows."
fi

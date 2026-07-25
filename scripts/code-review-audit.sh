#!/usr/bin/env bash
set -e

# ==============================================================================
# Weather Widget Code Review Audit Script
# Standardized tool to run code smell, complexity, copy-paste, and category audits.
# ==============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT_DIR"

echo "================================================================================"
echo "               WEATHER WIDGET CODE REVIEW AUDIT SUITE                           "
echo "================================================================================"

# 1. Run Complexity & File Size Audit
echo ""
echo "[1/4] Running Kotlin Code Complexity & File Size Analysis..."
python3 "$SCRIPT_DIR/analyze_complexity.py"

# 2. Run Duplicate Code Detection (PMD CPD)
echo ""
echo "[2/4] Running Copy-Paste Detection (CPD)..."
./gradlew cpdCheck --quiet || echo "Warning: cpdCheck encountered an issue."

if [ -f "$ROOT_DIR/build/reports/cpd/cpd.txt" ]; then
    DUPS=$(grep -c "Found a " "$ROOT_DIR/build/reports/cpd/cpd.txt" || true)
    echo "  - PMD CPD Scan complete: $DUPS duplication blocks detected."
    echo "  - Report output: build/reports/cpd/cpd.txt"
fi

# 3. Run Formatting & Linting Audit
echo ""
echo "[3/4] Running KtLint Linter Check..."
./gradlew :app:ktlintCheck --quiet || {
    echo "❌ KtLint failed! Please run './gradlew :app:ktlintFormat' to fix formatting."
    exit 1
}
echo "  - KtLint check passed cleanly."

# 4. Run Test Category Validation
echo ""
echo "[4/4] Validating Test Category Duration Annotations..."
./gradlew validateUnitTestDurations --quiet || {
    echo "❌ Test Category validation failed! Ensure all test classes declare a @Category."
    exit 1
}
echo "  - All test classes have valid @Category annotations."

echo ""
echo "================================================================================"
echo "               ✅ CODE REVIEW AUDIT COMPLETE (ALL GATES PASSED)                 "
echo "================================================================================"

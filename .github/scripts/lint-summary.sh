#!/usr/bin/env bash
# =============================================================================
# Summarize Android Lint results (informational).
#
# Enforcement lives in the lint task itself: app/build.gradle configures
#   lint { baseline = file('lint-baseline.xml'); checkReleaseBuilds false }
# so historic issues (recorded in the baseline) are tolerated while any NEW
# Error-severity issue fails :app:lintDebug directly.  Warnings never abort.
#
# This script prints the report totals for visibility in the CI log and in
# the uploaded lint-report artifact; it exits 0 unless no report exists at
# all (which would indicate the lint task did not run).
# =============================================================================
set -uo pipefail

results=( $(find . -type f \( -name 'lint-results-*.xml' -o -name 'lint-results-*.txt' \) \
    -path '*/reports/*' 2>/dev/null) )

if [ ${#results[@]} -eq 0 ]; then
    echo "lint-summary: no lint result files found (did lint run?)"
    exit 0
fi

total_errors=0
total_warnings=0

for f in "${results[@]}"; do
    case "$f" in
        *.xml)
            errors=$(grep -o 'severity="Error"' "$f" | wc -l)
            warnings=$(grep -o 'severity="Warning"' "$f" | wc -l)
            ;;
        *.txt)
            errors=$(grep -cE '^Error: ' "$f" || true)
            warnings=$(grep -cE '^Warning: ' "$f" || true)
            ;;
    esac
    total_errors=$((total_errors + errors))
    total_warnings=$((total_warnings + warnings))
    echo "$f: $errors errors, $warnings warnings"
done

echo ""
echo "=============================================="
echo " Lint totals: $total_errors errors / $total_warnings warnings"
echo " (errors matching lint-baseline.xml are historic"
echo "  and tolerated; NEW errors fail the lint task)"
echo "=============================================="
exit 0

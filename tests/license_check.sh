#!/usr/bin/env bash
# =============================================================================
# License / notice compliance check.
#
# Verifies that the legally required files are present and non-empty, and
# that the NOTICE still credits the upstream projects the app embeds.
# =============================================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0
FAIL=0

ok()  { echo "PASS $1"; PASS=$((PASS + 1)); }
bad() { echo "FAIL $1"; FAIL=$((FAIL + 1)); }

require_file() {
    local f="$1"
    if [ -s "$ROOT/$f" ]; then ok "file:$f"; else bad "file:$f (missing or empty)"; fi
}

require_contains() {
    local f="$1"
    local needle="$2"
    if [ -f "$ROOT/$f" ] && grep -qi "$needle" "$ROOT/$f"; then
        ok "contains:$f~$needle"
    else
        bad "contains:$f~$needle"
    fi
}

# --- required license files ------------------------------------------------
require_file "LICENSE"
require_file "NOTICE"
require_file "THIRD_PARTY_NOTICES.md"
require_file "LICENSES/Apache-2.0.txt"
require_file "LICENSES/MPL-2.0.txt"
require_file "embedded/LICENSES/Apache-2.0.txt"
require_file "embedded/LICENSES/Stellar-MPL-2.0.txt"

# --- license types ----------------------------------------------------------
require_contains "LICENSE" "GNU AFFERO GENERAL PUBLIC LICENSE"
require_contains "LICENSES/Apache-2.0.txt" "Apache License"
require_contains "LICENSES/MPL-2.0.txt" "Mozilla Public License"

# --- upstream credits -------------------------------------------------------
require_contains "NOTICE" "Stellar"
require_contains "NOTICE" "Shizuku"
require_contains "THIRD_PARTY_NOTICES.md" "Stellar"
require_contains "THIRD_PARTY_NOTICES.md" "libsu"

echo
echo "=================================================="
echo "PASS: $PASS  FAIL: $FAIL"
echo "=================================================="
[ "$FAIL" -eq 0 ] || exit 1

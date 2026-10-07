#!/usr/bin/env bash
# =============================================================================
# Repository hygiene checks — cheap invariants that keep CI and local builds
# deterministic:
#   1. gradlew is tracked with the executable bit (CI must not chmod it);
#   2. gradle wrapper jar + properties are present;
#   3. build outputs (build_out/) and local.properties are never tracked;
#   4. .gitignore actually covers the redirected build dir;
#   5. the tests/ suite required by CI exists.
# =============================================================================
set -uo pipefail

PASS=0
FAIL=0
ok()  { echo "PASS $1"; PASS=$((PASS + 1)); }
bad() { echo "FAIL $1"; FAIL=$((FAIL + 1)); }

# 1. gradlew executable bit in the git index
mode=$(git ls-files -s gradlew | awk '{print $1}')
if [ "$mode" = "100755" ]; then
    ok "gradlew tracked as executable ($mode)"
else
    bad "gradlew must be tracked as executable, got mode '$mode' (run: git update-index --chmod=+x gradlew)"
fi

# 2. wrapper present
for f in gradlew gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties; do
    if git ls-files --error-unmatch "$f" > /dev/null 2>&1; then
        ok "tracked:$f"
    else
        bad "missing from git: $f"
    fi
done

# 3. build outputs must never be tracked
tracked_bad=$(git ls-files -- 'build_out/**' '**/build/**' '*.apk' '*.aab' '*.jks' '*.keystore' 'local.properties')
if [ -z "$tracked_bad" ]; then
    ok "no build outputs / local.properties tracked"
else
    bad "tracked files that must never be committed:"
    echo "$tracked_bad"
fi

# 4. .gitignore covers the redirected build dir
if grep -qE '^/?build_out/' .gitignore; then
    ok ".gitignore covers build_out/"
else
    bad ".gitignore must ignore build_out/ (the project's redirected build dir)"
fi

# 5. CI test suite exists
for f in tests/assets_check.js tests/license_check.sh; do
    if [ -s "$f" ]; then
        ok "tests present:$f"
    else
        bad "missing test suite file: $f"
    fi
done

echo ""
echo "=============================================="
echo " repo-hygiene: PASS=$PASS FAIL=$FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ] || exit 1
exit 0

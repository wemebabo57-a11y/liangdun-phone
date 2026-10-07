#!/usr/bin/env bash
# =============================================================================
# Repository guard — fails if any signing material or pirated-verification
# CODE is tracked in the repository.
#
#   1. keystore / signing property files must never be committed;
#   2. keystore credential assignments must not appear in tracked sources;
#   3. built-in signature-verification / piracy-warning code must not exist.
#
# Precision measures (previous versions false-positived on documentation):
#   · `.github/**` is excluded — workflow/guard texts contain the literal
#     pattern words and would self-match;
#   · docs (`*.md`, `docs/**`) are excluded — they legitimately *describe*
#     the removed upstream components;
#   · comment content is blanked before matching (whole line for # // *,
#     and full <!-- --> / /* */ ranges), so "the original version used
#     SignatureVerifier (removed)" remarks inside code are fine while any
#     actual code reference fails the guard.
#
# NOTE on `set` flags: intentionally NO pipefail — `sed | grep -q` pipelines
# rely on grep's exit status; with pipefail, grep -q closing the pipe early
# gives sed a SIGPIPE (141) and the match is *lost* nondeterministically.
# =============================================================================
set -eu

fail=0

echo "== keystore / signing scripts =="
bad=$(git ls-files | grep -Ei '\.jks$|\.keystore$|\.jks\.[^/]+$|(^|/)signing\.(gradle|properties)$' || true)
if [ -n "$bad" ]; then
    echo "::error::signing material must never be committed"
    echo "$bad"
    fail=1
else
    echo "OK: no keystore/signing files tracked"
fi

# --- helpers ---------------------------------------------------------------
# code_files: tracked, non-.github, code-like text files
code_files() {
    git ls-files -z -- ':!/.github/**' \
        | xargs -0 -r grep -lI . -- 2>/dev/null \
        | grep -E '\.(java|kt|kts|gradle|xml|pro|properties|aidl|sh|cpp|c|h|hpp|cmake|txt)$|CMakeLists\.txt$' \
        || true
}

# strip_comments <file>: blank out comment content so only live code matches
strip_comments() {
    sed -e 's://.*$::' \
        -e 's:^[[:space:]]*[#*].*$::' \
        -e '/<!--/,/-->/d' \
        -e '/\/\*/,/\*\//d' \
        -e 's:/\*.*$::' \
        "$1" 2>/dev/null
}

echo "== keystore credential assignments in tracked sources =="
CRED_PAT="(storePassword|keyPassword|keyAlias)([[:space:]]*[=:][[:space:]]*|[[:space:]]+)['\"]|(storePassword|keyPassword|keyAlias)[[:space:]]*=[[:space:]]*[A-Za-z0-9_(]"
found_cred=""
for f in $(code_files); do
    if strip_comments "$f" | grep -qE "$CRED_PAT"; then
        found_cred="$found_cred $f"
    fi
done
if [ -n "$found_cred" ]; then
    echo "::error::keystore credentials found in tracked files:"
    echo "$found_cred" | tr ' ' '\n' | grep -v '^$'
    fail=1
else
    echo "OK: no keystore credentials in tracked sources"
fi

echo "== built-in signature verification code =="
VER_PAT='SignatureVerifier|PiracyWarningActivity'
found_ver=""
for f in $(code_files); do
    if strip_comments "$f" | grep -qE "$VER_PAT"; then
        found_ver="$found_ver $f"
    fi
done
if [ -n "$found_ver" ]; then
    echo "::error::built-in signature verification code must not be present:"
    echo "$found_ver" | tr ' ' '\n' | grep -v '^$'
    fail=1
else
    echo "OK: no signature-verification code"
fi

exit $fail

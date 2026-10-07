#!/usr/bin/env bash
# =============================================================================
# Deterministic Android SDK bootstrap for CI runners.
#
# Resolves the toolchain the project pins (AGP 8.13.2 / Gradle 8.14):
#
#   compileSdk        37            -> platform android-37.0 (upstream re-versioned;
#                                      legacy "platforms;android-37" no longer exists)
#   build tools       35.0.0        -> AGP 8.13.2's *default* build-tools. MUST be
#                                      pre-installed, see the note below.
#   NDK               29.0.13113456 -> pinned via app/build.gradle ndkVersion
#   CMake             3.22.1        -> pinned via externalNativeBuild.cmake.version
#
# NOTE — the mid-build auto-install bug (root cause of every previous red CI):
#   AGP 8.13.2 fails with
#       "Failed to find target with hash string 'android-37' in: $ANDROID_HOME"
#   whenever the SDK loader has to auto-download *anything* mid-configuration
#   (build-tools, platform, ...).  The in-memory target list is not refreshed
#   after the auto-install, so the platform hash lookup fails afterwards.
#   Therefore this script pre-installs EVERY component AGP could ask for:
#     * build-tools 35.0.0 (AGP default) and 36.0.0 (newer toolchain),
#     * the canonical platform android-37.0,
#     * an aliased copy "android-37" with patched metadata so BOTH resolution
#       paths work: the direct loader (package path platforms;android-37.0)
#       and the legacy hash lookup (target hash "android-37").
#
# Usage (from a workflow step):
#   bash .github/scripts/setup-android-sdk.sh
#   exports ANDROID_HOME / ANDROID_SDK_ROOT into GITHUB_ENV and puts
#   cmdline-tools on GITHUB_PATH for subsequent steps.
# =============================================================================
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
SDKMGR="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

log()  { echo "[sdk-setup] $*"; }

# --- 1. cmdline-tools --------------------------------------------------------
if [ ! -x "$SDKMGR" ]; then
    log "installing cmdline-tools into $ANDROID_HOME"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    curl -sSL --retry 3 \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
        -o /tmp/cmdtools.zip
    unzip -q -o /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -f /tmp/cmdtools.zip
else
    log "cmdline-tools already present"
fi

# --- 2. licenses -------------------------------------------------------------
yes | "$SDKMGR" --licenses > /dev/null 2>&1 || true

# --- 3. project toolchain ----------------------------------------------------
#  build-tools 35.0.0 : AGP 8.13.2 default — pre-installing it is REQUIRED,
#                       otherwise AGP auto-installs it mid-build and the
#                       platform target-hash resolution breaks (see header).
#  build-tools 36.0.0 : newest stable toolchain kept for completeness.
#  platforms;android-37.0 : the real API 37 platform (re-versioned upstream).
"$SDKMGR" --install \
    "platform-tools" \
    "platforms;android-37.0" \
    "build-tools;35.0.0" \
    "build-tools;36.0.0" \
    "ndk;29.0.13113456" \
    "cmake;3.22.1" > /tmp/sdkmanager-install.log 2>&1 || {
        tail -50 /tmp/sdkmanager-install.log
        echo "::error::sdkmanager failed to install the project toolchain"
        exit 1
    }

# --- 4. platform alias: android-37.0 -> android-37 ---------------------------
# The project pins compileSdk 37, which AGP resolves through BOTH:
#   (a) the canonical package path  "platforms;android-37.0" (direct loader), and
#   (b) the legacy platform hash     "android-37"             (SdkHandler target lookup).
# Keep the canonical install AND add an aliased copy with metadata patched to
# plain api level 37, so whichever path AGP takes, the platform is found.
PLAT_CANON="$ANDROID_HOME/platforms/android-37.0"
PLAT_ALIAS="$ANDROID_HOME/platforms/android-37"
if [ -d "$PLAT_CANON" ]; then
    rm -rf "$PLAT_ALIAS"
    cp -a "$PLAT_CANON" "$PLAT_ALIAS"
    sed -i 's/^AndroidVersion\.ApiLevel=37\.0$/AndroidVersion.ApiLevel=37/' \
        "$PLAT_ALIAS/source.properties"
    if [ -f "$PLAT_ALIAS/package.xml" ]; then
        sed -i -e 's|platforms;android-37\.0|platforms;android-37|g' \
               -e 's|<api-level>37\.0</api-level>|<api-level>37</api-level>|' \
               "$PLAT_ALIAS/package.xml"
    fi
    log "platform alias android-37 -> android-37.0 in place (both retained)"
else
    echo "::error::platforms/android-37.0 missing after install"
    exit 1
fi

# --- 5. expose to the workflow environment -----------------------------------
{
    echo "ANDROID_HOME=$ANDROID_HOME"
    echo "ANDROID_SDK_ROOT=$ANDROID_HOME"
} >> "$GITHUB_ENV"
echo "$ANDROID_HOME/cmdline-tools/latest/bin" >> "$GITHUB_PATH"

# --- 6. sanity report --------------------------------------------------------
log "installed components:"
"$SDKMGR" --list_installed 2>/dev/null | sed -n '/^Installed packages:/,$p' | head -30 || true
log "platforms dir: $(ls "$ANDROID_HOME/platforms" | tr '\n' ' ')"
log "SDK bootstrap complete."

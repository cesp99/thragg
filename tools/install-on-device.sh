#!/usr/bin/env bash
#
# Build Seeker IDE and put it on the phone.
#
#   tools/install-on-device.sh            # build, install, launch, screenshot
#   tools/install-on-device.sh --no-build # just reinstall what is already built
#   tools/install-on-device.sh --shot out.png
#
# Two things about this device are not the usual Android story, and both are
# handled here rather than left as folklore:
#
#   * The full edition targets SDK 28, because proot may only execute a
#     downloaded binary at that target — which is the whole reason the phone
#     can compile Solana programs at all. Android 14+ refuses to sideload a
#     target that old, and Play Protect says "Unsafe app blocked". The install
#     therefore needs --bypass-low-target-sdk-block.
#   * Only arm64-v8a matters. The Seeker has no 32-bit ABI at all, and the
#     Rust engine is by far the largest thing in the package, so building the
#     other ABIs doubles the wait for nothing.
#
# A build that touches core/ recompiles the Rust engine, which is slow
# (~10 min cold). A Kotlin-only change is about a minute.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk"
PKG=to.eyed.seeker.code
BUILD=1
SHOT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0; shift ;;
    --shot) SHOT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

adb get-state >/dev/null 2>&1 || { echo "no device; is it plugged in and authorised?" >&2; exit 1; }

if [ "$BUILD" = 1 ]; then
  say "building (arm64 only)"
  cd "$ROOT"
  ./gradlew assembleFullDebug -Pseeker.abis=arm64-v8a --console=plain
fi

[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }
say "installing $(du -h "$APK" | cut -f1)"

# Sideloading a debug build of a test-only APK, past the low-target-SDK block.
adb install -t -r --bypass-low-target-sdk-block "$APK"

say "launching"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

if [ -n "$SHOT" ]; then
  # Give the shell a moment to draw its first frame before capturing.
  sleep 5
  adb exec-out screencap -p > "$SHOT"
  say "screenshot: $SHOT"
fi

say "on the phone"

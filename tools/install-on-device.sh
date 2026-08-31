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
APK="$ROOT/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
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

# The phone drops off now and then — a long build is exactly when it happens,
# and failing the whole run for a blip that heals in two seconds is not worth
# it. Over wireless debugging it is not even a blip: the port changes whenever
# the phone re-advertises, so the saved endpoint goes stale and `adb connect`
# has to be pointed somewhere new. mDNS is what knows where that is, and a
# device that is already paired advertises _adb-tls-connect._tcp.
reconnect_wireless() {
  local endpoint
  endpoint=$(timeout 10 adb mdns services 2>/dev/null \
    | awk '$2 == "_adb-tls-connect._tcp" { print $3; exit }')
  [ -n "$endpoint" ] || return 1
  adb connect "$endpoint" >/dev/null 2>&1
}

wait_for_device() {
  local waited=0
  until adb get-state >/dev/null 2>&1; do
    [ "$waited" -ge 60 ] && {
      echo "no device after 60s." >&2
      echo "USB: plugged in and authorised? Wireless: is pairing still active" >&2
      echo "(Developer options > Wireless debugging)?" >&2
      exit 1
    }
    [ "$waited" = 0 ] && echo "waiting for the device..."
    # Retry discovery every 10s rather than every tick; mDNS is not free.
    [ $((waited % 10)) = 0 ] && reconnect_wireless
    sleep 2; waited=$((waited + 2))
  done
}
wait_for_device

if [ "$BUILD" = 1 ]; then
  say "building (arm64 only)"
  cd "$ROOT"
  ./gradlew assembleDebug -Pseeker.abis=arm64-v8a --console=plain
fi

[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }
wait_for_device
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

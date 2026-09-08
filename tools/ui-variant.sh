#!/usr/bin/env bash
#
# Build, install and seed a UI variant of Thragg beside the real app.
#
#   tools/ui-variant.sh <name>            # build (Kotlin only), install, seed data, launch
#   tools/ui-variant.sh <name> --no-build # reinstall what is built
#   tools/ui-variant.sh <name> --seed     # (re)copy the real app's data into the variant
#
# The variant is built with -Pthragg.variant=<name>, so it lands as
# to.eyed.thragg.<name> with its own data directory. That directory starts
# empty, which would leave the variant on the Setup gate, so the first install
# copies the real app's Debian, toolchain record, settings, sessions, chain
# state and projects (minus build outputs) across on the device itself with a
# run-as | run-as tar pipe. Everything in this script serialises on
# /tmp/ui/phone.lock because several variants share one phone.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
NAME="${1:?variant name}"; shift
BUILD=1; SEED=0
for a in "$@"; do case "$a" in --no-build) BUILD=0;; --seed) SEED=1; BUILD=0;; *) echo "unknown $a" >&2; exit 2;; esac; done
SRC=to.eyed.thragg; PKG="to.eyed.thragg.$NAME"
mkdir -p /tmp/ui
say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

if [ "$BUILD" = 1 ]; then
  say "building variant $NAME (arm64, Kotlin only)"
  [ -d "$ROOT/app/src/main/jniLibs/arm64-v8a" ] || cp -r /home/carlo/Desktop/Android/SeekerIDE/app/src/main/jniLibs "$ROOT/app/src/main/"
  ( cd "$ROOT" && ./gradlew assembleDebug -Pthragg.abis=arm64-v8a -Pthragg.variant="$NAME" -Pthragg.skipRust=true --console=plain )
fi
APK="$ROOT/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"

exec 9>/tmp/ui/phone.lock; flock 9
if [ "$SEED" = 0 ]; then
  say "installing $PKG"
  adb install -t -r --bypass-low-target-sdk-block "$APK"
fi
if ! adb shell "run-as $PKG test -d files/debian" 2>/dev/null; then SEED=1; fi
if [ "$SEED" = 1 ]; then
  say "seeding $PKG from $SRC (Debian is 3.9 GB; a few minutes)"
  adb shell am force-stop "$PKG"
  adb shell "run-as $SRC sh -c 'cd files && tar cf - settings.json solana-toolchain.json sessions chain themes icon_themes agent-lobby debian' | run-as $PKG sh -c 'mkdir -p files && cd files && tar xf -'"
  adb shell "run-as $SRC sh -c 'cd files && tar cf - --exclude=target --exclude=node_modules --exclude=.anchor projects' | run-as $PKG sh -c 'cd files && tar xf -'"
  # Build outputs are skipped above, but the program keypairs live under
  # target/deploy and a build without them regenerates the program id.
  adb shell "run-as $SRC sh -c 'cd files && tar cf - projects/*/target/deploy 2>/dev/null' | run-as $PKG sh -c 'cd files && tar xf -'" || true
  adb shell "run-as $PKG ls files"
fi
say "launching $PKG"
adb shell am start -n "$PKG/to.eyed.thragg.MainActivity" >/dev/null

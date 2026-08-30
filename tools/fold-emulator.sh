#!/usr/bin/env bash
# Create and boot a foldable emulator that stands in for the target device.
#
# The app is built for foldables, and almost every bug worth finding here has
# been a device bug: a Handler on the wrong thread, a display id, a fold that
# recreated the activity. A phone-shaped emulator finds none of them. This one
# has a hinge, two display regions and a keyboard, and it runs the full stack
# including the Debian userland under proot.
#
#   tools/fold-emulator.sh            # create if missing, then boot
#   tools/fold-emulator.sh create     # create (or recreate) the AVD only
#
# Once it is up:
#
#   adb -s emulator-5554 install -r app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk
#   adb -s emulator-5554 shell cmd device_state state 0   # fold
#   adb -s emulator-5554 shell cmd device_state state 3   # unfold
#   adb -s emulator-5554 exec-out screencap -p > shot.png
#
# Note the ABI: the emulator is x86_64, so install the x86_64 APK, not arm64.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
AVD_NAME="${AVD_NAME:-Fold_API_36}"
# API 36 with Google APIs: a plain "default" image has no keyboard layouts or
# input-method services worth testing against.
IMAGE="${IMAGE:-system-images;android-36;google_apis;x86_64}"
# "7.6in Fold-in with outer display" — the Galaxy Z Fold's shape, and the
# profile that carries a hinge sensor and fold postures.
DEVICE="${DEVICE:-7.6in Foldable}"

avdmanager="$SDK/cmdline-tools/latest/bin/avdmanager"
emulator="$SDK/emulator/emulator"

create() {
  if [ ! -d "$SDK/system-images/${IMAGE//;//}" ]; then
    echo "Missing system image. Install it with:" >&2
    echo "  $SDK/cmdline-tools/latest/bin/sdkmanager \"$IMAGE\"" >&2
    exit 1
  fi
  echo no | "$avdmanager" create avd -n "$AVD_NAME" -k "$IMAGE" -d "$DEVICE" --force

  # The defaults are too small for an IDE that holds a 35 MB engine, a Rust
  # worktree scan and a Debian rootfs: 1.5 GB of RAM and a 2 GB data partition
  # run out during `apt install`.
  local config="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
  python3 - "$config" <<'PY'
import sys
path = sys.argv[1]
cfg = {}
for line in open(path):
    if '=' in line:
        key, value = line.split('=', 1)
        cfg[key.strip()] = value.strip()
cfg.update({
    'hw.ramSize': '4096',
    'vm.heapSize': '512',
    # Without this the host keyboard is not delivered, and every keybinding
    # test has to go through `adb shell input keycombination` blind.
    'hw.keyboard': 'yes',
    'disk.dataPartition.size': '8G',
    'hw.gpu.enabled': 'yes',
    'hw.gpu.mode': 'swiftshader_indirect',
})
open(path, 'w').write('\n'.join(f'{k}={v}' for k, v in sorted(cfg.items())) + '\n')
PY
  echo "created $AVD_NAME"
}

case "${1:-boot}" in
  create) create ;;
  boot)
    [ -d "$HOME/.android/avd/$AVD_NAME.avd" ] || create
    exec "$emulator" -avd "$AVD_NAME" -no-snapshot-save -no-boot-anim \
      -gpu swiftshader_indirect -netdelay none -netspeed full
    ;;
  *) echo "usage: $0 [create|boot]" >&2; exit 2 ;;
esac

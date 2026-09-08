#!/usr/bin/env bash
#
# Drive one app on the shared phone and take screenshots, under the phone
# lock, so several variants under evaluation do not interleave their taps.
#
#   tools/ui-shot.sh <package> <outdir> <step>...
#
# Steps, executed in order:
#   start            launch the package's MainActivity (brings to front)
#   tap:X,Y          adb tap at phone pixels (screen is 1200x2670)
#   swipe:X1,Y1,X2,Y2[,ms]
#   key:NAME         adb keyevent (BACK, HOME, ENTER ...)
#   text:STR         adb input text (use %s for space)
#   wait:SECONDS     sleep
#   shot:NAME        screencap -> <outdir>/NAME.png (waits 1.2 s first)
#   hold:X,Y,MS,AT,NAME
#                    press at X,Y for MS ms and screencap at AT ms into the
#                    hold -> <outdir>/NAME.png (a frame of a held control)
#
# Every step sequence starts by bringing the package to the front, so a shot
# never captures another variant. BACK on a root destination leaves the app:
# prefer `start` to recover rather than pressing BACK blindly.
set -euo pipefail
PKG="${1:?package}"; OUT="${2:?outdir}"; shift 2
mkdir -p "$OUT" /tmp/ui
exec 9>/tmp/ui/phone.lock; flock 9
front() { adb shell am start -n "$PKG/to.eyed.thragg.MainActivity" >/dev/null 2>&1 || true; }
front; sleep 1
for step in "$@"; do
  case "$step" in
    start) front; sleep 2 ;;
    tap:*) IFS=, read -r x y <<<"${step#tap:}"; adb shell input tap "$x" "$y"; sleep 0.7 ;;
    swipe:*) IFS=, read -r a b c d ms <<<"${step#swipe:}"; adb shell input swipe "$a" "$b" "$c" "$d" "${ms:-300}"; sleep 0.7 ;;
    key:*) adb shell input keyevent "${step#key:}"; sleep 0.7 ;;
    text:*) adb shell input text "${step#text:}"; sleep 0.5 ;;
    wait:*) sleep "${step#wait:}" ;;
    shot:*) sleep 1.2; adb exec-out screencap -p > "$OUT/${step#shot:}.png"; echo "$OUT/${step#shot:}.png" ;;
    hold:*) IFS=, read -r x y ms at name <<<"${step#hold:}"
      adb shell input swipe "$x" "$y" "$x" "$y" "$ms" & hp=$!
      sleep "$(awk "BEGIN{print $at/1000}")"; adb exec-out screencap -p > "$OUT/$name.png"; echo "$OUT/$name.png"
      wait "$hp"; sleep 0.7 ;;
    *) echo "unknown step: $step" >&2; exit 2 ;;
  esac
done

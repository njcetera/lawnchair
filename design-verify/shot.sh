#!/usr/bin/env bash
# screencap + pull, with the two traps this device has already sprung:
#   * `exec-out screencap` corrupts the PNG here, so it goes via /sdcard and `pull`.
#   * the AVD has two physical displays and screencap silently defaults to "the first one found",
#     which is the 2076x2152 unfolded panel that is currently OFF. The powered display is chosen
#     explicitly and the pulled file's PNG header is checked against `wm size`.
# usage: shot.sh <serial> <dest.png>
set -euo pipefail
S="${1:?serial}"; DEST="${2:?dest}"
export MSYS_NO_PATHCONV=1
a() { adb -s "$S" "$@"; }
mkdir -p "$(dirname "$DEST")"
SIZE=$(a shell wm size | tr -d '\r' | awk '{print $NF}')
WW=${SIZE%x*}; HH=${SIZE#*x}
DISP=$(a shell dumpsys display | awk '/DisplayDeviceInfo[{]/ && / state ON,/ { if (match($0, /uniqueId="local:[0-9]+"/)) { u = substr($0, RSTART, RLENGTH); gsub(/[^0-9]/, "", u); print u; exit } }')
[ -n "$DISP" ] || { echo "could not find a powered display" >&2; exit 2; }
a shell screencap -d "$DISP" -p /sdcard/vrfy_shot.png
adb -s "$S" pull /sdcard/vrfy_shot.png "$(cygpath -w "$DEST")" >/dev/null 2>&1
a shell rm -f /sdcard/vrfy_shot.png
# PNG IHDR: 8-byte signature, 4-byte length, "IHDR", then big-endian width and height.
GW=$(od -An -tu4 -j16 -N4 --endian=big "$DEST" | tr -d ' ')
GH=$(od -An -tu4 -j20 -N4 --endian=big "$DEST" | tr -d ' ')
echo "shot $DEST ${GW}x${GH} (display $DISP, wm size ${WW}x${HH})"
[ "$GW" = "$WW" ] && [ "$GH" = "$HH" ] || { echo "!! screenshot is not the powered display" >&2; exit 3; }

#!/usr/bin/env bash
# Install this worktree's APK and PROVE, from the dex of the file the device is actually running,
# which source state it was built from.
#
# versionName comes from `git describe` and is byte-identical across the fixed and the
# deliberately-broken builds, and the shared Gradle cache has repackaged another tree's classes
# while reporting "up-to-date". So the check is: pull back what `pm path` points at, dexdump every
# classesN.dex, and require the state's own marker method to be present and the other three
# states' markers to be absent.
#
# usage: install.sh <serial> <fixed|broken-w1|broken-s12|broken-both>
set -euo pipefail
S="${1:?serial}"; STATE="${2:?state}"
P=app.lawnchair.debug
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEXDUMP="${DEXDUMP:-/c/Users/njcet/AppData/Local/Android/Sdk/build-tools/37.0.0/dexdump.exe}"
APK=$(ls -t "$ROOT"/build/outputs/apk/lawnWithQuickstepGithub/debug/*.apk | head -1)
TAG="$(echo "$STATE" | tr 'a-z-' 'A-Z_')"
WANT="vrfyState${TAG}"
export MSYS_NO_PATHCONV=1
a() { adb -s "$S" "$@"; }

echo "-- installing $(basename "$APK")"
a install -r -t "$(cygpath -w "$APK")" | tail -2

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
REMOTE=$(a shell pm path $P | tr -d '\r' | head -1 | sed 's/^package://')
echo "-- installed path: $REMOTE"
a pull "$REMOTE" "$(cygpath -w "$W")\on-device.apk" >/dev/null
unzip -o -q "$W/on-device.apk" 'classes*.dex' -d "$W"
FOUND=""
for d in "$W"/classes*.dex; do
  hits=$("$DEXDUMP" "$(cygpath -w "$d")" 2>/dev/null | grep -o "vrfyState[A-Z0-9_]*" | sort -u || true)
  [ -n "$hits" ] && FOUND="$FOUND $hits($(basename "$d"))"
done
echo "-- markers in the ON-DEVICE dex:${FOUND:- NONE}"
case "$FOUND" in
  *"$WANT"*) ;;
  *) echo "!! WRONG BUILD ON DEVICE: expected $WANT"; exit 9 ;;
esac
for other in FIXED BROKEN_W1 BROKEN_S12 BROKEN_BOTH; do
  [ "$other" = "$TAG" ] && continue
  case "$FOUND" in
    *"vrfyState${other} "*|*"vrfyState${other}("*) echo "!! stale marker vrfyState$other also present"; exit 9 ;;
  esac
done
echo "OK: device is running state=$STATE ($WANT)"

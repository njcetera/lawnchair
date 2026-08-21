#!/usr/bin/env bash
# Independent verification of W1 and S12 -- puts the worktree into one of four source states and
# stamps a matching, uniquely-named marker method so `dexdump` can prove which APK is installed.
#
#   fixed        both fixes present (HEAD)
#   broken-w1    AresHomeAdapter.releaseForRemoval short-circuits  (W1 falsification)
#   broken-s12   AresEditWiggle.start calls reset() again on the animators-off path (S12 falsif.)
#   broken-both  both of the above
#
# Idempotent: always resets the two files from git first.
set -euo pipefail
STATE="${1:?usage: set-state.sh fixed|broken-w1|broken-s12|broken-both}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
A="$ROOT/lawnchair/src/app/lawnchair/areslauncher/AresHomeAdapter.kt"
W="$ROOT/lawnchair/src/app/lawnchair/areslauncher/AresEditWiggle.kt"
M="$ROOT/lawnchair/src/app/lawnchair/areslauncher/AresVerifyMarker.kt"

git -C "$ROOT" checkout -- "$A" "$W"

w1=0; s12=0
case "$STATE" in
  fixed)       ;;
  broken-w1)   w1=1 ;;
  broken-s12)  s12=1 ;;
  broken-both) w1=1; s12=1 ;;
  *) echo "unknown state $STATE" >&2; exit 2 ;;
esac

if [ "$w1" = 1 ]; then
  perl -0pi -e 's/(    private fun releaseForRemoval\(position: Int\) \{\n)/$1        if (true) return \/\/ VERIFIER: W1 fix disabled\n/' "$A"
  grep -q "VERIFIER: W1 fix disabled" "$A" || { echo "W1 patch did not apply" >&2; exit 3; }
fi
if [ "$s12" = 1 ]; then
  perl -0pi -e 's/\n            clearFloat\(view\)\n            return null\n/\n            reset(view) \/\/ VERIFIER: S12 fix disabled\n            return null\n/' "$W"
  grep -q "VERIFIER: S12 fix disabled" "$W" || { echo "S12 patch did not apply" >&2; exit 3; }
fi

# Marker: uppercase, no dashes, so `dexdump | grep` cannot half-match another state.
TAG="$(echo "$STATE" | tr 'a-z-' 'A-Z_')"
perl -pi -e "s/fun vrfyState[A-Z0-9_]+\(\)/fun vrfyState${TAG}()/" "$M"
grep -n "fun vrfyState" "$M"
echo "state=$STATE"
git -C "$ROOT" --no-pager diff --stat -- "$A" "$W"

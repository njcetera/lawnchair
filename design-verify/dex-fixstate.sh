#!/usr/bin/env bash
# Reads the FIXES THEMSELVES out of the dex of the APK the device is running.
#
# The build marker only proves which AresVerifyMarker.kt was compiled. Gradle reported "6 from
# cache" on the restore build, and the shared cache has repackaged another tree's classes while
# claiming up-to-date, so in principle the marker and the two fixes could disagree. This
# disassembles the two methods the fixes live in and prints what they actually call:
#
#   AresEditWiggle.start              fixed  -> clearFloat on the animators-off branch
#                                     broken -> reset (which funnels into AresEditMotion.clear)
#   AresHomeAdapter.releaseForRemoval fixed  -> findViewHolderForAdapterPosition + setIsRecyclable
#                                     broken -> return-void on entry, no calls at all
#
# usage: dex-fixstate.sh <serial>     (pulls the installed APK)
#        dex-fixstate.sh -f <apk>
set -euo pipefail
DEXDUMP="${DEXDUMP:-/c/Users/njcet/AppData/Local/Android/Sdk/build-tools/37.0.0/dexdump.exe}"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
if [ "${1:-}" = "-f" ]; then
  APK="${2:?apk}"
else
  S="${1:?serial or -f <apk>}"
  R=$(MSYS_NO_PATHCONV=1 adb -s "$S" shell pm path app.lawnchair.debug | tr -d '\r' | head -1 | sed 's/^package://')
  echo "-- on-device apk: $R"
  MSYS_NO_PATHCONV=1 adb -s "$S" pull "$R" "$(cygpath -w "$W")\pulled.apk" >/dev/null 2>&1
  APK="$W/pulled.apk"
fi
unzip -o -q "$APK" 'classes*.dex' -d "$W"
for d in "$W"/classes*.dex; do
  "$DEXDUMP" -d "$(cygpath -w "$d")" 2>/dev/null > "$W/dis.txt"
  grep -q "app.lawnchair.areslauncher.AresHomeAdapter.releaseForRemoval" "$W/dis.txt" || continue
  echo "-- fix bodies found in $(basename "$d")"
  awk '
    /name          : .(start|releaseForRemoval).$/ { m = $0; sub(/.*: /, "", m); gsub(/\x27/, "", m); on = 1; keep = ""; next }
    on && /name          : / { on = 0 }
    on && /insns size/ { size = $4 }
    on && /invoke-|return-void|return-object/ {
      c = $0; sub(/^.*\| *[0-9a-f]+: /, "", c); sub(/ \/\/ .*/, "", c)
      if (c ~ /clearFloat|AresEditWiggle;\.reset|AresEditMotion|findViewHolderForAdapterPosition|setIsRecyclable|isRecyclable|areAnimatorsEnabled|getItemViewType/) keep = keep "\n        " c
      else if (c ~ /^return/ && keep == "") keep = keep "\n        " c "   <-- FIRST INSTRUCTION IS A RETURN"
    }
    on && /catches/ && m != "" { if (keep != "") printf("   %s (insns %s)%s\n", m, size, keep); m = ""; keep = "" }
  ' "$W/dis.txt" | grep -A9 -E "^   (start|releaseForRemoval)"
  break
done

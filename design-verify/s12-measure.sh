#!/usr/bin/env bash
# S12 independent measurement: where a folder icon is DRAWN inside its cell, with animators off.
#
# Two observables, neither of them the author's:
#   1. PIXELS. dumpsys reports mLeft/mTop, which the §26 lift never touches (it goes through
#      MultiTranslateDelegate). So the cell's LAYOUT rect comes from dumpsys and the icon's DRAWN
#      rect comes from the screenshot; the gap between them is the lift, measured in the one place
#      it actually exists.
#   2. A one-line probe of the framework's own View.getTranslationY() (AresVerifyProbe), logged
#      from the folder edit sync. That is the composed value the view draws with, not any Ares
#      bookkeeping of what the lift is believed to be.
#
# usage: s12-measure.sh <serial> <label> <animator_duration_scale>
set -euo pipefail
S="${1:?serial}"; LABEL="${2:?label}"; SCALE="${3:?scale}"
P=app.lawnchair.debug
D="$(cd "$(dirname "$0")" && pwd)"
OUT="${VRFY_OUT:-/tmp/vrfy}/$LABEL"
mkdir -p "$OUT"
export MSYS_NO_PATHCONV=1
a() { adb -s "$S" "$@"; }

echo "### $LABEL  animator_duration_scale=$SCALE"
a shell settings put global animator_duration_scale "$SCALE"
a shell am force-stop $P >/dev/null
a shell input keyevent KEYCODE_HOME >/dev/null
sleep 7

a shell dumpsys activity top > "$OUT/home.txt" 2>&1
bash "$D/homelist.sh" "$OUT/home.txt" > "$OUT/home.tsv"
FOLDER=$(awk -F'\t' '$8==1 {print; exit}' "$OUT/home.tsv")
[ -n "$FOLDER" ] || { echo "!! no folder tile on the grid -- fixture missing"; exit 4; }
FB=$(echo "$FOLDER" | cut -f3)
fx=$(echo "$FB" | awk -F'[,-]' '{printf "%d", ($1+$3)/2}')
fy=$(echo "$FB" | awk -F'[,-]' '{printf "%d", ($2+$4)/2}')
echo "-- folder tile $FB"

# Grid edit mode first: tapping a folder from inside edit mode is the path that attaches the
# folder edit chrome (AresFolderEdit.attach).
HOLD=$(awk -F'\t' '$5==0 && $8==0 {print $3; exit}' "$OUT/home.tsv")
hx=$(echo "$HOLD" | awk -F'[,-]' '{printf "%d", ($1+$3)/2}')
hy=$(echo "$HOLD" | awk -F'[,-]' '{printf "%d", ($2+$4)/2}')
echo "-- long-press icon tile at $hx,$hy to arm edit mode"
a shell input swipe $hx $hy $hx $hy 800 >/dev/null
sleep 3
a shell dumpsys activity top > "$OUT/edit.txt" 2>&1
bash "$D/homelist.sh" "$OUT/edit.txt" > "$OUT/edit.tsv"
BADGES=$(awk -F'\t' '$7!="" {n++} END {print n+0}' "$OUT/edit.tsv")
echo "-- tiles carrying a visible x badge: $BADGES (0 means edit mode did not arm)"
[ "$BADGES" -gt 0 ] || { echo "!! edit mode did not arm"; exit 5; }

a logcat -c >/dev/null 2>&1 || true
echo "-- tap folder at $fx,$fy"
a shell input tap $fx $fy >/dev/null
sleep 5

a shell dumpsys activity top > "$OUT/folder.txt" 2>&1
a logcat -d -s VRFY_S12:I > "$OUT/probe.log" 2>&1 || true
bash "$D/shot.sh" "$S" "$OUT/shot.png"
echo "-- folder open? $(grep -c 'launcher3.folder.Folder{' "$OUT/folder.txt") Folder view(s) in dump"
bash "$D/folder-cells.sh" "$OUT/folder.txt" > "$OUT/cells.tsv"
cat "$OUT/cells.tsv" | sed 's/^/   /'
NC=$(grep -vc '^#' "$OUT/cells.tsv" || true)
[ "${NC:-0}" -gt 0 ] || { echo "!! folder is not open / has no icons"; exit 6; }

echo "-- drawn position vs layout position, per cell"
echo "   cell  layoutRect                 layoutCentreY  inkCentroidY  drawnMinusLayout"
while IFS=$'\t' read -r idx hash rect cy; do
  case "$idx" in \#*) continue;; esac
  L=${rect%%,*}; rest=${rect#*,}; T=${rest%%-*}; rest2=${rect#*-}; R=${rest2%%,*}; B=${rest2#*,}
  line=$(pwsh -NoProfile -File "$(cygpath -w "$D/rowink.ps1")" -Png "$(cygpath -w "$OUT/shot.png")" -L "$L" -T "$T" -R "$R" -B "$B")
  ink=$(echo "$line" | grep -o 'centroidY=[-0-9.]*' | cut -d= -f2)
  d=$(awk -v a="$ink" -v b="$cy" 'BEGIN{printf "%+.2f", a-b}')
  printf "   %-5s %-26s %-14s %-13s %s\n" "$idx" "$rect" "$cy" "$ink" "$d"
  echo "      $line" >> "$OUT/ink.txt"
done < "$OUT/cells.tsv"

echo "-- probe lines: $(grep -c 'idx=' "$OUT/probe.log" || true)"
grep 'idx=' "$OUT/probe.log" | tail -9 | sed 's/^.*VRFY_S12: /   /'

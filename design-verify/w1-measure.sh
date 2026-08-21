#!/usr/bin/env bash
# W1 independent measurement: delete one tile from the home grid and ask whether its VIEW is still
# a child of AresHomeListView afterwards.
#
# The observable is IDENTITY, not a count: the tile's `System.identityHashCode` is read out of
# `dumpsys activity top` before the delete and looked for again after it. That sidesteps the trap
# the ledger records against counting -- removing a tall widget lets more tiles fit, so the number
# of attached children legitimately RISES. A specific view that outlives its row cannot be
# explained away like that. Pairwise bounds overlap is carried alongside as a second, equally
# count-free signal.
#
# usage: w1-measure.sh <serial> <label> <widget|icon>
set -euo pipefail
S="${1:?serial}"; LABEL="${2:?label}"; KIND="${3:?widget|icon}"
P=app.lawnchair.debug
D="$(cd "$(dirname "$0")" && pwd)"
OUT="${VRFY_OUT:-/tmp/vrfy}/$LABEL"
mkdir -p "$OUT"
export MSYS_NO_PATHCONV=1
a() { adb -s "$S" "$@"; }

DB=$(a shell run-as $P ls databases 2>/dev/null | tr -d '\r' | grep -m1 '^launcher.*\.db$')
rows() { a shell run-as $P sqlite3 "databases/$DB" "'select count(*) from favorites where container=-100;'" 2>/dev/null | tr -d '\r'; }

echo "### $LABEL  kind=$KIND  db=$DB"
# A ghost survives everything short of activity recreation, so every run starts from a fresh
# process -- otherwise a leftover from the previous run is attributed to this one.
a shell am force-stop $P >/dev/null
a shell input keyevent KEYCODE_HOME >/dev/null
sleep 7

a shell dumpsys activity top > "$OUT/before.txt" 2>&1
bash "$D/homelist.sh" "$OUT/before.txt" > "$OUT/before.tsv"
R0=$(rows)
echo "-- before: rows(container=-100)=$R0"
bash "$D/overlaps.sh" "$OUT/before.tsv" | sed 's/^/   /'

# Enter edit mode with a long press on a tile. A bare long press does not start a drag in this
# fork, so a same-point swipe is a hold, not a gesture.
HOLD=$(awk -F'\t' '$5==0 {print $3; exit}' "$OUT/before.tsv")
hx=$(echo "$HOLD" | awk -F'[,-]' '{printf "%d", ($1+$3)/2}')
hy=$(echo "$HOLD" | awk -F'[,-]' '{printf "%d", ($2+$4)/2}')
echo "-- long-press tile at $hx,$hy"
a shell input swipe $hx $hy $hx $hy 800 >/dev/null
sleep 3

a shell dumpsys activity top > "$OUT/edit.txt" 2>&1
bash "$D/homelist.sh" "$OUT/edit.txt" > "$OUT/edit.tsv"
WANT=$([ "$KIND" = widget ] && echo 1 || echo 0)
TARGET=$(awk -F'\t' -v w="$WANT" '$5==w && $7!="" {print; exit}' "$OUT/edit.tsv")
[ -n "$TARGET" ] || { echo "!! no $KIND tile with a visible badge in edit mode -- edit mode did not arm"; exit 4; }
TH=$(echo "$TARGET" | cut -f2); TB=$(echo "$TARGET" | cut -f3); TBADGE=$(echo "$TARGET" | cut -f7)
bx=$(echo "$TBADGE" | awk -F'[,-]' '{printf "%d", ($1+$3)/2}')
by=$(echo "$TBADGE" | awk -F'[,-]' '{printf "%d", ($2+$4)/2}')
echo "-- target $KIND hash=$TH bounds=$TB badge=$TBADGE -> tap $bx,$by"
echo "-- edit-mode children: $(grep -vc '^#' "$OUT/edit.tsv")"

a shell input tap $bx $by >/dev/null
sleep 5

a shell dumpsys activity top > "$OUT/after.txt" 2>&1
bash "$D/homelist.sh" "$OUT/after.txt" > "$OUT/after.tsv"
R1=$(rows)
echo "-- after: rows(container=-100)=$R1  (was $R0)"
bash "$D/overlaps.sh" "$OUT/after.tsv" | sed 's/^/   /'

STILL=$(awk -F'\t' -v h="$TH" '$2==h {print $0}' "$OUT/after.tsv")
if [ -n "$STILL" ]; then
  echo "RESULT $LABEL: GHOST -- deleted tile's view is STILL a child of AresHomeListView"
  echo "   $STILL"
else
  echo "RESULT $LABEL: CLEAN -- deleted tile's view ($TH) is gone from the children"
fi
if [ "$R1" = "$R0" ]; then echo "   !! db row count did not change ($R0) -- the tap may not have deleted anything"; fi

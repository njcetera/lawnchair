#!/usr/bin/env bash
# The LAYOUT rect of every icon inside the open folder, absolute, from `dumpsys activity top`.
#
# This is the half of the S12 measurement that dumpsys can give: mLeft/mTop, which §26's lift
# never touches because the lift goes through MultiTranslateDelegate. The other half -- where the
# icon is actually DRAWN -- has to come from pixels. The gap between the two is the lift.
#
# TSV: idx  hash  absL,absT-absR,absB  centreY
set -euo pipefail
F="${1:?usage: folder-cells.sh <dumpsys-activity-top-file>}"
awk '
function ind(s) { if (match(s, /[^ ]/)) return RSTART-1; return -1 }
{
  if ($0 !~ /^ *[A-Za-z][A-Za-z0-9_.$]*\{[0-9a-f]+ /) next
  i = ind($0)
  cls = $1; sub(/\{.*/, "", cls)
  h = ""; if (match($0, /\{[0-9a-f]+/)) h = substr($0, RSTART+1, RLENGTH-1)
  if (!match($0, /-?[0-9]+,-?[0-9]+--?[0-9]+,-?[0-9]+/)) next
  split(substr($0, RSTART, RLENGTH), p, /[,-]/); l=p[1]; t=p[2]; r=p[3]; bo=p[4]
  px = (i >= 2 && (i-2) in ax) ? ax[i-2] : 0
  py = (i >= 2 && (i-2) in ay) ? ay[i-2] : 0
  ax[i] = px + l; ay[i] = py + t

  if (!infolder && cls ~ /folder\.Folder$/) { infolder = 1; fbase = i; next }
  if (!infolder) next
  if (i <= fbase) { infolder = 0; insw = 0; next }
  if (!insw && cls ~ /ShortcutAndWidgetContainer$/) {
    insw = 1; swbase = i
    printf("# folder container hash=%s origin=%d,%d\n", h, ax[i], ay[i])
    next
  }
  if (!insw) next
  if (i <= swbase) { insw = 0; next }
  if (i == swbase + 2 && cls ~ /BubbleTextView$/) {
    k++
    printf("%d\t%s\t%d,%d-%d,%d\t%.1f\n", k, h, ax[i], ay[i], ax[i]+(r-l), ay[i]+(bo-t),
           ay[i] + (bo-t)/2.0)
  }
}
' "$F"

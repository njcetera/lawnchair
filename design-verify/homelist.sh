#!/usr/bin/env bash
# Independent W1 observable: the DIRECT CHILDREN of every AresHomeListView, straight out of
# `dumpsys activity top`, in ABSOLUTE screen coordinates.
#
# Why dumpsys and not RecyclerView's own counters: `attachViewToParent(..., hidden = true)` adds
# the view to the parent's mChildren array, so a hidden animating view IS a real child and the
# framework's own hierarchy dump reports it. `LayoutManager.getChildCount()` subtracts hidden
# views, which is exactly why the ghost is invisible to the layout manager -- and why anything
# reading it can miss the defect.
#
# TSV per direct child:
#   idx  hash  absL,absT-absR,absB  class  widget(0|1)  vis  badgeAbs  folder(0|1)
# `widget=1` means a *HostView is somewhere in that child's subtree; `folder=1` a FolderIcon.
# `badge` is the top-start ImageView inside the tile -- AresRemoveBadge's x -- absent outside
# edit mode.
#
# Absolute coordinates come from an indent-keyed ancestor stack over the WHOLE dump: dumpsys
# prints mLeft,mTop relative to the parent. A child is only a child when its indent is exactly
# parent+2 -- the ledger records an earlier indentation walk producing a false count.
set -euo pipefail
F="${1:?usage: homelist.sh <dumpsys-activity-top-file>}"
awk '
function ind(s) { if (match(s, /[^ ]/)) return RSTART-1; return -1 }
{
  if ($0 !~ /^ *[A-Za-z][A-Za-z0-9_.$]*\{[0-9a-f]+ /) next
  i = ind($0)
  cls = $1; sub(/\{.*/, "", cls)
  h = ""; if (match($0, /\{[0-9a-f]+/)) h = substr($0, RSTART+1, RLENGTH-1)
  vis = substr($2, 1, 1)
  if (!match($0, /-?[0-9]+,-?[0-9]+--?[0-9]+,-?[0-9]+/)) next
  b = substr($0, RSTART, RLENGTH)
  split(b, p, /[,-]/); l = p[1]; t = p[2]; r = p[3]; bo = p[4]
  px = (i >= 2 && (i-2) in ax) ? ax[i-2] : 0
  py = (i >= 2 && (i-2) in ay) ? ay[i-2] : 0
  ax[i] = px + l; ay[i] = py + t

  if (cls ~ /AresHomeListView$/) {
    listn++; base = i; inlist = 1; k = 0; cur = 0
    printf("# list %d hash=%s origin=%d,%d\n", listn, h, ax[i], ay[i])
    next
  }
  if (!inlist) next
  if (i <= base) { inlist = 0; next }
  if (i == base + 2) {
    cur = ++k
    hash[cur] = h; klass[cur] = cls; wid[cur] = 0; fld[cur] = 0
    vv[cur] = vis; badge[cur] = ""
    L[cur] = ax[i]; T[cur] = ay[i]; R[cur] = ax[i] + (r - l); Bo[cur] = ay[i] + (bo - t)
    next
  }
  if (cur > 0 && $0 ~ /HostView[{]/) wid[cur] = 1
  if (cur > 0 && $0 ~ /FolderIcon[{]/) fld[cur] = 1
  if (cur > 0 && i == base + 4 && cls ~ /ImageView$/ && l < 200 && t < 200 && badge[cur] == "")
    badge[cur] = sprintf("%d,%d-%d,%d", ax[i], ay[i], ax[i] + (r-l), ay[i] + (bo-t))
}
END {
  fmt = "%d\t%s\t%d,%d-%d,%d\t%s\t%d\t%s\t%s\t%d\n"
  for (n = 1; n <= k; n++)
    printf(fmt, n, hash[n], L[n], T[n], R[n], Bo[n], klass[n], wid[n], vv[n], badge[n], fld[n]+0)
}
' "$F"

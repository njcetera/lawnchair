#!/usr/bin/env bash
# Direct children of the first view matching <anchor-regex>, from `dumpsys activity top`, in
# absolute screen coordinates. Same indent-stack walk as homelist.sh; kept separate so the W1
# tile columns do not have to mean something else for the folder.
#
# TSV: idx  hash  absL,absT-absR,absB  class  vis  text
set -euo pipefail
F="${1:?usage: subtree.sh <dump> <anchor-regex> [occurrence]}"
ANCHOR="${2:?anchor regex}"
OCC="${3:-1}"
awk -v anchor="$ANCHOR" -v occ="$OCC" '
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

  if (!found && $0 ~ anchor) {
    seen++
    if (seen == occ) {
      found = 1; base = i
      printf("# anchor %s hash=%s origin=%d,%d size=%dx%d\n", cls, h, ax[i], ay[i], r-l, bo-t)
      next
    }
  }
  if (!found || done) next
  if (i <= base) { done = 1; next }
  if (i == base + 2) {
    k++
    printf("%d\t%s\t%d,%d-%d,%d\t%s\t%s\n", k, h, ax[i], ay[i], ax[i]+(r-l), ay[i]+(bo-t), cls, vis)
  }
}
' "$F"

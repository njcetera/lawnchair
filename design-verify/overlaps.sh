#!/usr/bin/env bash
# Independent W1 observable #2: pairwise bounds overlap among the direct children of
# AresHomeListView.
#
# Viewport-independent, unlike any absolute child count. AresMasonryLayoutManager packs tiles so
# they never intersect; a ghost keeps the bounds it had when its row was deleted and is never laid
# out again, so it lands ON TOP OF whatever now occupies that area. The ledger's original W1
# evidence is exactly this -- a 520x482 tile with two 1x1 tiles drawn inside it.
set -euo pipefail
F="${1:?usage: overlaps.sh <homelist.sh output>}"
awk -F'\t' '
/^#/ { next }
{
  n++; id[n]=$2; b[n]=$3; w[n]=$5
  split($3, p, /[,-]/)
  # bounds are l,t-r,b and all coordinates here are non-negative
  L[n]=p[1]; T[n]=p[2]; R[n]=p[3]; B[n]=p[4]
}
END {
  c = 0
  for (i = 1; i <= n; i++) for (j = i+1; j <= n; j++) {
    ox = (R[i] < R[j] ? R[i] : R[j]) - (L[i] > L[j] ? L[i] : L[j])
    oy = (B[i] < B[j] ? B[i] : B[j]) - (T[i] > T[j] ? T[i] : T[j])
    if (ox > 0 && oy > 0) {
      c++
      printf("OVERLAP %dx%d  %s %s (widget=%s)  vs  %s %s (widget=%s)\n",
             ox, oy, id[i], b[i], w[i], id[j], b[j], w[j])
    }
  }
  printf("children=%d overlaps=%d\n", n, c)
}
' "$F"

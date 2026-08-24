package app.lawnchair.areslauncher

/**
 * Greedy first-fit packer for the masonry home grid.
 *
 * This is the whole layout model, and it is deliberately a **pure function with no Android
 * dependency**: `(ordered footprints, column count) -> positions`. See
 * design/requirements-alignment.md §4 and design/scrolling-grid-home.md.
 *
 * Windows Phone Start semantics fall out of one rule -- walk the items in order and place each at
 * the first position its footprint fits, scanning top-to-bottom then left-to-right:
 *
 *  - **No holes.** A hole can only exist if nothing later fits it, and anything that fits is placed
 *    there by construction.
 *  - **Removal compacts upward.** Remove an item, re-run over the shorter list, everything after it
 *    slides into the gap.
 *  - **Insertion pushes down.** Insert mid-sequence and subsequent items repack after it.
 *  - **Height stays proportional to item count**, which was the point: no arrangement of items can
 *    produce a tall sparse grid, so scrolling can never run away.
 *
 * Position is *derived*, never stored. The persisted state is `rank` (the order) plus each item's
 * `(spanX, spanY)` -- there are no x/y coordinates in the database. That is what makes this
 * testable, and it is also why the occupancy-collision class of bug (which once deleted every
 * desktop item -- see component-verification-3.md §2) cannot occur here: a list cannot collide with
 * itself.
 */
object AresPacker {

    /** A footprint in grid cells. Both dimensions are clamped to at least 1 by [pack]. */
    data class Span(val w: Int, val h: Int)

    /** Top-left cell coordinate of a placed item. */
    data class Cell(val x: Int, val y: Int)

    /**
     * Result of a pack: one [Cell] per input span (same order, same size), plus the total number of
     * grid rows consumed, which is what the layout manager needs for its scroll range.
     */
    data class Layout(val cells: List<Cell>, val rows: Int)

    /**
     * Packs [spans] into [columns] columns by greedy first-fit.
     *
     * An item wider than the grid is clamped to the full width rather than dropped -- a widget
     * declaring more columns than the device has would otherwise vanish silently.
     *
     * [reservedRun] (WP folders Phase 3 #3): a contiguous index range that must be kept together as
     * one visual block -- an inline-expanded folder tile plus its spliced children. Without it, the
     * greedy backfill can pull a child up into an earlier hole (left by a wide widget), stranding it
     * far from the folder it belongs to. When the run is given AND every member is a 1x1 footprint
     * (which folder icons and app icons always are), the whole run is placed as one contiguous
     * row-major block at the first position where all of its cells are free together -- so the
     * children always flow immediately after the folder. A run with any non-1x1 member, or an
     * out-of-range range, is ignored and those items pack individually (safe fallback, never a
     * crash). Items outside the run pack first-fit exactly as before, so a no-run call is
     * byte-for-byte the old behaviour.
     *
     * The self-non-collision guarantee is preserved: the block only ever occupies cells it has
     * checked free, so a list still cannot collide with itself (see the class KDoc / row-34).
     */
    @JvmOverloads
    fun pack(spans: List<Span>, columns: Int, reservedRun: IntRange? = null): Layout {
        if (columns <= 0 || spans.isEmpty()) return Layout(emptyList(), 0)

        // Occupancy grid, grown a row at a time as items are placed. Row-major: occupied[y][x].
        val occupied = ArrayList<BooleanArray>()
        val cells = arrayOfNulls<Cell>(spans.size)
        var rows = 0

        fun rowAt(y: Int): BooleanArray {
            while (occupied.size <= y) occupied.add(BooleanArray(columns))
            return occupied[y]
        }

        fun fits(x: Int, y: Int, w: Int, h: Int): Boolean {
            for (dy in 0 until h) {
                val row = rowAt(y + dy)
                for (dx in 0 until w) {
                    if (row[x + dx]) return false
                }
            }
            return true
        }

        fun place(pos: Int, span: Span) {
            val w = span.w.coerceIn(1, columns)
            val h = span.h.coerceAtLeast(1)
            // Scan top-to-bottom, then left-to-right within each row: the first position that fits
            // wins. The scan is bounded because a fresh row is always empty, so an item can never
            // fail to place.
            var placed: Cell? = null
            var y = 0
            while (placed == null) {
                for (x in 0..(columns - w)) {
                    if (fits(x, y, w, h)) {
                        placed = Cell(x, y)
                        break
                    }
                }
                if (placed == null) y++
            }
            for (dy in 0 until h) {
                val row = rowAt(placed.y + dy)
                for (dx in 0 until w) row[placed.x + dx] = true
            }
            cells[pos] = placed
            rows = maxOf(rows, placed.y + h)
        }

        // Is the reserved run usable? It must be in range and every member a 1x1 footprint -- the
        // contiguous-block placement below reasons in single cells, so a wider member would be
        // mis-placed. Anything else falls through to per-item first-fit.
        val runUsable = reservedRun != null && !reservedRun.isEmpty() &&
            reservedRun.first >= 0 && reservedRun.last < spans.size &&
            reservedRun.all { spans[it].w.coerceIn(1, columns) == 1 && spans[it].h.coerceAtLeast(1) == 1 }

        var i = 0
        while (i < spans.size) {
            if (runUsable && i == reservedRun!!.first) {
                val n = reservedRun.last - reservedRun.first + 1
                // First row-major start index whose n consecutive cells are ALL free. Fresh rows are
                // empty, so such a window always exists at or before the frontier -- terminates.
                var start = 0
                while (true) {
                    var ok = true
                    for (k in 0 until n) {
                        val idx = start + k
                        if (rowAt(idx / columns)[idx % columns]) { ok = false; break }
                    }
                    if (ok) break
                    start++
                }
                for (k in 0 until n) {
                    val idx = start + k
                    val x = idx % columns
                    val y = idx / columns
                    rowAt(y)[x] = true
                    cells[reservedRun.first + k] = Cell(x, y)
                    rows = maxOf(rows, y + 1)
                }
                i = reservedRun.last + 1
            } else {
                place(i, spans[i])
                i++
            }
        }

        @Suppress("UNCHECKED_CAST")
        return Layout((cells as Array<Cell>).toList(), rows)
    }
}

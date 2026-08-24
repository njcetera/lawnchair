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
     * [reservedRun] (WP folders): a contiguous index range whose FIRST index is an inline-expanded
     * folder tile and whose remaining indices are that folder's spliced children. It is laid out
     * Windows-Phone style (owner decision 2026-08-24), which is two rules working together:
     *
     *  1. The folder tile sits on the FRONTIER of the items before it -- the first free cell at or
     *     below the highest row those items occupy. It never backfills an earlier hole (left by a
     *     wide widget), so it keeps its natural in-flow position, may share its row with other
     *     tiles, and always has empty space directly beneath it.
     *  2. Its children then open into DEDICATED, EXCLUSIVE full-width rows starting on the row right
     *     below the folder. The whole child band's rows are reserved, so no unrelated tile invades
     *     them (the band's trailing empty cells are deliberate whitespace) and the grid resumes on a
     *     fresh row after the band.
     *
     * The effect: an open folder gets its own horizontal space with its apps directly under it,
     * instead of the apps flowing inline among other icons; everything after the folder is pushed
     * down, exactly as tapping a folder open did on Windows Phone. A run with any non-1x1 member, or
     * an out-of-range range, is ignored and those items pack individually (safe fallback, never a
     * crash). Items outside the run pack first-fit, so a no-run call is byte-for-byte the old
     * behaviour.
     *
     * The self-non-collision guarantee is preserved: the folder takes one checked-free cell and the
     * child band is laid only on rows verified empty, so a list still cannot collide with itself
     * (see the class KDoc / row-34).
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

        // Is a whole row of the occupancy grid free of any occupied cell?
        fun rowEmpty(y: Int): Boolean = rowAt(y).none { it }

        var i = 0
        while (i < spans.size) {
            if (runUsable && i == reservedRun!!.first) {
                // (1) Folder on the FRONTIER of the items before it: scan for the first free cell at
                // or below the highest currently-occupied row, so it never backfills an earlier
                // hole and always has empty space beneath it.
                var floor = 0
                for (y in occupied.indices) if (occupied[y].any { it }) floor = y
                var fy = floor
                var fx = -1
                while (fx < 0) {
                    val row = rowAt(fy)
                    val free = (0 until columns).firstOrNull { !row[it] }
                    if (free != null) fx = free else fy++
                }
                rowAt(fy)[fx] = true
                cells[reservedRun.first] = Cell(fx, fy)
                rows = maxOf(rows, fy + 1)

                // (2) Children into exclusive full-width rows directly below the folder's row. fy is
                // at or below the highest occupied row, so fy+1 is empty -- the band starts there.
                // Reserve the whole band (including a partial last row's trailing cells) so no later
                // tile drops into the folder's opened space.
                val childCount = reservedRun.last - reservedRun.first
                if (childCount > 0) {
                    val bandRows = (childCount + columns - 1) / columns
                    var startRow = fy + 1
                    while (!(0 until bandRows).all { dr -> rowEmpty(startRow + dr) }) startRow++
                    for (k in 0 until childCount) {
                        val x = k % columns
                        val y = startRow + k / columns
                        rowAt(y)[x] = true
                        cells[reservedRun.first + 1 + k] = Cell(x, y)
                    }
                    for (dr in 0 until bandRows) {
                        val row = rowAt(startRow + dr)
                        for (x in 0 until columns) row[x] = true
                    }
                    rows = maxOf(rows, startRow + bandRows)
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
